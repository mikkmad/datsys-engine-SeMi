package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import datasys.semi.operators.HashJoinOperator;
import datasys.semi.operators.JoinEstimate;
import datasys.semi.operators.Operator;

/**
 * Exercise 10 Required Test 4: HashJoinOperator over TestListOperator stubs.
 * Covers the cursor-revealing cases (duplicates on both sides multiply, empty
 * build side, empty probe side), dropped unmatched rows, the output row layout,
 * the row counters and the close() summary line.
 *
 * <p>
 * Exercise 11 §5: the close() line carries the planner's estimate next to the
 * actual counts.
 */
class HashJoinOperatorTest {

    // --- Fixtures ---
    private static final JoinEstimate ESTIMATE = new JoinEstimate(1, List.of("build", "probe"), 2.6);

    /**
     * Verifies that 2 build rows and 3 probe rows with the same key emit 6
     * rows: in probe order, and in build order within one probe row.
     */
    @Test
    void duplicatesOnBothSidesMultiply() {
        List<Object[]> buildRows = List.of(
                new Object[] { "k", "b1" },
                new Object[] { "k", "b2" });
        List<Object[]> probeRows = List.of(
                new Object[] { "p1", "k" },
                new Object[] { "p2", "k" },
                new Object[] { "p3", "k" });

        List<Object[]> joined = drain(join(buildRows, probeRows, 0, 1));

        assertRows(List.of(
                new Object[] { "k", "b1", "p1", "k" },
                new Object[] { "k", "b2", "p1", "k" },
                new Object[] { "k", "b1", "p2", "k" },
                new Object[] { "k", "b2", "p2", "k" },
                new Object[] { "k", "b1", "p3", "k" },
                new Object[] { "k", "b2", "p3", "k" }), joined);
    }

    /**
     * Verifies that build rows and probe rows without a partner are dropped,
     * while matched rows are joined.
     */
    @Test
    void unmatchedRowsOnBothSidesAreDropped() {
        List<Object[]> buildRows = List.of(
                new Object[] { 1L, "one" },
                new Object[] { 2L, "two" },
                new Object[] { 9L, "build-only" });
        List<Object[]> probeRows = List.of(
                new Object[] { 2L, 20.0 },
                new Object[] { 7L, 70.0 },
                new Object[] { 1L, 10.0 });

        List<Object[]> joined = drain(join(buildRows, probeRows, 0, 0));

        assertRows(List.of(
                new Object[] { 2L, "two", 2L, 20.0 },
                new Object[] { 1L, "one", 1L, 10.0 }), joined);
    }

    /**
     * Verifies that an empty build side yields no rows, and the probe side is
     * still pulled to exhaustion.
     */
    @Test
    void emptyBuildSideEmitsNothing() {
        List<Object[]> probeRows = List.of(new Object[] { 1L }, new Object[] { 2L });
        HashJoinOperator join = join(List.of(), probeRows, 0, 0);

        assertEquals(List.of(), drain(join));
        assertEquals(0, join.rowsBuild());
        assertEquals(2, join.rowsProbe());
        assertEquals(0, join.rowsOut());
    }

    /**
     * Verifies that an empty probe side yields no rows although the build side
     * is fully drained.
     */
    @Test
    void emptyProbeSideEmitsNothing() {
        List<Object[]> buildRows = List.of(new Object[] { 1L }, new Object[] { 1L });
        HashJoinOperator join = join(buildRows, List.of(), 0, 0);

        assertEquals(List.of(), drain(join));
        assertEquals(2, join.rowsBuild());
        assertEquals(0, join.rowsProbe());
        assertEquals(0, join.rowsOut());
    }

