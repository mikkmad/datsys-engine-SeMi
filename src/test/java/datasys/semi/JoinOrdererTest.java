package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import datasys.semi.optimizer.JoinEdge;
import datasys.semi.optimizer.JoinOrder;
import datasys.semi.optimizer.JoinOrderer;
import datasys.semi.optimizer.JoinSide;
import datasys.semi.optimizer.JoinStep;
import datasys.semi.optimizer.TableEstimate;

/**
 * Exercise 11 Required Tests 3 and 4: on hand-made statistics, the greedy order
 * picks the expected join order, never a cross product while a connected table
 * exists, and lets the smaller estimated input build. Also checks that the
 * syntax order reproduces week 10's plan and its error messages.
 */
class JoinOrdererTest {

    // --- Fixture: FROM orders, customers, cities with a tiny filtered cities ---
    private static final List<TableEstimate> CHAIN_TABLES = List.of(
            new TableEstimate("orders", 10_000),
            new TableEstimate("customers", 1_000),
            new TableEstimate("cities", 1));
    private static final JoinEdge ORDERS_CUSTOMERS = edge(0, "o_custkey", 1_000, 1, "c_custkey", 1_000);
    private static final JoinEdge CUSTOMERS_CITIES = edge(1, "c_city", 100, 2, "ct_city", 100);
    private static final List<JoinEdge> CHAIN_EDGES = List.of(ORDERS_CUSTOMERS, CUSTOMERS_CITIES);

    /**
     * Required Test 3: under the FROM-list order, the first join produces 10,000
     * rows; greedy starts from the 10-row customers-cities join and reverses the
     * FROM list, so no intermediate result exceeds the final 100 rows.
     */
    @Test
    void greedyOrderReversesFromListWhenFromListOrderIsWorst() {
        JoinOrder syntax = JoinOrderer.orderBySyntax(CHAIN_TABLES, CHAIN_EDGES);
        JoinOrder greedy = JoinOrderer.orderGreedily(CHAIN_TABLES, CHAIN_EDGES);

        assertEquals(List.of(0, 1, 2), syntax.tableOrder());
        assertEquals(List.of(10_000.0, 100.0), estimates(syntax));
        assertEquals(List.of(2, 1, 0), greedy.tableOrder());
        assertEquals(List.of(10.0, 100.0), estimates(greedy));
        assertEquals(List.of(CUSTOMERS_CITIES, ORDERS_CUSTOMERS),
                greedy.steps().stream().map(JoinStep::edge).toList());
    }

    /**
     * Required Test 3: after joining t0 and t1, a cross product with the 1-row
     * table t3 would be estimated at 100 rows, far less than the 10,000-row join
     * with t2. The planner still joins t2 first, because only t2 is connected.
     */
    @Test
    void greedyOrderNeverPicksCrossProductWhileConnectedTableExists() {
        List<TableEstimate> tables = List.of(new TableEstimate("t0", 100), new TableEstimate("t1", 100),
                new TableEstimate("t2", 1_000), new TableEstimate("t3", 1));
        List<JoinEdge> edges = List.of(
                edge(0, "a0", 100, 1, "a1", 100),
                edge(1, "b1", 10, 2, "b2", 10),
                edge(2, "c2", 1, 3, "c3", 1));

        JoinOrder greedy = JoinOrderer.orderGreedily(tables, edges);

        assertEquals(List.of(0, 1, 2, 3), greedy.tableOrder());
        assertEquals(List.of(100.0, 10_000.0, 10_000.0), estimates(greedy));
        assertEveryStepIsConnected(greedy);
    }

    /**
     * Required Test 3: two predicates with equal estimates tie; the earlier one
     * in WHERE order wins, and equal tables let the earlier FROM table build.
     */
    @Test
    void greedyOrderBreaksTiesByWhereOrderAndFromPosition() {
        List<TableEstimate> tables = List.of(new TableEstimate("a", 10), new TableEstimate("b", 10),
                new TableEstimate("c", 10));
        List<JoinEdge> edges = List.of(edge(2, "c_b", 10, 1, "b_c", 10), edge(0, "a_b", 10, 1, "b_a", 10));

        JoinOrder greedy = JoinOrderer.orderGreedily(tables, edges);

        assertEquals(List.of(1, 2, 0), greedy.tableOrder());
    }

