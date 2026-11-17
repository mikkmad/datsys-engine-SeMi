package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import datasys.semi.models.DeleteStatement;
import datasys.semi.models.JoinPredicate;
import datasys.semi.models.Predicate;
import datasys.semi.models.SelectStatement;
import datasys.semi.models.Statement;
import datasys.semi.parser.SqlParseException;
import datasys.semi.parser.SqlParser;
import datasys.semi.parser.SqlPrinter;
import datasys.semi.schema.Comparison;

/**
 * Exercise 10 Required Test 1: the grammar accepts table lists and {@code AND}
 * chains, sorts column-to-column predicates into {@code joins} and
 * column-to-constant predicates into {@code filters}, and the printer
 * round-trip {@code parse(print(s)) = s} covers the new shapes.
 */
class SqlJoinGrammarTest {

    // --- Front End Under Test ---
    private final SqlParser parser = new SqlParser();
    private final SqlPrinter printer = new SqlPrinter();

    /**
     * Verifies that a FROM list of several tables parses in source order.
     */
    @Test
    void tableListParsesInFromListOrder() {
        SelectStatement select = parseSelect("SELECT * FROM orders, customers, products, cities;");

        assertEquals(List.of("orders", "customers", "products", "cities"), select.tableNames());
        assertEquals(List.of(), select.filters());
        assertEquals(List.of(), select.joins());
    }

    /**
     * Verifies that column-to-column predicates land in joins and
     * column-to-constant predicates in filters, each list keeping source order
     * even when the two shapes are interleaved.
     */
    @Test
    void interleavedAndChainIsSortedIntoFiltersAndJoins() {
        SelectStatement select = parseSelect("SELECT * FROM orders, customers, products "
                + "WHERE o_custname = c_name AND c_city = 'Copenhagen' AND o_prodname = p_name AND o_quantity > 1;");

        assertEquals(List.of(
                new JoinPredicate("o_custname", "c_name"),
                new JoinPredicate("o_prodname", "p_name")), select.joins());
        assertEquals(List.of(
                new Predicate("c_city", Comparison.EQUALS, "Copenhagen"),
                new Predicate("o_quantity", Comparison.GREATER_THAN, 1L)), select.filters());
    }

