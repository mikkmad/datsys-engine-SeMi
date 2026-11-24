package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.executor.Executor;
import datasys.semi.models.ScanStats;
import datasys.semi.models.SelectStatement;
import datasys.semi.operators.FilterOperator;
import datasys.semi.operators.HashJoinOperator;
import datasys.semi.operators.JoinEstimate;
import datasys.semi.operators.Operator;
import datasys.semi.operators.ProjectOperator;
import datasys.semi.operators.ScanOperator;
import datasys.semi.optimizer.JoinOrderMode;
import datasys.semi.parser.Binder;
import datasys.semi.parser.SqlParser;
import datasys.semi.planner.Planner;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;

/**
 * Exercise 10 Required Test 5, under {@code -j syntax}: the planner builds
 * left-deep hash join chains in FROM-list order with filters below the joins,
 * per-table pruning stays visible in ScanStats, and unconnected FROM lists are
 * rejected. Also checks the four testbed queries of section 5 end to end
 * through the Executor.
 *
 * <p>
 * Exercise 11 Required Test 5: under {@code -j optimized}, a rearranged plan
 * still emits {@code SELECT *} and column lists in FROM-list order, identical
 * to {@code -j syntax}.
 *
 * <p>
 * Exercise 11 §5: every planned join carries its estimate, and every executed
 * join leaves one log line with {@code estRows} and {@code rowsOut}, next to
 * one {@code joinOrder} line per statement, all attributable through
 * {@code statementNumber}.
 *
 * <p>
 * The engine stores at most two rows per partition, so orders and customers
 * span two partitions each and pruning on o_orderkey is observable.
 */
class JoinPlannerTest {

    // --- Testbed Queries (Exercise 10 section 5) ---
    private static final String ORDERS_CUSTOMERS =
            "SELECT * FROM orders, customers WHERE o_custname = c_name;";
    private static final String ORDERS_CUSTOMERS_PRODUCTS =
            "SELECT * FROM orders, customers, products WHERE o_custname = c_name AND o_prodname = p_name;";
    private static final String FOUR_TABLES = "SELECT * FROM orders, customers, products, cities "
            + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;";
    private static final String COPENHAGEN_ONLY = "SELECT * FROM orders, customers, products "
            + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';";

    // --- Log Format (following conventions from Exercise 1 §3) ---
    private static final int LOG_FIELD_COUNT = 7;
    private static final int STATEMENT_NUMBER_FIELD = 2;
    private static final int MESSAGE_FIELD = 6;

    // --- Front End ---
    private final SqlParser parser = new SqlParser();

    // --- State Under Test ---
    private StorageEngine engine;
    private Binder binder;
    private Planner planner;
    private Planner optimizedPlanner;

