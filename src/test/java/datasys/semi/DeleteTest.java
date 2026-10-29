package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import datasys.semi.engine.ConflictException;
import datasys.semi.engine.DeleteResult;
import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.engine.StorageEngine.Partition;
import datasys.semi.models.DeleteStatement;
import datasys.semi.models.Predicate;
import datasys.semi.models.SelectStatement;
import datasys.semi.operators.FilterOperator;
import datasys.semi.operators.ScanOperator;
import datasys.semi.planner.DeletePlan;
import datasys.semi.planner.Planner;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;
import datasys.semi.schema.Comparison;

/**
 * Tests copy-on-write {@code DELETE}, reader pinning and partition garbage
 * collection on sorted golden data with {@code maxRowsPerPartition = 2}, which
 * yields the partitions [12, 31], [88, 95], [140, 187] and [210, 299] on the
 * {@code distance} column at version 1.
 */
class DeleteTest {

    // --- Test Configuration ---
    private static final int MAX_ROWS_PER_PARTITION = 2;
    private static final int MAX_RETAINED_CATALOG_VERSIONS = 10;
    private static final List<ColumnSpec> SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // --- State Under Test ---
    private Path directory;
    private StorageEngine engine;
    private Planner planner;

    @BeforeEach
    void setUp(@TempDir Path tempDirectory) throws IOException {
        directory = tempDirectory;
        Path csvPath = UtilsTest.copyResource(directory, "trips_sorted.csv");
        engine = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);
        engine.createTable("trips", SCHEMA);
        engine.copyFile(engine.snapshot("trips"), csvPath.toString());
        planner = new Planner(engine);
    }

    /**
     * Required Test 4: a selective DELETE rewrites exactly the partition that can
     * match, and the carried partitions keep their catalog entries and positions.
     * The original partition file is left untouched.
     */
    @Test
    void selectiveDeleteRewritesExactlyPartitionsThatCanMatch() throws IOException {
        Catalog before = engine.snapshot("trips");
        Path originalFile = directory.resolve(before.partitions().get(1).path());
        byte[] originalBytes = Files.readAllBytes(originalFile);

        DeletePlan plan = planner.planDelete(deleteWhere(Comparison.EQUALS, 95L), before);
        assertEquals(List.of(1), plan.rewriteSet());

        DeleteResult result = engine.deleteRows(before, plan.rewriteSet(), plan.rowMatcher());
        assertEquals(new DeleteResult(1, 1, 3, 2), result);

        List<Partition> after = engine.snapshot("trips").partitions();
        assertEquals(4, after.size());
        assertEquals(before.partitions().get(0), after.get(0));
        assertEquals(before.partitions().get(2), after.get(2));
        assertEquals(before.partitions().get(3), after.get(3));
        assertNotEquals(before.partitions().get(1).path(), after.get(1).path());
        assertEquals(1, after.get(1).rowCount());
        assertEquals("88", after.get(1).statistics().get("distance").max());
        assertArrayEquals(originalBytes, Files.readAllBytes(originalFile));
    }

    /**
     * Verifies that DELETE and SELECT decide from the same pruning helper: the
     * rewrite set equals the partitions a SELECT with the same predicate reads.
     */
    @Test
    void rewriteSetMatchesSelectPruningForSamePredicate() {
        Catalog snapshot = engine.snapshot("trips");
        Predicate predicate = new Predicate("distance", Comparison.GREATER_THAN, 100L);

        DeletePlan deletePlan = planner.planDelete(new DeleteStatement("trips", Optional.of(predicate)), snapshot);
        FilterOperator selectPlan = (FilterOperator) planner.plan(
                new SelectStatement("trips", Optional.empty(), Optional.of(predicate)), snapshot);

        assertEquals(List.of(2, 3), deletePlan.rewriteSet());
        assertEquals(((ScanOperator) selectPlan.child()).partitionNumbers(), deletePlan.rewriteSet());
    }

    /**
     * Verifies that a candidate partition without any matching row is carried over
     * rather than copied, and that the DELETE still commits a new version.
     */
    @Test
    void candidateWithoutMatchIsCarriedOverAndStillCommitsNewVersion() throws IOException {
        Catalog before = engine.snapshot("trips");
        long filesBefore = countPartitionFiles();

        DeleteResult result = runDelete(deleteWhere(Comparison.EQUALS, 90L));

        assertEquals(new DeleteResult(0, 0, 4, 2), result);
        assertEquals(before.partitions(), engine.snapshot("trips").partitions());
        assertEquals(filesBefore, countPartitionFiles());
    }

    /**
     * Verifies that a partition whose rows all match is dropped from the next
     * version instead of being rewritten as an empty file.
     */
    @Test
    void fullyMatchedPartitionIsDroppedFromCatalog() {
        Catalog before = engine.snapshot("trips");

        DeleteResult result = runDelete(deleteWhere(Comparison.GREATER_THAN, 200L));

        assertEquals(new DeleteResult(2, 1, 3, 2), result);
        assertEquals(before.partitions().subList(0, 3), engine.snapshot("trips").partitions());
    }

    /**
     * Verifies that a DELETE without WHERE removes every row and partition.
     */
    @Test
    void deleteWithoutWhereRemovesEveryRow() {
        DeleteResult result = runDelete(new DeleteStatement("trips", Optional.empty()));

        assertEquals(new DeleteResult(8, 4, 0, 2), result);
        assertTrue(engine.snapshot("trips").partitions().isEmpty());
    }

    /**
     * Required Test 5: a fresh engine on the same data directory sees the
     * post-DELETE version and its rows.
     */
    @Test
    void freshEngineSeesPostDeleteVersion() {
        runDelete(deleteWhere(Comparison.EQUALS, 95L));
        Catalog committed = engine.snapshot("trips");

        StorageEngine restarted = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);
        Catalog reloaded = restarted.snapshot("trips");

        assertEquals(2L, reloaded.version());
        assertEquals(committed.partitions(), reloaded.partitions());
        List<Object[]> rows = reloaded.partitions().stream()
                .flatMap(partition -> restarted.readPartition(reloaded, partition).stream())
                .toList();
        assertEquals(7, rows.size());
        assertTrue(rows.stream().noneMatch(row -> row[1].equals(95L)));
    }

    /**
     * Verifies that a DELETE against a stale snapshot fails with
     * ConflictException, keeps the current version, and discards the partition
     * files it wrote.
     */
    @Test
    void staleSnapshotDeleteFailsAndLeavesNoNewPartitionFiles() throws IOException {
        Catalog stale = engine.snapshot("trips");
        runDelete(deleteWhere(Comparison.EQUALS, 12L));
        long filesBefore = countPartitionFiles();

        DeletePlan plan = planner.planDelete(deleteWhere(Comparison.EQUALS, 95L), stale);
        assertThrows(ConflictException.class,
                () -> engine.deleteRows(stale, plan.rewriteSet(), plan.rowMatcher()));

        assertEquals(2L, engine.snapshot("trips").version());
        assertEquals(filesBefore, countPartitionFiles());
    }

    /**
     * Verifies that a partition replaced by a DELETE stays on disk while an older
     * retained version lists it and is deleted once that version leaves the
     * retention window.
     */
    @Test
    void replacedPartitionIsDeletedOnceItLeavesRetentionWindow() {
        Path replacedFile = directory.resolve(engine.snapshot("trips").partitions().get(1).path());
        runDelete(deleteWhere(Comparison.EQUALS, 95L));

        commitEmptyDeletesUntilVersion(MAX_RETAINED_CATALOG_VERSIONS);
        assertTrue(Files.exists(replacedFile), "version 1 is still retained and lists the file");

        commitEmptyDeletesUntilVersion(MAX_RETAINED_CATALOG_VERSIONS + 1);
        assertFalse(Files.exists(replacedFile), "no retained or pinned version lists the file");
        assertTrue(engine.snapshot("trips").partitions().stream()
                .allMatch(partition -> Files.exists(directory.resolve(partition.path()))));
    }

    /**
     * Verifies that a pinned version keeps its partition files after leaving the
     * retention window until its last reader releases it.
     */
    @Test
    void pinnedVersionKeepsItsPartitionsUntilLastReaderReleases() {
        Catalog firstReader = engine.pinSnapshot("trips");
        Catalog secondReader = engine.pinSnapshot("trips");
        assertEquals(2, engine.pinnedReaderCount("trips", 1L));
        Partition replaced = firstReader.partitions().get(1);
        Path replacedFile = directory.resolve(replaced.path());

        runDelete(deleteWhere(Comparison.EQUALS, 95L));
        commitEmptyDeletesUntilVersion(MAX_RETAINED_CATALOG_VERSIONS + 1);
        assertTrue(Files.exists(replacedFile), "pinned version 1 still lists the file");
        assertEquals(2, engine.readPartition(firstReader, replaced).size());

        engine.releaseSnapshot(firstReader);
        assertTrue(Files.exists(replacedFile), "one reader still pins version 1");
        assertEquals(1, engine.pinnedReaderCount("trips", 1L));

        engine.releaseSnapshot(secondReader);
        assertFalse(Files.exists(replacedFile), "last reader released version 1");
        assertEquals(0, engine.pinnedReaderCount("trips", 1L));
    }

    /**
     * Verifies that releasing a snapshot that is not pinned throws
     * IllegalStateException.
     */
    @Test
    void releasingUnpinnedSnapshotThrowsIllegalStateException() {
        Catalog unpinned = engine.snapshot("trips");

        assertThrows(IllegalStateException.class, () -> engine.releaseSnapshot(unpinned));
    }

    /**
     * Verifies that a fresh engine deletes partition files no retained catalog
     * lists and temporary files left by interrupted writes, while keeping every
     * referenced partition.
     */
    @Test
    void startupSweepRemovesOrphanedAndTemporaryFiles() throws IOException {
        Path tableDirectory = directory.resolve("data").resolve("trips");
        Path orphan = Files.writeString(tableDirectory.resolve("partition-" + UUID.randomUUID() + ".dat"), "x");
        Path partitionTemp = Files.writeString(tableDirectory.resolve("partition-a.dat.tmp-" + UUID.randomUUID()), "x");
        Path catalogTemp = Files.writeString(
                directory.resolve("catalogs").resolve("trips-v2.json.tmp-" + UUID.randomUUID()), "x");

        StorageEngine restarted = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);

        assertFalse(Files.exists(orphan));
        assertFalse(Files.exists(partitionTemp));
        assertFalse(Files.exists(catalogTemp));
        Catalog reloaded = restarted.snapshot("trips");
        assertEquals(1L, reloaded.version());
        assertTrue(reloaded.partitions().stream()
                .allMatch(partition -> Files.exists(directory.resolve(partition.path()))));
    }

    /**
     * Builds a DELETE statement on {@code distance} with the given comparison.
     *
     * @param comparison comparison operator
     * @param distance   distance constant
     * @return delete statement on the trips table
     */
    private static DeleteStatement deleteWhere(Comparison comparison, long distance) {
        return new DeleteStatement("trips", Optional.of(new Predicate("distance", comparison, distance)));
    }

    /**
     * Plans and executes a DELETE against the current version of the trips table.
     *
     * @param statement delete statement to run
     * @return committed delete summary
     */
    private DeleteResult runDelete(DeleteStatement statement) {
        Catalog snapshot = engine.snapshot("trips");
        DeletePlan plan = planner.planDelete(statement, snapshot);
        return engine.deleteRows(snapshot, plan.rewriteSet(), plan.rowMatcher());
    }

    /**
     * Commits DELETEs that match nothing until the table reaches a version.
     *
     * @param targetVersion version to reach
     */
    private void commitEmptyDeletesUntilVersion(long targetVersion) {
        while (engine.snapshot("trips").version() < targetVersion) {
            runDelete(deleteWhere(Comparison.EQUALS, 1000L));
        }
    }

    /**
     * Counts the committed partition files of the trips table.
     *
     * @return number of {@code .dat} files in {@code data/trips/}
     * @throws IOException if the directory cannot be listed
     */
    private long countPartitionFiles() throws IOException {
        try (Stream<Path> files = Files.list(directory.resolve("data").resolve("trips"))) {
            return files.filter(path -> path.toString().endsWith(".dat")).count();
        }
    }
}
