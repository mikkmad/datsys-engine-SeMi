package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.executor.Executor;
import datasys.semi.models.SelectStatement;
import datasys.semi.operators.Operator;
import datasys.semi.planner.Planner;

/**
 * Required test 1 of Exercise 8 (§2, §5): a snapshot read under a concurrent
 * commit, without threads. A {@code SELECT} plan is opened on a pinned version,
 * a whole {@code DELETE} runs from the same thread, and the scan is drained
 * afterwards; it must return the rows of its pinned version.
 */
class SnapshotReadTest {

    // --- State Under Test ---
    private StorageEngine engine;
    private Executor executor;

    /**
     * Creates {@code trips} and loads the eight golden rows as version 1.
     *
     * @param directory JUnit-managed temporary directory
     * @throws IOException if the CSV resource cannot be copied
     */
    @BeforeEach
    void setUp(@TempDir Path directory) throws IOException {
        Path csvFile = UtilsTest.copyResource(directory, "trips.csv");
        engine = new StorageEngine(directory, 2);
        executor = new Executor(engine);
        executor.execute("""
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                """.formatted(csvFile.toString()));
    }

    /**
     * Verifies that a plan opened on version 1 and drained after a DELETE commits
     * version 2 still returns all eight rows of version 1, including the deleted
     * one, while a new query sees version 2.
     */
    @Test
    void planOpenedBeforeDeleteReturnsPinnedVersionRows() {
        Catalog pinnedV1 = engine.pinSnapshot("trips");
        try {
            Operator plan = new Planner(engine).plan(
                    new SelectStatement("trips", Optional.empty(), Optional.empty()), pinnedV1);
            plan.open();
            List<Object[]> rows = new ArrayList<>();
            try {
                rows.add(plan.next());
                executor.execute("DELETE FROM trips WHERE distance = 95;");
                assertEquals(2L, engine.snapshot("trips").version());
                drainInto(plan, rows);
            } finally {
                plan.close();
            }

            assertEquals(8, rows.size());
            assertNotNull(rows.getFirst());
            assertTrue(rows.stream().anyMatch(row -> Arrays.equals(row, new Object[] { "Odense", 95L, 120.75 })));
        } finally {
            engine.releaseSnapshot(pinnedV1);
        }

        List<Object[]> afterDelete = executor.executeQuery("SELECT * FROM trips;");
        assertEquals(7, afterDelete.size());
        assertTrue(afterDelete.stream().noneMatch(row -> row[1].equals(95L)));
        assertArrayEquals(new Object[] { "Copenhagen", 12L, 23.5 }, afterDelete.getFirst());
    }

    /**
     * Pulls every remaining row from an open operator into {@code rows}.
     *
     * @param plan open operator to drain
     * @param rows collector receiving the rows in output order
     */
    private static void drainInto(Operator plan, List<Object[]> rows) {
        Object[] row;
        while ((row = plan.next()) != null) {
            rows.add(row);
        }
    }
}
