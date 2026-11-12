package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import datasys.semi.engine.StorageEngine;
import datasys.semi.models.SelectStatement;
import datasys.semi.parser.Binder;
import datasys.semi.parser.SqlParser;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;

/**
 * Exercise 10 Required Test 2: the binder resolves unqualified column names
 * against all FROM-list schemas. Unknown columns, ambiguous columns, joins
 * across mismatched types and joins whose two sides land in the same table
 * each throw IllegalArgumentException; the testbed queries bind.
 */
class MultiTableBinderTest {

    // --- Testbed Queries (Exercise 10 section 5) ---
    private static final List<String> TESTBED_QUERIES = List.of(
            "SELECT * FROM orders, customers WHERE o_custname = c_name;",
            "SELECT * FROM orders, customers, products WHERE o_custname = c_name AND o_prodname = p_name;",
            "SELECT * FROM orders, customers, products, cities "
                    + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;",
            "SELECT * FROM orders, customers, products "
                    + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';");

    // --- Front End ---
    private final SqlParser parser = new SqlParser();

    // --- State Under Test ---
    private StorageEngine engine;
    private Binder binder;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        engine = new StorageEngine(directory);
        engine.createTable("orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG),
                new ColumnSpec("o_custname", ColumnType.STRING),
                new ColumnSpec("o_prodname", ColumnType.STRING),
                new ColumnSpec("o_quantity", ColumnType.LONG)));
        engine.createTable("products", List.of(
                new ColumnSpec("p_name", ColumnType.STRING),
                new ColumnSpec("p_price", ColumnType.DOUBLE)));
        engine.createTable("customers", List.of(
                new ColumnSpec("c_name", ColumnType.STRING),
                new ColumnSpec("c_city", ColumnType.STRING)));
        engine.createTable("cities", List.of(
                new ColumnSpec("ct_city", ColumnType.STRING),
                new ColumnSpec("ct_country", ColumnType.STRING),
                new ColumnSpec("ct_population", ColumnType.LONG)));
        engine.createTable("left_t", List.of(
                new ColumnSpec("id", ColumnType.LONG),
                new ColumnSpec("l_name", ColumnType.STRING)));
        engine.createTable("right_t", List.of(
                new ColumnSpec("id", ColumnType.LONG),
                new ColumnSpec("r_name", ColumnType.STRING)));
        binder = new Binder(engine);
    }

    /**
     * Verifies that all four testbed queries bind.
     */
    @Test
    void testbedQueriesBind() {
        for (String sql : TESTBED_QUERIES) {
            assertDoesNotThrow(() -> binder.bind(parseSelect(sql)), sql);
        }
    }

    /**
     * Verifies that a column found in no FROM table is unknown, whether it
     * appears in a filter, a join or a column list, and even if another table
     * outside the FROM list owns it.
     */
    @Test
    void unknownColumnThrows() {
        assertBindFails("SELECT * FROM orders, customers WHERE o_custname = c_name AND missing = 1;",
                "unknown column: missing");
        assertBindFails("SELECT * FROM orders, customers WHERE o_custname = missing;",
                "unknown column: missing");
        assertBindFails("SELECT missing FROM orders, customers WHERE o_custname = c_name;",
                "unknown column: missing");
        assertBindFails("SELECT * FROM orders, customers WHERE o_prodname = p_name;",
                "unknown column: p_name");
    }

    /**
     * Verifies that a column found in two FROM tables is ambiguous in a filter,
     * in a join and in a column list, while the same name binds when only one
     * owner is listed.
     */
    @Test
    void ambiguousColumnThrows() {
        assertBindFails("SELECT * FROM left_t, right_t WHERE id = 1;", "ambiguous column: id (in left_t, right_t)");
        assertBindFails("SELECT * FROM left_t, right_t WHERE id = r_name;", "ambiguous column: id (in left_t, right_t)");
        assertBindFails("SELECT id FROM left_t, right_t WHERE l_name = r_name;",
                "ambiguous column: id (in left_t, right_t)");

        assertDoesNotThrow(() -> binder.bind(parseSelect("SELECT * FROM left_t, orders WHERE id = o_orderkey;")));
    }

    /**
     * Verifies that a join between columns of different types is rejected,
     * because hash join keys are compared with equals and would never match.
     */
    @Test
    void joinAcrossMismatchedTypesThrows() {
        IllegalArgumentException longDouble = bindFailure(
                "SELECT * FROM orders, products WHERE o_orderkey = p_price;");
        assertTrue(longDouble.getMessage().contains("compares LONG with DOUBLE"), longDouble.getMessage());

        IllegalArgumentException stringLong = bindFailure(
                "SELECT * FROM orders, cities WHERE o_custname = ct_population;");
        assertTrue(stringLong.getMessage().contains("compares STRING with LONG"), stringLong.getMessage());
    }

    /**
     * Verifies that a join whose two sides land in the same table is rejected,
     * whether the two columns differ or are the same column.
     */
    @Test
    void joinWithinOneTableThrows() {
        IllegalArgumentException sameTable = bindFailure(
                "SELECT * FROM orders, customers WHERE o_custname = c_name AND o_custname = o_prodname;");
        assertTrue(sameTable.getMessage().contains("must connect two different tables"), sameTable.getMessage());
        assertTrue(sameTable.getMessage().contains("orders"), sameTable.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(parseSelect("SELECT * FROM orders WHERE o_custname = o_custname;")));
    }

    /**
     * Verifies that filters bind against the table that owns their column: a
     * constant of the right type binds, a constant of the wrong type throws.
     */
    @Test
    void filtersBindAgainstOwningTable() {
        assertDoesNotThrow(() -> binder.bind(parseSelect(
                "SELECT * FROM orders, cities, customers "
                        + "WHERE o_custname = c_name AND c_city = ct_city AND ct_population > 500000;")));
        assertBindFails("SELECT * FROM orders, customers WHERE o_custname = c_name AND c_city = 5;",
                "constant type does not match column type: STRING");
    }

    /**
     * Verifies that column lists over joins bind when each column resolves to
     * one table.
     */
    @Test
    void columnListOverJoinBinds() {
        assertDoesNotThrow(() -> binder.bind(parseSelect(
                "SELECT c_name, o_quantity FROM orders, customers WHERE o_custname = c_name;")));
    }

    /**
     * Verifies that a FROM list naming a table twice and a FROM list naming an
     * unknown table are rejected.
     */
    @Test
    void repeatedOrUnknownTableThrows() {
        assertBindFails("SELECT * FROM orders, customers, orders WHERE o_custname = c_name;",
                "duplicate table in FROM list: orders");
        assertBindFails("SELECT * FROM orders, suppliers;", "unknown table: suppliers");
    }

    /**
     * Verifies the explicit-snapshot overload: snapshots must match the FROM
     * list one to one and in order, and the single-snapshot entry point rejects
     * multi-table statements.
     */
    @Test
    void snapshotsMustMatchFromList() {
        SelectStatement select = parseSelect(TESTBED_QUERIES.getFirst());

        assertDoesNotThrow(() -> binder.bind(select,
                List.of(engine.snapshot("orders"), engine.snapshot("customers"))));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(select, List.of(engine.snapshot("orders"))));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(select, List.of(engine.snapshot("customers"), engine.snapshot("orders"))));
        assertThrows(IllegalArgumentException.class, () -> binder.bind(select, engine.snapshot("orders")));
    }

    /**
     * Parses SQL text holding exactly one SELECT statement.
     *
     * @param sql SQL text with one SELECT statement
     * @return the parsed select statement
     */
    private SelectStatement parseSelect(String sql) {
        return (SelectStatement) parser.parse(sql).getFirst();
    }

    /**
     * Binds a statement that must fail and returns the failure.
     *
     * @param sql SQL text with one SELECT statement
     * @return the IllegalArgumentException thrown by the binder
     */
    private IllegalArgumentException bindFailure(String sql) {
        SelectStatement select = parseSelect(sql);
        return assertThrows(IllegalArgumentException.class, () -> binder.bind(select), sql);
    }

    /**
     * Asserts that binding fails with exactly the given message.
     *
     * @param sql             SQL text with one SELECT statement
     * @param expectedMessage expected exception message
     */
    private void assertBindFails(String sql, String expectedMessage) {
        assertEquals(expectedMessage, bindFailure(sql).getMessage(), sql);
    }
}
