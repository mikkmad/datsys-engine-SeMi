package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.engine.StorageEngine.Partition;
import datasys.semi.optimizer.CardinalityEstimator;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;

/**
 * Tests the NDV roll forward described in {@code docs/optimizer-design.md}: when
 * the engine starts, every partition written before NDV was collected gets its
 * exact NDV, and the NDVs are committed as the table's next catalog version. A
 * table without NDV is simulated by removing {@code ndv} from the catalog JSON
 * before restarting the engine.
 */
class NdvRollForwardTest {

    // --- Test Configuration ---
    private static final int MAX_ROWS_PER_PARTITION = 4;
    private static final String TABLE = "ndvtrips";
    private static final List<ColumnSpec> SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    /**
     * Partition 0: city {Odense, Aarhus} = 2, distance {10, 20, 30} = 3, price
     * {1.5, 2.5} = 2. Partition 1: one distinct value per column.
     */
    private static final String CSV_WITH_DUPLICATES = """
            Odense,10,1.5
            Odense,10,2.5
            Aarhus,20,1.5
            Odense,30,1.5
            Aarhus,20,9.0
            Aarhus,20,9.0
            Aarhus,20,9.0
            """;

    // --- JSON Tooling ---
    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- State Under Test ---
    private Path directory;

    /**
     * Copies the CSV with known duplicates as version 1 of the test table.
     *
     * @param tempDirectory JUnit-managed temporary directory
     * @throws IOException if the CSV file cannot be written
     */
    @BeforeEach
    void setUp(@TempDir Path tempDirectory) throws IOException {
        directory = tempDirectory;
        Path csvFile = directory.resolve("duplicates.csv");
        Files.writeString(csvFile, CSV_WITH_DUPLICATES, StandardCharsets.UTF_8);

        StorageEngine writer = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);
        writer.createTable(TABLE, SCHEMA);
        writer.copyFile(writer.snapshot(TABLE), csvFile.toString());
    }

    /**
     * Verifies that a restart commits version 2 with the exact NDV of every
     * column of every partition.
     *
     * @throws IOException if the catalog file cannot be rewritten
     */
    @Test
    void restartCommitsExactNdvsForLegacyPartitions() throws IOException {
        removeNdvFromCatalog(catalogFile(1));

        Catalog rolledForward = restart().snapshot(TABLE);

        assertEquals(2L, rolledForward.version());
        assertNdvs(rolledForward.partitions().get(0), 2L, 3L, 2L);
        assertNdvs(rolledForward.partitions().get(1), 1L, 1L, 1L);
    }

    /**
     * Verifies that the rolled-forward NDVs are written to the catalog file and
     * that a second restart has nothing left to commit.
     *
     * @throws IOException if a catalog file cannot be rewritten or read
     */
    @Test
    void rolledForwardNdvIsPersistedAndNotCommittedAgain() throws IOException {
        removeNdvFromCatalog(catalogFile(1));
        restart();

        Catalog reloaded = restart().snapshot(TABLE);

        assertEquals(2L, reloaded.version());
        assertNdvs(reloaded.partitions().get(0), 2L, 3L, 2L);
        JsonNode cityJson = objectMapper.readTree(catalogFile(2).toFile())
                .get("partitions").get(0).get("statistics").get("city");
        assertEquals(2L, cityJson.get("ndv").asLong());
    }

    /**
     * Verifies that a table whose NDVs were collected during COPY is not touched
     * at startup.
     */
    @Test
    void tableWithNdvsIsNotCommittedAtStartup() {
        assertEquals(1L, restart().snapshot(TABLE).version());
    }

    /**
     * Verifies that the estimator sees the real NDV from the first statement on:
     * 2 + 1 distinct cities instead of the 7-row fallback.
     *
     * @throws IOException if the catalog file cannot be rewritten
     */
    @Test
    void estimatorUsesRolledForwardNdvBeforeAnyScan() throws IOException {
        removeNdvFromCatalog(catalogFile(1));

        List<Partition> partitions = restart().snapshot(TABLE).partitions();

        assertEquals(3L, CardinalityEstimator.columnNdv(partitions, "city"));
    }

    /**
     * Verifies that a partition that cannot be read keeps the row-count fallback,
     * the other partitions are still rolled forward, and the engine opens.
     *
     * @throws IOException if the catalog or partition file cannot be changed
     */
    @Test
    void unreadablePartitionKeepsFallbackAndEngineStillOpens() throws IOException {
        removeNdvFromCatalog(catalogFile(1));
        String brokenPath = readVersionOneFromDisk().partitions().get(1).path();
        Files.delete(directory.resolve(brokenPath));

        Catalog current = restart().snapshot(TABLE);

        assertEquals(2L, current.version());
        assertNdvs(current.partitions().get(0), 2L, 3L, 2L);
        assertNull(current.partitions().get(1).statistics().get("city").ndv());
        assertEquals(3L, current.partitions().get(1).columnNdv("city"));
    }

    /**
     * Verifies that every rolled-forward NDV gets its own DEBUG line in the same
     * format as during COPY.
     *
     * @throws IOException if the catalog or log file cannot be read
     */
    @Test
    void rolledForwardNdvIsLogged() throws IOException {
        removeNdvFromCatalog(catalogFile(1));
        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        try {
            restart();
        } finally {
            MDC.remove("sessionId");
        }

        List<String> sessionLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8)
                .stream()
                .filter(line -> line.contains("," + session + ",") && line.contains(",DEBUG,StorageEngine,"))
                .toList();
        assertLogged(sessionLines, "table=ndvtrips partition=0 column=city rows=4 ndv=2");
        assertLogged(sessionLines, "table=ndvtrips partition=1 column=price rows=3 ndv=1");
        assertEquals(6, sessionLines.stream().filter(line -> line.contains(" ndv=")).count());
        assertTrue(sessionLines.stream().anyMatch(line -> line.endsWith(
                ",api=rollForwardNdvs table=ndvtrips version=2 committed=true")));
    }

    /**
     * Verifies that an NDV larger than the partition's row count is rejected.
     */
    @Test
    void rollForwardRejectsNdvAboveRowCount() {
        Partition partition = restart().snapshot(TABLE).partitions().get(0);

        assertThrows(IllegalArgumentException.class,
                () -> partition.withRolledForwardNdvs(Map.of("city", 5L)));
    }

    /**
     * Opens a new engine on the test directory, which runs the startup roll
     * forward.
     *
     * @return the restarted engine
     */
    private StorageEngine restart() {
        return new StorageEngine(directory, MAX_ROWS_PER_PARTITION);
    }

    /**
     * Reads catalog version 1 from disk without starting an engine, so the test
     * can look up partition paths before the roll forward runs.
     *
     * @return the deserialized catalog of version 1
     * @throws IOException if the catalog file cannot be read
     */
    private Catalog readVersionOneFromDisk() throws IOException {
        return objectMapper.readValue(catalogFile(1).toFile(), Catalog.class);
    }

    /**
     * Asserts the stored NDV of every column of one partition.
     *
     * @param partition   partition to check
     * @param cityNdv     expected NDV of {@code city}
     * @param distanceNdv expected NDV of {@code distance}
     * @param priceNdv    expected NDV of {@code price}
     */
    private static void assertNdvs(Partition partition, long cityNdv, long distanceNdv, long priceNdv) {
        assertEquals(cityNdv, partition.statistics().get("city").ndv());
        assertEquals(distanceNdv, partition.statistics().get("distance").ndv());
        assertEquals(priceNdv, partition.statistics().get("price").ndv());
    }

    /**
     * Asserts that exactly one log line ends with the expected message.
     *
     * @param logLines log lines of the test's session
     * @param message  expected log message
     */
    private static void assertLogged(List<String> logLines, String message) {
        assertEquals(1, logLines.stream().filter(line -> line.endsWith("," + message)).count(),
                "expected one log line: " + message);
    }

    /**
     * Removes the {@code ndv} field from every column statistic in a catalog
     * file, as a catalog written before NDV was collected would look.
     *
     * @param catalogFile catalog JSON file to rewrite in place
     * @throws IOException if the file cannot be read or written
     */
    private void removeNdvFromCatalog(Path catalogFile) throws IOException {
        JsonNode catalog = objectMapper.readTree(catalogFile.toFile());
        for (JsonNode partition : catalog.get("partitions")) {
            for (JsonNode statistics : partition.get("statistics")) {
                ((ObjectNode) statistics).remove("ndv");
            }
        }
        objectMapper.writeValue(catalogFile.toFile(), catalog);
    }

    /**
     * Resolves the catalog file of one version of the test table.
     *
     * @param version catalog version, non-negative
     * @return path of {@code catalogs/ndvtrips-v<version>.json}
     */
    private Path catalogFile(long version) {
        return directory.resolve("catalogs").resolve("%s-v%d.json".formatted(TABLE, version));
    }
}
