package datasys.semi.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import datasys.semi.UtilsTest;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.engine.StorageEngine.Partition;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;

/**
 * Tests the {@code beforeCommit} test seam of {@link StorageEngine} (Exercise 8
 * §2). Lives in the engine package to reach the package-private field. Every
 * test is single-threaded: a second statement injected into the hook runs at
 * exactly the moment between a writer's snapshot and its commit validation.
 */
class CommitHookTest {

    // --- Test Configuration ---
    private static final int MAX_ROWS_PER_PARTITION = 2;
    private static final int ROWS_PER_COPY = 8;
    private static final List<ColumnSpec> SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // --- State Under Test ---
    private Path directory;
    private Path csvFile;
    private StorageEngine engine;

    /**
     * Creates an empty {@code trips} table at version 0 in a fresh directory.
     *
     * @param tempDirectory JUnit-managed temporary directory
     * @throws IOException if the CSV resource cannot be copied
     */
    @BeforeEach
    void setUp(@TempDir Path tempDirectory) throws IOException {
        directory = tempDirectory;
        csvFile = UtilsTest.copyResource(directory, "trips.csv");
        engine = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);
        engine.createTable("trips", SCHEMA);
    }

    /**
     * Verifies that the hook runs exactly once per commit.
     */
    @Test
    void hookRunsBeforeEveryCommit() {
        AtomicInteger hookRuns = new AtomicInteger();
        engine.beforeCommit = hookRuns::incrementAndGet;

        copyOnCurrentVersion();

        assertEquals(1, hookRuns.get());
        assertEquals(1L, engine.snapshot("trips").version());
    }

    /**
     * Verifies that a commit injected into the hook lands between the outer
     * writer's snapshot and its validation, so the outer commit conflicts and
     * leaves only the injected commit visible. The injected commit succeeding
     * proves the hook runs outside the commit critical section.
     *
     * @throws IOException if the storage directories cannot be listed
     */
    @Test
    void commitInjectedIntoHookMakesOuterCommitConflict() throws IOException {
        engine.beforeCommit = () -> {
            engine.beforeCommit = () -> {
            };
            copyOnCurrentVersion();
        };
        Catalog outerBase = engine.snapshot("trips");

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> engine.copyFile(outerBase, csvFile.toString()));

        assertEquals("conflict table=trips base=0 current=1", conflict.getMessage());
        Catalog current = engine.snapshot("trips");
        assertEquals(1L, current.version());
        assertEquals(ROWS_PER_COPY, countRows(current));
        assertEquals(referencedPartitionFiles(current), listPartitionFiles());
        assertTrue(listTemporaryFiles().isEmpty(), "aborted commit left temporary files behind");
    }

    /**
     * Verifies that a throwing hook aborts the commit like any other failure: the
     * version is unchanged and the written partitions and temporary catalog file
     * are deleted.
     *
     * @throws IOException if the storage directories cannot be listed
     */
    @Test
    void throwingHookAbortsCommitWithoutVisibleChange() throws IOException {
        IllegalStateException hookFailure = new IllegalStateException("hook failed");
        engine.beforeCommit = () -> {
            throw hookFailure;
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class, this::copyOnCurrentVersion);

        assertSame(hookFailure, failure);
        assertEquals(0L, engine.snapshot("trips").version());
        assertTrue(engine.snapshot("trips").partitions().isEmpty());
        assertTrue(listPartitionFiles().isEmpty());
        assertTrue(listTemporaryFiles().isEmpty(), "aborted commit left temporary files behind");
    }

    /**
     * Verifies that an engine whose hook was never assigned commits normally.
     */
    @Test
    void defaultHookIsNoOp() {
        copyOnCurrentVersion();
        copyOnCurrentVersion();

        assertEquals(2L, engine.snapshot("trips").version());
        assertEquals(2 * ROWS_PER_COPY, countRows(engine.snapshot("trips")));
    }

    // --- Helpers ---

    /**
     * Loads the CSV file on the table's current version, committing the next
     * version.
     */
    private void copyOnCurrentVersion() {
        engine.copyFile(engine.snapshot("trips"), csvFile.toString());
    }

    /**
     * Sums the row counts of a catalog's partitions.
     *
     * @param catalog catalog snapshot
     * @return total number of rows
     */
    private static int countRows(Catalog catalog) {
        return catalog.partitions().stream().mapToInt(Partition::rowCount).sum();
    }

    /**
     * Returns the file names of the partitions a catalog lists.
     *
     * @param catalog catalog snapshot
     * @return partition file names
     */
    private static Set<String> referencedPartitionFiles(Catalog catalog) {
        return catalog.partitions().stream()
                .map(partition -> Path.of(partition.path()).getFileName().toString())
                .collect(Collectors.toSet());
    }

    /**
     * Lists the partition file names of the {@code trips} table.
     *
     * @return file names in {@code data/trips/} ending in {@code .dat}; empty if
     *         the directory does not exist
     * @throws IOException if the directory cannot be listed
     */
    private Set<String> listPartitionFiles() throws IOException {
        Path tableDirectory = directory.resolve("data").resolve("trips");
        if (!Files.isDirectory(tableDirectory)) {
            return Set.of();
        }
        try (Stream<Path> paths = Files.list(tableDirectory)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".dat"))
                    .collect(Collectors.toSet());
        }
    }

    /**
     * Lists leftover temporary files anywhere under the storage directories.
     *
     * @return paths whose file name contains {@code .tmp-}
     * @throws IOException if the directories cannot be walked
     */
    private List<Path> listTemporaryFiles() throws IOException {
        try (Stream<Path> paths = Stream.concat(Files.walk(directory.resolve("catalogs")),
                Files.walk(directory.resolve("data")))) {
            return paths.filter(path -> path.getFileName().toString().contains(".tmp-")).toList();
        }
    }
}
