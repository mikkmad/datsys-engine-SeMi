package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import datasys.semi.engine.ConflictException;
import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.engine.StorageEngine.Partition;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;

/**
 * Tests first-committer-wins validation at commit (Exercise 8 §1) without
 * threads: a snapshot taken before another commit is a stale base, which is
 * exactly the state a parked concurrent writer reaches at its commit.
 */
class ConflictTest {

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
     * Verifies that a COPY from a base the table has moved past throws
     * ConflictException carrying the table, base and current version.
     */
    @Test
    void staleCopyThrowsConflictExceptionWithBaseAndCurrentVersion() {
        Catalog staleBase = engine.snapshot("trips");
        copyOnCurrentVersion();

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> engine.copyFile(staleBase, csvFile.toString()));

        assertEquals("conflict table=trips base=0 current=1", conflict.getMessage());
        assertEquals("trips", conflict.tableName());
        assertEquals(0L, conflict.baseVersion());
        assertEquals(1L, conflict.currentVersion());
    }

    /**
     * Verifies that a conflicting COPY leaves the table at the winner's version
     * and rows, and deletes the partition files and temporary catalog file it
     * wrote.
     *
     * @throws IOException if the storage directories cannot be listed
     */
    @Test
    void conflictingCopyLeavesNoVisibleChangeAndNoOrphans() throws IOException {
        Catalog staleBase = engine.snapshot("trips");
        copyOnCurrentVersion();
        Set<String> filesAfterWinner = listPartitionFiles();

        assertThrows(ConflictException.class, () -> engine.copyFile(staleBase, csvFile.toString()));

        assertWinnerStateUnchanged(1L, ROWS_PER_COPY, filesAfterWinner);
    }

    /**
     * Verifies that a conflicting DELETE leaves the table at the winner's version
     * and rows, and deletes the rewritten partition files it wrote.
     *
     * @throws IOException if the storage directories cannot be listed
     */
    @Test
    void conflictingDeleteLeavesNoVisibleChangeAndNoOrphans() throws IOException {
        copyOnCurrentVersion();
        Catalog staleBase = engine.snapshot("trips");
        copyOnCurrentVersion();
        Set<String> filesAfterWinner = listPartitionFiles();
        List<Integer> everyPartition = List.of(0, 1, 2, 3);

        assertThrows(ConflictException.class,
                () -> engine.deleteRows(staleBase, everyPartition, row -> row[1].equals(95L)));

        assertWinnerStateUnchanged(2L, 2 * ROWS_PER_COPY, filesAfterWinner);
    }

    /**
     * Verifies that an aborted COPY changed nothing, so rerunning it on a fresh
     * snapshot commits the next version with both loads' rows.
     */
    @Test
    void abortedCopyCanBeRetriedOnFreshSnapshot() {
        Catalog staleBase = engine.snapshot("trips");
        copyOnCurrentVersion();
        assertThrows(ConflictException.class, () -> engine.copyFile(staleBase, csvFile.toString()));

        copyOnCurrentVersion();

        assertEquals(2L, engine.snapshot("trips").version());
        assertEquals(2 * ROWS_PER_COPY, countRows(engine.snapshot("trips")));
    }

    /**
     * Verifies that the storage engine throws the conflict without logging it:
     * the ERROR line is written once, by the statement boundary that handles the
     * failure (AGENTS §5.4), which {@code TwoWriterConflictTest} checks.
     *
     * @throws IOException if the log file cannot be read
     */
    @Test
    void conflictIsThrownWithoutErrorLineAtTheThrowSite() throws IOException {
        String session = UUID.randomUUID().toString();
        Catalog staleBase = engine.snapshot("trips");
        copyOnCurrentVersion();

        MDC.put("sessionId", session);
        try {
            assertThrows(ConflictException.class, () -> engine.copyFile(staleBase, csvFile.toString()));
        } finally {
            MDC.remove("sessionId");
        }

        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        assertTrue(logLines.stream()
                .noneMatch(line -> line.contains("," + session + ",") && line.contains(",ERROR,")),
                "the storage engine must not log the conflict it throws");
    }

    /**
     * Verifies that creating an existing table is still an argument error, not a
     * conflict.
     */
    @Test
    void createExistingTableStillThrowsIllegalArgumentException() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> engine.createTable("trips", SCHEMA));

        assertEquals("table already exists: trips", failure.getMessage());
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
     * Asserts that the table still shows the winner's commit and that the losing
     * statement left no partition or temporary files behind.
     *
     * @param expectedVersion  version committed by the winner
     * @param expectedRows     total row count after the winner's commit
     * @param filesAfterWinner partition files on disk right after the winner's
     *                         commit
     * @throws IOException if the storage directories cannot be listed
     */
    private void assertWinnerStateUnchanged(long expectedVersion, int expectedRows, Set<String> filesAfterWinner)
            throws IOException {
        Catalog current = engine.snapshot("trips");
        assertEquals(expectedVersion, current.version());
        assertEquals(expectedRows, countRows(current));
        assertEquals(filesAfterWinner, listPartitionFiles());
        assertTrue(listTemporaryFiles().isEmpty(), "aborted commit left temporary files behind");
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
     * Lists the partition file names of the {@code trips} table.
     *
     * @return file names in {@code data/trips/} ending in {@code .dat}
     * @throws IOException if the directory cannot be listed
     */
    private Set<String> listPartitionFiles() throws IOException {
        try (Stream<Path> paths = Files.list(directory.resolve("data").resolve("trips"))) {
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
