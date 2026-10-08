package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import datasys.semi.engine.StorageEngine;
import datasys.semi.models.Predicate;
import datasys.semi.models.ScanStats;
import datasys.semi.models.SelectStatement;
import datasys.semi.operators.FilterOperator;
import datasys.semi.operators.Operator;
import datasys.semi.operators.ScanOperator;
import datasys.semi.planner.Planner;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;
import datasys.semi.schema.Comparison;

/**
 * Unit tests for query planning, Volcano operator tree assembly, and partition pruning in {@link Planner}.
 */
class PlannerTest {

    // --- Test Schema ---
    private static final List<ColumnSpec> SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // --- State Under Test ---
    private StorageEngine engine;
    private Planner planner;

    /**
     * Initializes a storage engine with 2-row partitions and ingests sorted golden data.
     *
     * @param directory temporary directory injected by JUnit
     * @throws IOException if copying test resources fails
     */
    @BeforeEach
    void setUp(@TempDir Path directory) throws IOException {
        Path csvPath = UtilsTest.copyResource(directory, "trips_sorted.csv");
        engine = new StorageEngine(directory, 2);
        engine.createTable("trips", SCHEMA);
        engine.copyFile("trips", csvPath.toString());
        planner = new Planner(engine);
    }

    /**
     * Required Unit Test 3: Sorted golden data with maxRowsPerPartition = 2;
     * the planner keeps exactly the partitions that can match, asserted via
     * ScanStats.
     */
    @Test
    void plannerPruningKeepsMatchingPartitionsOnSortedGoldenData() {
        SelectStatement statement = new SelectStatement("trips", Optional.empty(), Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 200L)));

        Operator plan = planner.plan(statement);
        ScanStats stats = planner.lastScanStats();

        assertEquals(4, stats.partitionsTotal());
        assertEquals(1, stats.partitionsRead());
        assertEquals(3, stats.partitionsPruned());
        assertEquals(stats, planner.getLastScanStats());

        assertInstanceOf(FilterOperator.class, plan);
        FilterOperator filter = (FilterOperator) plan;
        assertInstanceOf(ScanOperator.class, filter.child());
        ScanOperator scan = (ScanOperator) filter.child();
        assertEquals(List.of(3), scan.partitionNumbers());
    }

    /**
     * Verifies pruning on other comparisons and boundaries.
     */
    @Test
    void plannerPruningOnLowerBoundAndFullyPrunedQueries() {
        // Partition 0: distance 12..31. distance < 50 keeps partition 0 and drops 1, 2,
        // 3.
        SelectStatement lowerBound = new SelectStatement("trips", Optional.empty(), Optional.of(new Predicate("distance", Comparison.LESS_THAN, 50L)));
        planner.plan(lowerBound);

        ScanStats lowerStats = planner.lastScanStats();
        assertEquals(4, lowerStats.partitionsTotal());
        assertEquals(1, lowerStats.partitionsRead());
        assertEquals(3, lowerStats.partitionsPruned());

        // Fully pruned query: distance > 500 matches none of the partitions.
        SelectStatement none = new SelectStatement("trips", Optional.empty(), Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 500L)));
        Operator planNone = planner.plan(none);

        ScanStats noneStats = planner.lastScanStats();
        assertEquals(4, noneStats.partitionsTotal());
        assertEquals(0, noneStats.partitionsRead());
        assertEquals(4, noneStats.partitionsPruned());

        assertInstanceOf(FilterOperator.class, planNone);
        ScanOperator scanNone = (ScanOperator) ((FilterOperator) planNone).child();
        assertTrue(scanNone.partitionNumbers().isEmpty());
    }

    /**
     * Required Unit Test 4: Planner shapes:
     * WHERE -> Filter over Scan; no WHERE -> bare Scan over all partitions.
     */
    @Test
    void plannerShapesDifferentiateWhereAndNoWhereQueries() {
        // 1. WHERE -> Filter over Scan
        SelectStatement withWhere = new SelectStatement("trips", Optional.empty(), Optional.of(new Predicate("city", Comparison.EQUALS, "Odense")));

        Operator filteredPlan = planner.plan(withWhere);
        assertInstanceOf(FilterOperator.class, filteredPlan);

        FilterOperator filter = (FilterOperator) filteredPlan;
        assertInstanceOf(ScanOperator.class, filter.child());

        ScanOperator childScan = (ScanOperator) filter.child();
        assertEquals("trips", childScan.tableName());
        assertEquals(0, filter.predicate().columnIndex());
        assertEquals(Comparison.EQUALS, filter.predicate().comparison());
        assertEquals("Odense", filter.predicate().constant());

        // 2. No WHERE -> bare Scan over all partitions
        SelectStatement withoutWhere = new SelectStatement("trips", Optional.empty(), Optional.empty());

        Operator barePlan = planner.plan(withoutWhere);
        assertInstanceOf(ScanOperator.class, barePlan);

        ScanOperator bareScan = (ScanOperator) barePlan;
        assertEquals("trips", bareScan.tableName());
        assertEquals(List.of(0, 1, 2, 3), bareScan.partitionNumbers());

        ScanStats scanStats = planner.lastScanStats();
        assertEquals(4, scanStats.partitionsTotal());
        assertEquals(4, scanStats.partitionsRead());
        assertEquals(0, scanStats.partitionsPruned());
    }

    /**
     * Validates error handling on invalid queries and constructor inputs.
     */
    @Test
    void validationErrorsOnUnknownTableAndColumn() {
        assertThrows(IllegalArgumentException.class, () -> new Planner(null));

        assertThrows(IllegalArgumentException.class, () -> planner.plan(null));

        SelectStatement unknownTable = new SelectStatement("nonexistent", Optional.empty(), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> planner.plan(unknownTable));

        SelectStatement unknownColumn = new SelectStatement("trips", Optional.empty(), Optional.of(new Predicate("nonexistent_col", Comparison.EQUALS, "val")));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(unknownColumn));
    }

    /**
     * Verifies that planning an unfiltered SELECT emits decision=READ with reason=noPredicate
     * for every partition in the table.
     *
     * @throws IOException if reading the engine log fails
     */
    @Test
    void planWithoutPredicateLogsNoPredicateDecisionLines() throws IOException {
        SelectStatement withoutWhere = new SelectStatement("trips", Optional.empty(), Optional.empty());
        planner.plan(withoutWhere);

        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        boolean hasNoPredicateLine = logLines.stream()
                .anyMatch(line -> line.contains("table=trips partition=0 decision=READ reason=noPredicate"));

        assertTrue(hasNoPredicateLine, "expected decision=READ reason=noPredicate log line for partition 0");
    }

    /**
     * Verifies that when a partition is missing column statistics, the planner retains the partition
     * and logs decision=READ with reason=missingStats.
     *
     * @param directory temporary directory for storage engine files
     * @throws IOException if reading or writing catalog files or engine log fails
     */
    @Test
    void prunePartitionsKeepsPartitionAndLogsWhenStatisticsAreMissing(@TempDir Path directory) throws IOException {
        String testTable = "test_table_" + UUID.randomUUID().toString().replace("-", "");
        Path csvPath = UtilsTest.copyResource(directory, "trips_sorted.csv");
        StorageEngine storageEngine = new StorageEngine(directory, 2);
        storageEngine.createTable(testTable, SCHEMA);
        storageEngine.copyFile(testTable, csvPath.toString());

        Path catalogPath = directory.resolve("catalogs").resolve(testTable + ".json");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(catalogPath.toFile());
        ((ObjectNode) rootNode.get("partitions").get(0).get("statistics")).remove("distance");
        mapper.writeValue(catalogPath.toFile(), rootNode);

        StorageEngine reloadedEngine = new StorageEngine(directory, 2);
        Planner reloadedPlanner = new Planner(reloadedEngine);

        SelectStatement statement = new SelectStatement(
                testTable, Optional.empty(), Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 200L)));
        reloadedPlanner.plan(statement);

        ScanStats stats = reloadedPlanner.lastScanStats();
        assertEquals(4, stats.partitionsTotal());
        assertEquals(2, stats.partitionsRead(), "partition 0 (missing stats) and partition 3 should be read");
        assertEquals(2, stats.partitionsPruned());

        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        String expectedDecisionLine = "table=" + testTable + " partition=0 decision=READ reason=missingStats";
        boolean hasMissingStatsLine = logLines.stream().anyMatch(line -> line.contains(expectedDecisionLine));

        assertTrue(hasMissingStatsLine, "expected missingStats log line for partition 0");
    }
}