    @BeforeEach
    void setUp(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory.resolve("joins"));
        engine = new StorageEngine(directory, 2);
        createAndLoad(directory, "orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG),
                new ColumnSpec("o_custname", ColumnType.STRING),
                new ColumnSpec("o_prodname", ColumnType.STRING),
                new ColumnSpec("o_quantity", ColumnType.LONG)));
        createAndLoad(directory, "products", List.of(
                new ColumnSpec("p_name", ColumnType.STRING),
                new ColumnSpec("p_price", ColumnType.DOUBLE)));
        createAndLoad(directory, "customers", List.of(
                new ColumnSpec("c_name", ColumnType.STRING),
                new ColumnSpec("c_city", ColumnType.STRING)));
        createAndLoad(directory, "cities", List.of(
                new ColumnSpec("ct_city", ColumnType.STRING),
                new ColumnSpec("ct_country", ColumnType.STRING),
                new ColumnSpec("ct_population", ColumnType.LONG)));
        binder = new Binder(engine);
        planner = new Planner(engine, JoinOrderMode.SYNTAX);
        optimizedPlanner = new Planner(engine, JoinOrderMode.OPTIMIZED);
    }

    /**
     * Verifies that a four-table query builds three hash joins, left-deep in
     * FROM-list order: each join's build side is the previous join and its
     * probe side is the next table's scan, with keys at the joined-row index of
     * the earlier column and the in-table index of the new table's column.
     */
    @Test
    void fourTableQueryBuildsThreeLeftDeepHashJoinsInFromListOrder() {
        Operator plan = plan(FOUR_TABLES);

        HashJoinOperator citiesJoin = assertInstanceOf(HashJoinOperator.class, plan);
        assertJoin(citiesJoin, 5, 0, "cities");
        HashJoinOperator productsJoin = assertInstanceOf(HashJoinOperator.class, citiesJoin.build());
        assertJoin(productsJoin, 2, 0, "products");
        HashJoinOperator customersJoin = assertInstanceOf(HashJoinOperator.class, productsJoin.build());
        assertJoin(customersJoin, 1, 0, "customers");
        assertEquals("orders", assertInstanceOf(ScanOperator.class, customersJoin.build()).tableName());
    }

    /**
     * Verifies that filters are applied below the joins, on the pipeline of the
     * table that owns their column, and that pruning still works per table and
     * shows up in each table's ScanStats.
     */
    @Test
    void filtersStayBelowJoinsAndPruningIsVisiblePerTable() {
        Operator plan = plan("SELECT * FROM orders, customers, products WHERE o_custname = c_name "
                + "AND o_prodname = p_name AND c_city = 'Copenhagen' AND o_orderkey > 101;");

        HashJoinOperator productsJoin = assertInstanceOf(HashJoinOperator.class, plan);
        assertEquals("products", assertInstanceOf(ScanOperator.class, productsJoin.probe()).tableName());
        HashJoinOperator customersJoin = assertInstanceOf(HashJoinOperator.class, productsJoin.build());

        FilterOperator ordersFilter = assertInstanceOf(FilterOperator.class, customersJoin.build());
        assertEquals(0, ordersFilter.predicate().columnIndex());
        ScanOperator ordersScan = assertInstanceOf(ScanOperator.class, ordersFilter.child());
        assertEquals(List.of(1), ordersScan.partitionNumbers());

        FilterOperator customersFilter = assertInstanceOf(FilterOperator.class, customersJoin.probe());
        assertEquals(1, customersFilter.predicate().columnIndex());
        assertEquals("customers", assertInstanceOf(ScanOperator.class, customersFilter.child()).tableName());

        assertEquals(List.of("orders", "customers", "products"),
                List.copyOf(planner.lastScanStatsByTable().keySet()));
        assertEquals(new ScanStats(2, 1, 1, 1), planner.lastScanStatsByTable().get("orders"));
        assertEquals(new ScanStats(2, 2, 0, 1), planner.lastScanStatsByTable().get("customers"));
        assertEquals(new ScanStats(1, 1, 0, 1), planner.lastScanStatsByTable().get("products"));
        assertEquals(new ScanStats(2, 1, 1, 1), planner.lastScanStats());

        assertEquals(List.of("102,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0",
                "103,Anna,Helmet,1,Anna,Copenhagen,Helmet,299.0"), drainSorted(plan));
    }

    /**
     * Verifies that the new table may appear on either side of the join
     * predicate: the keys are the same.
     */
    @Test
    void joinPredicateSidesMayBeWrittenInEitherOrder() {
        HashJoinOperator join = assertInstanceOf(HashJoinOperator.class,
                plan("SELECT * FROM orders, customers WHERE c_name = o_custname;"));

        assertJoin(join, 1, 0, "customers");
    }

    /**
     * Verifies that a table not connected to the tables before it is rejected
     * with a message suggesting a reordered FROM list, and that the reordered
     * FROM list plans.
     */
    @Test
    void unconnectedFromListIsRejectedWithReorderingHint() {
        IllegalArgumentException unconnected = assertThrows(IllegalArgumentException.class,
                () -> plan("SELECT * FROM orders, cities, customers WHERE o_custname = c_name AND c_city = ct_city;"));
        assertEquals("table cities is not connected by a join predicate to the tables before it (orders); "
                + "reorder the FROM list so that each table joins a table listed before it",
                unconnected.getMessage());

        IllegalArgumentException noJoins = assertThrows(IllegalArgumentException.class,
                () -> plan("SELECT * FROM orders, customers;"));
        assertEquals("table customers is not connected by a join predicate to the tables before it (orders); "
                + "reorder the FROM list so that each table joins a table listed before it",
                noJoins.getMessage());

        assertInstanceOf(HashJoinOperator.class, plan(
                "SELECT * FROM orders, customers, cities WHERE o_custname = c_name AND c_city = ct_city;"));
    }

    /**
     * Verifies the documented simplification: a second join predicate that
     * connects the same table to the earlier tables is rejected.
     */
    @Test
    void secondConnectingPredicateForOneTableIsRejected() {
        IllegalArgumentException twoPredicates = assertThrows(IllegalArgumentException.class,
                () -> plan("SELECT * FROM orders, customers WHERE o_custname = c_name AND o_prodname = c_city;"));

        assertEquals("table customers is connected by 2 join predicates (o_custname = c_name and "
                + "o_prodname = c_city); only one join predicate per table is supported", twoPredicates.getMessage());
    }

    /**
     * Verifies that a column list over a join projects joined-row columns.
     */
    @Test
    void columnListOverJoinProjectsJoinedColumns() {
        Operator plan = plan("SELECT c_city, o_orderkey FROM orders, customers WHERE o_custname = c_name;");

        assertInstanceOf(ProjectOperator.class, plan);
        assertEquals(List.of("Aarhus,101", "Copenhagen,100", "Copenhagen,102", "Copenhagen,103"), drainSorted(plan));
    }

    /**
     * Verifies that single-table plans keep their Part 1 shape and statistics.
     */
    @Test
    void singleTablePlanKeepsFilterOverScanShape() {
        Operator plan = plan("SELECT * FROM orders WHERE o_orderkey > 101;");

        FilterOperator filter = assertInstanceOf(FilterOperator.class, plan);
        assertEquals(List.of(1), assertInstanceOf(ScanOperator.class, filter.child()).partitionNumbers());
        assertEquals(List.of("orders"), List.copyOf(planner.lastScanStatsByTable().keySet()));
    }

    /**
     * Verifies the four testbed queries end to end through the Executor: the
     * first three return one row per order, the filtered one returns three.
     */
    @Test
    void testbedQueriesReturnHandCheckedRows() {
        List<String> ordersCustomers = List.of(
                "100,Anna,Bike,1,Anna,Copenhagen",
                "101,Bob,Helmet,2,Bob,Aarhus",
                "102,Carol,Bike,1,Carol,Copenhagen",
                "103,Anna,Helmet,1,Anna,Copenhagen");
        List<String> withProducts = List.of(
                "100,Anna,Bike,1,Anna,Copenhagen,Bike,2999.0",
                "101,Bob,Helmet,2,Bob,Aarhus,Helmet,299.0",
                "102,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0",
                "103,Anna,Helmet,1,Anna,Copenhagen,Helmet,299.0");
        List<String> withCities = List.of(
                "100,Anna,Bike,1,Anna,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000",
                "101,Bob,Helmet,2,Bob,Aarhus,Helmet,299.0,Aarhus,Denmark,290000",
                "102,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000",
                "103,Anna,Helmet,1,Anna,Copenhagen,Helmet,299.0,Copenhagen,Denmark,1400000");

        assertEquals(ordersCustomers, executeSorted(ORDERS_CUSTOMERS));
        assertEquals(withProducts, executeSorted(ORDERS_CUSTOMERS_PRODUCTS));
        assertEquals(withCities, executeSorted(FOUR_TABLES));
        assertEquals(List.of(withProducts.get(0), withProducts.get(2), withProducts.get(3)),
                executeSorted(COPENHAGEN_ONLY));
    }

    /**
     * Verifies that a joined query logs one HashJoin summary per join and one
     * SELECT summary line per FROM table.
     *
     * @throws IOException if the log file cannot be read
     */
    @Test
    void joinedQueryLogsEveryJoinAndOneSummaryPerTable() throws IOException {
        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        try {
            executeSorted(ORDERS_CUSTOMERS_PRODUCTS);
        } finally {
            MDC.remove("sessionId");
        }

        List<String> sessionLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8)
                .stream().filter(line -> line.contains("," + session + ",")).toList();
        assertEquals(List.of(
                "operator=HashJoin join=1 tables=orders+customers estRows=3 buildKey=1 probeKey=0 rowsBuild=4"
                        + " rowsProbe=3 rowsOut=4",
                "operator=HashJoin join=2 tables=orders+customers+products estRows=2 buildKey=2 probeKey=0"
                        + " rowsBuild=4 rowsProbe=2 rowsOut=4"),
                sessionLines.stream().filter(line -> line.contains("operator=HashJoin"))
                        .map(line -> line.substring(line.indexOf("operator=HashJoin"))).sorted().toList());
        assertEquals(List.of("customers", "orders", "products"),
                sessionLines.stream().filter(line -> line.contains("statement=SELECT table="))
                        .map(line -> line.replaceAll(".*statement=SELECT table=(\\w+) rowsOut=4 .*", "$1"))
                        .sorted().toList());
    }

    /**
     * Exercise 11 Required Test 5: customers (3 rows) is estimated smaller than
     * orders (4 rows), so the optimized plan lets customers build. The joined row
     * then starts with the customers columns, and a projection restores the
     * FROM-list order: the rows equal those of the syntax plan.
     */
    @Test
    void rearrangedPlanStillEmitsSelectStarColumnsInFromListOrder() {
        Operator optimized = plan(ORDERS_CUSTOMERS, optimizedPlanner);

        ProjectOperator restoreFromListOrder = assertInstanceOf(ProjectOperator.class, optimized);
        HashJoinOperator join = assertInstanceOf(HashJoinOperator.class, restoreFromListOrder.child());
        assertEquals("customers", assertInstanceOf(ScanOperator.class, join.build()).tableName());
        assertEquals("orders", assertInstanceOf(ScanOperator.class, join.probe()).tableName());
        assertEquals(0, join.buildKey());
        assertEquals(1, join.probeKey());
        assertEquals(drainSorted(plan(ORDERS_CUSTOMERS)), drainSorted(optimized));
    }

    /**
     * Exercise 11 Required Test 5: a column list is resolved through the
     * rearranged layout, so it selects the same values as under the syntax plan.
     */
    @Test
    void rearrangedPlanResolvesColumnListThroughJoinedLayout() {
        String sql = "SELECT c_city, o_orderkey, p_price FROM orders, customers, products "
                + "WHERE o_custname = c_name AND o_prodname = p_name;";

        assertEquals(drainSorted(plan(sql)), drainSorted(plan(sql, optimizedPlanner)));
    }

    /**
     * Exercise 11 Required Test 5: the testbed queries with two, three and four
     * tables print identical rows, with identical column order, under both join
     * order modes.
     */
    @Test
    void testbedQueriesPrintIdenticalRowsUnderBothJoinOrderModes() {
        for (String sql : List.of(ORDERS_CUSTOMERS, ORDERS_CUSTOMERS_PRODUCTS, FOUR_TABLES, COPENHAGEN_ONLY)) {
            assertEquals(executeSorted(sql, JoinOrderMode.SYNTAX), executeSorted(sql, JoinOrderMode.OPTIMIZED),
                    sql);
        }
    }

    /**
     * Verifies that the optimized planner does not depend on the FROM-list order
     * for connectivity, but still rejects a query that needs a cross product.
     */
    @Test
    void optimizedPlannerReordersUnconnectedFromListButRejectsCrossProducts() {
        String unconnectedInFromOrder = "SELECT * FROM orders, cities, customers "
                + "WHERE o_custname = c_name AND c_city = ct_city;";
        assertEquals(4, drainSorted(plan(unconnectedInFromOrder, optimizedPlanner)).size());

        IllegalArgumentException crossProduct = assertThrows(IllegalArgumentException.class,
                () -> plan("SELECT * FROM orders, customers;", optimizedPlanner));
        assertEquals("no join predicate connects the FROM tables (orders+customers); "
                + "cross products are not supported", crossProduct.getMessage());
    }

    /**
     * Exercise 11 §5: the planner hands every join the orderer's estimate,
     * unrounded, with its one-based number and the tables it covers in join
     * order. Under {@code -j syntax} the four-table chain is estimated at
     * 4·3/4 = 3, then 3·2/4 = 1.5, then 1.5·2/3 = 1 rows (sum-rule NDVs over
     * two-row partitions: o_custname 4, c_name 3, o_prodname 4, p_name 2,
     * c_city 3, ct_city 2).
     */
    @Test
    void plannedJoinsCarryTheOrdererEstimates() {
        HashJoinOperator citiesJoin = assertInstanceOf(HashJoinOperator.class, plan(FOUR_TABLES));
        HashJoinOperator productsJoin = assertInstanceOf(HashJoinOperator.class, citiesJoin.build());
        HashJoinOperator customersJoin = assertInstanceOf(HashJoinOperator.class, productsJoin.build());

        assertEquals(new JoinEstimate(1, List.of("orders", "customers"), 3.0), customersJoin.estimate());
        assertEquals(new JoinEstimate(2, List.of("orders", "customers", "products"), 1.5),
                productsJoin.estimate());
        assertEquals(new JoinEstimate(3, List.of("orders", "customers", "products", "cities"), 1.0),
                citiesJoin.estimate());
    }

    /**
     * Exercise 11 §5 and Required Test 6 (log part), under {@code -j optimized}:
     * a script of a three-table, a four-table and a single-table SELECT. Each
     * joined statement logs its join order once and one line per executed join
     * with estimate and actual output, all under its own statementNumber; the
     * single-table statement logs neither. The greedy order starts with
     * products ⋈ orders (4·2/4 = 2, the first of two predicates estimated at 2).
     *
     * @throws IOException if the log file cannot be read
     */
    @Test
    void optimizedJoinsLogEstimateAndActualPerStatement() throws IOException {
        List<String[]> logLines = executeScriptAndReadLog(
                ORDERS_CUSTOMERS_PRODUCTS + FOUR_TABLES + "SELECT * FROM orders;", JoinOrderMode.OPTIMIZED);

        assertEquals(List.of(
                "statement=SELECT joinOrder=products+orders+customers mode=optimized estRows=2",
                "join=1 tables=products+orders estRows=2 rowsOut=4",
                "join=2 tables=products+orders+customers estRows=2 rowsOut=4"), joinLog(logLines, "1"));
        assertEquals(List.of(
                "statement=SELECT joinOrder=products+orders+customers+cities mode=optimized estRows=1",
                "join=1 tables=products+orders estRows=2 rowsOut=4",
                "join=2 tables=products+orders+customers estRows=2 rowsOut=4",
                "join=3 tables=products+orders+customers+cities estRows=1 rowsOut=4"), joinLog(logLines, "2"));
        assertEquals(List.of(), joinLog(logLines, "3"));
    }

    /**
     * Exercise 11 §5 and Required Test 6 (log part), under {@code -j syntax}:
     * the same lines, in {@code FROM}-list order and with the syntax plan's
     * estimates, so week 12 can compare both modes from the log.
     *
     * @throws IOException if the log file cannot be read
     */
    @Test
    void syntaxJoinsLogEstimateAndActualPerStatement() throws IOException {
        List<String[]> logLines = executeScriptAndReadLog(FOUR_TABLES, JoinOrderMode.SYNTAX);

        assertEquals(List.of(
                "statement=SELECT joinOrder=orders+customers+products+cities mode=syntax estRows=1",
                "join=1 tables=orders+customers estRows=3 rowsOut=4",
                "join=2 tables=orders+customers+products estRows=2 rowsOut=4",
                "join=3 tables=orders+customers+products+cities estRows=1 rowsOut=4"), joinLog(logLines, "1"));
    }

    /**
     * Executes a SQL script through an Executor in the given mode under a fresh
     * session and returns that session's log lines.
     *
     * @param sql           one or more SELECT statements
     * @param joinOrderMode join order mode of the executor
     * @return the session's log lines split into the seven CSV fields
     * @throws IOException if the log file cannot be read
     */
    private List<String[]> executeScriptAndReadLog(String sql, JoinOrderMode joinOrderMode) throws IOException {
        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        try {
            executeSorted(sql, joinOrderMode);
        } finally {
            MDC.remove("sessionId");
        }
        return Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains("," + session + ","))
                .map(line -> line.split(",", LOG_FIELD_COUNT))
                .toList();
    }

    /**
     * Extracts a statement's join order line and, per executed join, the join
     * number, tables, estimate and actual output, in join number order.
     *
     * @param logLines        a session's log lines split into CSV fields
     * @param statementNumber the statement whose lines are wanted
     * @return the join order message followed by one summary per join
     */
    private static List<String> joinLog(List<String[]> logLines, String statementNumber) {
        List<String> messages = logLines.stream()
                .filter(fields -> fields[STATEMENT_NUMBER_FIELD].equals(statementNumber))
                .map(fields -> fields[MESSAGE_FIELD])
                .toList();
        List<String> joinLog = new ArrayList<>(messages.stream()
                .filter(message -> message.startsWith("statement=SELECT joinOrder="))
                .toList());
        joinLog.addAll(messages.stream()
                .filter(message -> message.startsWith("operator=HashJoin "))
                .map(message -> message.replaceAll(
                        "operator=HashJoin (join=\\d+ tables=\\S+ estRows=\\d+) .* (rowsOut=\\d+)", "$1 $2"))
                .sorted()
                .toList());
        return joinLog;
    }

    /**
     * Creates a testbed table and loads its CSV from the test resources.
     *
     * @param directory temporary engine directory
     * @param tableName table to create
     * @param schema    table columns
     * @throws IOException if the CSV resource cannot be copied
     */
    private void createAndLoad(Path directory, String tableName, List<ColumnSpec> schema) throws IOException {
        Path csvPath = UtilsTest.copyResource(directory, "joins/" + tableName + ".csv");
        engine.createTable(tableName, schema);
        engine.copyFile(engine.snapshot(tableName), csvPath.toString());
    }

    /**
     * Parses, binds and plans one SELECT against the current snapshots.
     *
     * @param sql SQL text with one SELECT statement
     * @return the planned operator tree
     */
    private Operator plan(String sql) {
        return plan(sql, planner);
    }

    /**
     * Parses, binds and plans one SELECT with the given planner.
     *
     * @param sql            SQL text with one SELECT statement
     * @param chosenPlanner  planner in the wanted join order mode
     * @return the planned operator tree
     */
    private Operator plan(String sql, Planner chosenPlanner) {
        SelectStatement select = (SelectStatement) parser.parse(sql).getFirst();
        List<Catalog> snapshots = select.tableNames().stream().map(engine::snapshot).toList();
        binder.bind(select, snapshots);
        return chosenPlanner.plan(select, snapshots);
    }

    /**
     * Asserts a join's keys and the table of its probe-side scan.
     *
     * @param join           the join operator
     * @param buildKey       expected build key index
     * @param probeKey       expected probe key index
     * @param probeTableName expected table scanned on the probe side
     */
    private static void assertJoin(HashJoinOperator join, int buildKey, int probeKey, String probeTableName) {
        assertEquals(buildKey, join.buildKey());
        assertEquals(probeKey, join.probeKey());
        assertEquals(probeTableName, assertInstanceOf(ScanOperator.class, join.probe()).tableName());
    }

    /**
     * Drains an operator tree into sorted CSV lines.
     *
     * @param plan operator tree to drain
     * @return emitted rows as comma-joined strings, sorted
     */
    private static List<String> drainSorted(Operator plan) {
        List<String> lines = new ArrayList<>();
        plan.open();
        for (Object[] row = plan.next(); row != null; row = plan.next()) {
            lines.add(String.join(",", Arrays.stream(row).map(String::valueOf).toList()));
        }
        plan.close();
        return lines.stream().sorted().toList();
    }

    /**
     * Executes one SELECT through the Executor and returns its sorted CSV output.
     *
     * @param sql SQL text with one SELECT statement
     * @return output lines, sorted
     */
    private List<String> executeSorted(String sql) {
        return executeSorted(sql, JoinOrderMode.SYNTAX);
    }

    /**
     * Executes one SELECT through an Executor in the given join order mode and
     * returns its sorted CSV output.
     *
     * @param sql           SQL text with one SELECT statement
     * @param joinOrderMode join order mode of the executor
     * @return output lines, sorted
     */
    private List<String> executeSorted(String sql, JoinOrderMode joinOrderMode) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new Executor(engine, new PrintStream(buffer, true, StandardCharsets.UTF_8), joinOrderMode).execute(sql);
        return buffer.toString(StandardCharsets.UTF_8).lines().sorted().toList();
    }
}