    /**
     * Required Test 4: in the first join, the smaller table builds, whichever
     * side of the predicate and of the FROM list it is on.
     */
    @Test
    void smallerTableBuildsTheFirstJoin() {
        List<TableEstimate> tables = List.of(new TableEstimate("big", 500), new TableEstimate("small", 20));

        JoinOrder greedy = JoinOrderer.orderGreedily(tables, List.of(edge(0, "k", 20, 1, "k2", 20)));

        assertEquals(1, greedy.firstTablePosition());
        JoinStep onlyStep = greedy.steps().getFirst();
        assertEquals(0, onlyStep.newTablePosition());
        assertFalse(onlyStep.newTableBuilds());
        assertEquals(500.0, onlyStep.estimatedRows());
    }

    /**
     * Required Test 4: in a later join, a new table smaller than the
     * intermediate result builds; a larger one probes.
     */
    @Test
    void smallerInputBuildsLaterJoins() {
        List<TableEstimate> tables = List.of(new TableEstimate("a", 100), new TableEstimate("b", 100),
                new TableEstimate("c", 50));
        List<JoinEdge> edges = List.of(edge(0, "ab", 100, 1, "ba", 100), edge(0, "ac", 1, 2, "ca", 1));

        JoinOrder greedy = JoinOrderer.orderGreedily(tables, edges);

        assertEquals(List.of(0, 1, 2), greedy.tableOrder());
        JoinStep cJoin = greedy.steps().getLast();
        assertTrue(cJoin.newTableBuilds(), "c (50 rows) is smaller than a+b (100 rows)");
        assertEquals(5_000.0, cJoin.estimatedRows());

        JoinOrder chain = JoinOrderer.orderGreedily(CHAIN_TABLES, CHAIN_EDGES);
        assertFalse(chain.steps().getLast().newTableBuilds(), "orders (10000 rows) is larger than 10 rows");
    }

    /**
     * Verifies week 10's plan under the syntax order: FROM-list order, the
     * tables joined so far always building, even when the new table is smaller.
     */
    @Test
    void syntaxOrderKeepsFromListOrderAndJoinedTablesBuild() {
        JoinOrder syntax = JoinOrderer.orderBySyntax(CHAIN_TABLES, CHAIN_EDGES);

        assertEquals(0, syntax.firstTablePosition());
        assertTrue(syntax.steps().stream().noneMatch(JoinStep::newTableBuilds));
    }

    /**
     * Verifies that the syntax order keeps week 10's error messages for an
     * unconnected FROM list and for a second connecting predicate.
     */
    @Test
    void syntaxOrderRejectsUnconnectedTablesAndSecondPredicates() {
        List<JoinEdge> reversed = List.of(CUSTOMERS_CITIES);
        IllegalArgumentException unconnected = assertThrows(IllegalArgumentException.class,
                () -> JoinOrderer.orderBySyntax(CHAIN_TABLES, reversed));
        assertEquals("table customers is not connected by a join predicate to the tables before it (orders); "
                + "reorder the FROM list so that each table joins a table listed before it",
                unconnected.getMessage());

        List<JoinEdge> twoPredicates = List.of(ORDERS_CUSTOMERS, edge(0, "o_city", 10, 1, "c_city", 100),
                CUSTOMERS_CITIES);
        IllegalArgumentException second = assertThrows(IllegalArgumentException.class,
                () -> JoinOrderer.orderBySyntax(CHAIN_TABLES, twoPredicates));
        assertEquals("table customers is connected by 2 join predicates (o_custkey = c_custkey and "
                + "o_city = c_city); only one join predicate per table is supported", second.getMessage());
    }