    /**
     * Verifies the four testbed queries of Exercise 10 section 5 parse to the
     * expected ASTs.
     */
    @Test
    void testbedQueriesParseToExpectedAsts() {
        JoinPredicate ordersCustomers = new JoinPredicate("o_custname", "c_name");
        JoinPredicate ordersProducts = new JoinPredicate("o_prodname", "p_name");
        JoinPredicate customersCities = new JoinPredicate("c_city", "ct_city");

        assertEquals(select(List.of("orders", "customers"), List.of(), List.of(ordersCustomers)),
                parseSelect("SELECT * FROM orders, customers WHERE o_custname = c_name;"));
        assertEquals(select(List.of("orders", "customers", "products"), List.of(),
                List.of(ordersCustomers, ordersProducts)),
                parseSelect("SELECT * FROM orders, customers, products "
                        + "WHERE o_custname = c_name AND o_prodname = p_name;"));
        assertEquals(select(List.of("orders", "customers", "products", "cities"), List.of(),
                List.of(ordersCustomers, ordersProducts, customersCities)),
                parseSelect("SELECT * FROM orders, customers, products, cities "
                        + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;"));
        assertEquals(select(List.of("orders", "customers", "products"),
                List.of(new Predicate("c_city", Comparison.EQUALS, "Copenhagen")),
                List.of(ordersCustomers, ordersProducts)),
                parseSelect("SELECT * FROM orders, customers, products "
                        + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';"));
    }

    /**
     * Verifies that a chain of filters on a single table parses into filters
     * only.
     */
    @Test
    void andChainOfFiltersParsesIntoFiltersOnly() {
        SelectStatement select = parseSelect("SELECT * FROM trips WHERE distance > 100 AND price < 50.5;");

        assertEquals(List.of(
                new Predicate("distance", Comparison.GREATER_THAN, 100L),
                new Predicate("price", Comparison.LESS_THAN, 50.5)), select.filters());
        assertEquals(List.of(), select.joins());
    }

    /**
     * Verifies that AND is a case-insensitive keyword while identifiers that
     * merely start with "and" remain identifiers.
     */
    @Test
    void andKeywordIsCaseInsensitiveAndPrefixedIdentifiersStayIdentifiers() {
        SelectStatement select = parseSelect("select * from andes, t2 where andes_id = t2_id aNd android = 1;");

        assertEquals(List.of("andes", "t2"), select.tableNames());
        assertEquals(List.of(new JoinPredicate("andes_id", "t2_id")), select.joins());
        assertEquals(List.of(new Predicate("android", Comparison.EQUALS, 1L)), select.filters());
    }

    /**
     * Verifies that column lists (Part 1 extension) still combine with table
     * lists.
     */
    @Test
    void columnListCombinesWithTableList() {
        SelectStatement select = parseSelect("SELECT c_name, o_quantity FROM orders, customers "
                + "WHERE o_custname = c_name;");

        assertEquals(Optional.of(List.of("c_name", "o_quantity")), select.columns());
        assertEquals(List.of("orders", "customers"), select.tableNames());
    }

    /**
     * Verifies that shapes outside the conjunctive grammar are rejected with a
     * positioned parse error.
     */
    @Test
    void unsupportedConditionShapesAreRejectedByTheParser() {
        List<String> rejectedSql = List.of(
                "SELECT * FROM a, b WHERE a_id < b_id;",
                "SELECT * FROM a, b WHERE a_id = b_id OR a_x = 1;",
                "SELECT * FROM a, b WHERE (a_id = b_id);",
                "SELECT * FROM a, b WHERE a_id = b_id AND;",
                "SELECT * FROM a, b WHERE AND a_id = b_id;",
                "SELECT * FROM a, WHERE a_id = 1;",
                "SELECT * FROM a b;",
                "SELECT * FROM a, b WHERE 1 = a_id;",
                "SELECT * FROM a WHERE AND = 1;");
        for (String sql : rejectedSql) {
            SqlParseException exception = assertThrows(SqlParseException.class, () -> parser.parse(sql), sql);
            assertEquals(1, exception.line(), sql);
        }
    }

    /**
     * Verifies that DELETE keeps its restricted form: one table and at most one
     * column-to-constant filter.
     */
    @Test
    void deleteKeepsSingleTableSingleFilterForm() {
        assertEquals(List.of(new DeleteStatement("trips",
                Optional.of(new Predicate("distance", Comparison.EQUALS, 1L)))),
                parser.parse("DELETE FROM trips WHERE distance = 1;"));

        List<String> rejectedSql = List.of(
                "DELETE FROM trips, cities;",
                "DELETE FROM trips WHERE distance = 1 AND price = 2.0;",
                "DELETE FROM trips WHERE distance = price;");
        for (String sql : rejectedSql) {
            assertThrows(SqlParseException.class, () -> parser.parse(sql), sql);
        }
    }

    /**
     * Verifies the printer emits filters before joins joined by AND, and that
     * the output reparses to an equal statement.
     */
    @Test
    void printerEmitsTableListAndFiltersBeforeJoins() {
        SelectStatement select = select(List.of("orders", "customers", "products"),
                List.of(new Predicate("c_city", Comparison.EQUALS, "Copenhagen")),
                List.of(new JoinPredicate("o_custname", "c_name"), new JoinPredicate("o_prodname", "p_name")));

        assertEquals("SELECT * FROM orders, customers, products WHERE c_city = 'Copenhagen' "
                + "AND o_custname = c_name AND o_prodname = p_name;", printer.print(select));
        assertRoundTrip(select);
    }

    /**
     * Verifies the round-trip property over the new shapes: table lists alone,
     * joins alone, filters alone, mixed conjunctions, and column lists.
     */
    @Test
    void roundTripCoversTableListsJoinsAndConjunctions() {
        List<Statement> statements = new ArrayList<>();
        statements.add(select(List.of("orders", "customers"), List.of(), List.of()));
        statements.add(select(List.of("orders", "customers"), List.of(),
                List.of(new JoinPredicate("o_custname", "c_name"))));
        statements.add(select(List.of("trips"), List.of(
                new Predicate("city", Comparison.LESS_THAN, "Odense"),
                new Predicate("distance", Comparison.GREATER_THAN, -5L),
                new Predicate("price", Comparison.EQUALS, 1.0E7)), List.of()));
        statements.add(select(List.of("orders", "customers", "products", "cities"),
                List.of(new Predicate("o_quantity", Comparison.GREATER_THAN, 1L),
                        new Predicate("ct_population", Comparison.LESS_THAN, 1000000L)),
                List.of(new JoinPredicate("o_custname", "c_name"),
                        new JoinPredicate("o_prodname", "p_name"),
                        new JoinPredicate("c_city", "ct_city"))));
        statements.add(new SelectStatement(List.of("orders", "customers"),
                Optional.of(List.of("c_name", "o_quantity")), List.of(),
                List.of(new JoinPredicate("o_custname", "c_name"))));

        statements.forEach(this::assertRoundTrip);
    }

    /**
     * Verifies that parsed statements, printed and reparsed, are stable even
     * when the source interleaves filters and joins.
     */
    @Test
    void interleavedSourceRoundTripsThroughCanonicalOrder() {
        SelectStatement parsed = parseSelect("SELECT * FROM a, b WHERE a_id = b_id AND a_x = 1 AND a_y = b_y;");

        assertEquals("SELECT * FROM a, b WHERE a_x = 1 AND a_id = b_id AND a_y = b_y;", printer.print(parsed));
        assertRoundTrip(parsed);
    }

    /**
     * Verifies the AST records reject invalid arguments.
     */
    @Test
    void astRecordsRejectInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new JoinPredicate(null, "b"));
        assertThrows(IllegalArgumentException.class, () -> new JoinPredicate("a", " "));
        assertThrows(IllegalArgumentException.class, () -> select(List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> select(List.of("a", ""), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> select(null, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> select(List.of("a"), null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> select(List.of("a"), List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new SelectStatement(List.of("a"), null, List.of(), List.of()));

        List<JoinPredicate> joinsWithNull = new ArrayList<>();
        joinsWithNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> select(List.of("a"), List.of(), joinsWithNull));
    }

    /**
     * Verifies that SelectStatement defensively copies its lists, so later
     * mutation of the caller's list cannot change the AST.
     */
    @Test
    void selectStatementDefensivelyCopiesItsLists() {
        List<String> tableNames = new ArrayList<>(List.of("orders", "customers"));
        SelectStatement select = select(tableNames, List.of(), List.of());
        tableNames.add("products");

        assertEquals(List.of("orders", "customers"), select.tableNames());
        assertThrows(UnsupportedOperationException.class, () -> select.tableNames().add("cities"));
    }

    /**
     * Builds a {@code SELECT *} statement.
     *
     * @param tableNames tables in FROM-list order
     * @param filters    column-to-constant predicates
     * @param joins      column-to-column predicates
     * @return the select statement
     */
    private static SelectStatement select(List<String> tableNames, List<Predicate> filters,
            List<JoinPredicate> joins) {
        return new SelectStatement(tableNames, Optional.empty(), filters, joins);
    }

    /**
     * Parses SQL text holding exactly one SELECT statement.
     *
     * @param sql SQL text with one SELECT statement
     * @return the parsed select statement
     */
    private SelectStatement parseSelect(String sql) {
        List<Statement> statements = parser.parse(sql);
        assertEquals(1, statements.size());
        return (SelectStatement) statements.getFirst();
    }

    /**
     * Asserts that printing and reparsing a statement yields an equal statement.
     *
     * @param statement statement to round-trip
     */
    private void assertRoundTrip(Statement statement) {
        String printedSql = printer.print(statement);
        assertEquals(List.of(statement), parser.parse(printedSql), printedSql);
    }
}