    /**
     * Verifies the testbed shape: orders joined with customers on the customer
     * name gives one row per order, laid out as build columns then probe
     * columns, with keys at non-zero positions.
     */
    @Test
    void testbedOrdersJoinCustomersGivesOneRowPerOrder() {
        List<Object[]> orders = List.of(
                new Object[] { 100L, "Anna", "Bike", 1L },
                new Object[] { 101L, "Bob", "Helmet", 2L },
                new Object[] { 102L, "Carol", "Bike", 1L },
                new Object[] { 103L, "Anna", "Helmet", 1L });
        List<Object[]> customers = List.of(
                new Object[] { "Anna", "Copenhagen" },
                new Object[] { "Bob", "Aarhus" },
                new Object[] { "Carol", "Copenhagen" });

        HashJoinOperator join = join(orders, customers, 1, 0);
        List<Object[]> joined = drain(join);

        assertRows(List.of(
                new Object[] { 100L, "Anna", "Bike", 1L, "Anna", "Copenhagen" },
                new Object[] { 103L, "Anna", "Helmet", 1L, "Anna", "Copenhagen" },
                new Object[] { 101L, "Bob", "Helmet", 2L, "Bob", "Aarhus" },
                new Object[] { 102L, "Carol", "Bike", 1L, "Carol", "Copenhagen" }), joined);
        assertEquals(4, join.rowsBuild());
        assertEquals(3, join.rowsProbe());
        assertEquals(4, join.rowsOut());
    }

    /**
     * Verifies that a null key never matches, not even another null key.
     */
    @Test
    void nullKeysNeverMatch() {
        List<Object[]> buildRows = List.of(new Object[] { null, "b-null" }, new Object[] { "k", "b-k" });
        List<Object[]> probeRows = List.of(new Object[] { null }, new Object[] { "k" });

        assertRows(List.<Object[]>of(new Object[] { "k", "b-k", "k" }), drain(join(buildRows, probeRows, 0, 0)));
    }

    /**
     * Verifies that output rows are fresh arrays and the input rows are not
     * modified.
     */
    @Test
    void outputRowsDoNotAliasOrMutateInputRows() {
        Object[] buildRow = { 1L, "b" };
        Object[] probeRow = { 1L, "p" };
        List<Object[]> joined = drain(join(List.<Object[]>of(buildRow), List.<Object[]>of(probeRow), 0, 0));

        joined.getFirst()[0] = 99L;
        assertArrayEquals(new Object[] { 1L, "b" }, buildRow);
        assertArrayEquals(new Object[] { 1L, "p" }, probeRow);
    }

    /**
     * Verifies that next() keeps returning null after the probe side is
     * exhausted.
     */
    @Test
    void nextStaysNullAfterExhaustion() {
        HashJoinOperator join = join(List.<Object[]>of(new Object[] { 1L }), List.<Object[]>of(new Object[] { 1L }),
                0, 0);
        join.open();

        assertArrayEquals(new Object[] { 1L, 1L }, join.next());
        assertNull(join.next());
        assertNull(join.next());
        join.close();
    }

    /**
     * Verifies that close() logs one summary line with the join number, its
     * tables, the rounded estimate (2.6 to 3) and the build, probe and output
     * row counts.
     *
     * @throws IOException if the log file cannot be read
     */
    @Test
    void closeLogsEstimateNextToRowsBuildRowsProbeRowsOut() throws IOException {
        List<Object[]> buildRows = List.of(
                new Object[] { "a" }, new Object[] { "a" }, new Object[] { "b" }, new Object[] { "c" });
        List<Object[]> probeRows = List.of(new Object[] { "a" }, new Object[] { "b" }, new Object[] { "z" });

        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        try {
            drain(join(buildRows, probeRows, 0, 0));
        } finally {
            MDC.remove("sessionId");
        }

        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        assertTrue(logLines.stream()
                .filter(line -> line.contains("," + session + ","))
                .anyMatch(line -> line.contains(",DEBUG,HashJoinOperator,")
                        && line.endsWith("operator=HashJoin join=1 tables=build+probe estRows=3 buildKey=0 probeKey=0"
                                + " rowsBuild=4 rowsProbe=3 rowsOut=3")),
                "expected HashJoin summary line for session " + session);
    }

    /**
     * Verifies the lifecycle guards: next() and close() before open(), and a
     * second open().
     */
    @Test
    void lifecycleViolationsThrow() {
        HashJoinOperator join = join(List.of(), List.of(), 0, 0);
        assertThrows(IllegalStateException.class, join::next);
        assertThrows(IllegalStateException.class, join::close);

        join.open();
        assertThrows(IllegalStateException.class, join::open);
        join.close();
    }