    /**
     * Verifies that the greedy order rejects queries only a cross product could
     * plan, and a second predicate it could not apply.
     */
    @Test
    void greedyOrderRejectsCrossProductsAndSecondPredicates() {
        IllegalArgumentException disconnected = assertThrows(IllegalArgumentException.class,
                () -> JoinOrderer.orderGreedily(CHAIN_TABLES, List.of(CUSTOMERS_CITIES)));
        assertEquals("tables orders are not connected by a join predicate to cities+customers; "
                + "cross products are not supported", disconnected.getMessage());

        IllegalArgumentException noPredicates = assertThrows(IllegalArgumentException.class,
                () -> JoinOrderer.orderGreedily(CHAIN_TABLES, List.of()));
        assertEquals("no join predicate connects the FROM tables (orders+customers+cities); "
                + "cross products are not supported", noPredicates.getMessage());

        List<JoinEdge> cycle = List.of(ORDERS_CUSTOMERS, CUSTOMERS_CITIES, edge(0, "o_city", 100, 2, "ct_city", 100));
        IllegalArgumentException second = assertThrows(IllegalArgumentException.class,
                () -> JoinOrderer.orderGreedily(CHAIN_TABLES, cycle));
        assertEquals("table orders is connected by 2 join predicates (o_custkey = c_custkey and "
                + "o_city = ct_city); only one join predicate per table is supported", second.getMessage());
    }

    /**
     * Verifies that a single table needs no join step in either order.
     */
    @Test
    void singleTableHasNoJoinSteps() {
        List<TableEstimate> single = List.of(new TableEstimate("orders", 4));

        assertEquals(new JoinOrder(0, List.of()), JoinOrderer.orderGreedily(single, List.of()));
        assertEquals(new JoinOrder(0, List.of()), JoinOrderer.orderBySyntax(single, List.of()));
    }

    /**
     * Verifies that invalid input is rejected with IllegalArgumentException.
     */
    @Test
    void invalidInputThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> JoinOrderer.orderGreedily(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> JoinOrderer.orderGreedily(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> JoinOrderer.orderGreedily(CHAIN_TABLES, null));
        assertThrows(IllegalArgumentException.class,
                () -> JoinOrderer.orderGreedily(CHAIN_TABLES, List.of(edge(0, "x", 1, 3, "y", 1))));
        assertThrows(IllegalArgumentException.class, () -> edge(1, "x", 1, 1, "y", 1));
        assertThrows(IllegalArgumentException.class, () -> new TableEstimate("t", -1));
        assertThrows(IllegalArgumentException.class, () -> new TableEstimate("t", Double.NaN));
    }

    /**
     * Builds a join edge from hand-made positions, columns and NDVs.
     *
     * @param leftTable   FROM position of the left column's table
     * @param leftColumn  left column name
     * @param leftNdv     left column NDV
     * @param rightTable  FROM position of the right column's table
     * @param rightColumn right column name
     * @param rightNdv    right column NDV
     * @return the edge
     */
    private static JoinEdge edge(int leftTable, String leftColumn, long leftNdv, int rightTable,
            String rightColumn, long rightNdv) {
        return new JoinEdge(new JoinSide(leftTable, leftColumn, leftNdv),
                new JoinSide(rightTable, rightColumn, rightNdv));
    }

    /**
     * Lists the estimated output of every step.
     *
     * @param order a join order
     * @return estimates in execution order
     */
    private static List<Double> estimates(JoinOrder order) {
        return order.steps().stream().map(JoinStep::estimatedRows).toList();
    }

    /**
     * Asserts that each step's predicate joins its new table to a table joined
     * before it, so no step is a cross product.
     *
     * @param order a join order
     */
    private static void assertEveryStepIsConnected(JoinOrder order) {
        List<Integer> joined = new ArrayList<>(List.of(order.firstTablePosition()));
        for (JoinStep step : order.steps()) {
            int otherTable = step.edge().otherSideOf(step.newTablePosition()).tablePosition();
            assertTrue(joined.contains(otherTable), "step adding " + step.newTablePosition() + " is connected");
            joined.add(step.newTablePosition());
        }
    }
}