    /**
     * Verifies argument validation and the error for a key index outside a row.
     */
    @Test
    void invalidArgumentsAndOutOfRangeKeysThrow() {
        Operator rows = new TestListOperator(List.of());
        assertThrows(IllegalArgumentException.class, () -> new HashJoinOperator(null, rows, 0, 0, ESTIMATE));
        assertThrows(IllegalArgumentException.class, () -> new HashJoinOperator(rows, null, 0, 0, ESTIMATE));
        assertThrows(IllegalArgumentException.class, () -> new HashJoinOperator(rows, rows, -1, 0, ESTIMATE));
        assertThrows(IllegalArgumentException.class, () -> new HashJoinOperator(rows, rows, 0, -1, ESTIMATE));
        assertThrows(IllegalArgumentException.class, () -> new HashJoinOperator(rows, rows, 0, 0, null));

        HashJoinOperator wideBuildKey = join(List.<Object[]>of(new Object[] { 1L }), List.of(), 3, 0);
        IllegalStateException buildError = assertThrows(IllegalStateException.class, wideBuildKey::open);
        assertEquals("build row has 1 columns but the join key index is 3", buildError.getMessage());

        HashJoinOperator wideProbeKey = join(List.of(), List.<Object[]>of(new Object[] { 1L }), 0, 2);
        wideProbeKey.open();
        assertThrows(IllegalStateException.class, wideProbeKey::next);
    }

    /**
     * Verifies the accessors expose the wiring the planner tests inspect.
     */
    @Test
    void accessorsExposeWiring() {
        Operator build = new TestListOperator(List.of());
        Operator probe = new TestListOperator(List.of());
        HashJoinOperator join = new HashJoinOperator(build, probe, 2, 5, ESTIMATE);

        assertEquals(build, join.build());
        assertEquals(probe, join.probe());
        assertEquals(2, join.buildKey());
        assertEquals(5, join.probeKey());
        assertEquals(ESTIMATE, join.estimate());
    }

    /**
     * Exercise 11 §5: the estimate keeps its exact value, rounds only for the
     * log, joins table names with {@code +} (the log is CSV), and rejects values
     * no plan can produce.
     */
    @Test
    void joinEstimateRoundsForTheLogAndRejectsInvalidValues() {
        JoinEstimate estimate = new JoinEstimate(2, List.of("products", "orders", "customers"), 1.5);

        assertEquals(1.5, estimate.estimatedRows());
        assertEquals(2, estimate.roundedRows());
        assertEquals("products+orders+customers", estimate.describeTables());
        assertThrows(IllegalArgumentException.class, () -> new JoinEstimate(0, List.of("a", "b"), 1));
        assertThrows(IllegalArgumentException.class, () -> new JoinEstimate(1, List.of("a"), 1));
        assertThrows(IllegalArgumentException.class, () -> new JoinEstimate(1, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new JoinEstimate(1, List.of("a", " "), 1));
        assertThrows(IllegalArgumentException.class, () -> new JoinEstimate(1, List.of("a", "b"), -1));
        assertThrows(IllegalArgumentException.class, () -> new JoinEstimate(1, List.of("a", "b"), Double.NaN));
    }

    /**
     * Builds a hash join over two stub children.
     *
     * @param buildRows rows of the build child
     * @param probeRows rows of the probe child
     * @param buildKey  key index in build rows
     * @param probeKey  key index in probe rows
     * @return the join operator with the fixture estimate, not yet opened
     */
    private static HashJoinOperator join(List<Object[]> buildRows, List<Object[]> probeRows, int buildKey,
            int probeKey) {
        return new HashJoinOperator(new TestListOperator(buildRows), new TestListOperator(probeRows), buildKey,
                probeKey, ESTIMATE);
    }

    /**
     * Opens an operator, pulls every row, and closes it.
     *
     * @param operator operator to drain
     * @return emitted rows in order
     */
    private static List<Object[]> drain(Operator operator) {
        List<Object[]> rows = new ArrayList<>();
        operator.open();
        for (Object[] row = operator.next(); row != null; row = operator.next()) {
            rows.add(row);
        }
        operator.close();
        return rows;
    }

    /**
     * Asserts two row lists are equal element by element, in order.
     *
     * @param expected expected rows
     * @param actual   actual rows
     */
    private static void assertRows(List<Object[]> expected, List<Object[]> actual) {
        assertEquals(expected.size(), actual.size(), () -> "row count; actual=" + actual.stream()
                .map(Arrays::toString).toList());
        for (int index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index), actual.get(index), "row " + index);
        }
    }
}
