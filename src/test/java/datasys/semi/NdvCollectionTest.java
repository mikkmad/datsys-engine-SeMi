package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;

/**
 * Tests NDV collection during {@code COPY} and {@code DELETE} rewrites (Exercise
 * 11 §2, required test §8.1): known duplicates give the exact per-partition NDV
 * for every column type, the NDV is persisted and logged, and partitions written
 * before NDV was collected fall back to their row count.
 */
class NdvCollectionTest {

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
    private Path csvFile;
    private StorageEngine engine;

    /**
     * Creates an empty table and writes the CSV with known duplicates.
     *
     * @param tempDirectory JUnit-managed temporary directory
     * @throws IOException if the CSV file cannot be written
     */
    @BeforeEach
    void setUp(@TempDir Path tempDirectory) throws IOException {
        directory = tempDirectory;
        csvFile = directory.resolve("duplicates.csv");
        Files.writeString(csvFile, CSV_WITH_DUPLICATES, StandardCharsets.UTF_8);
        engine = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);
        engine.createTable(TABLE, SCHEMA);
    }

    /**
     * Verifies that each partition stores the exact NDV of every column, for
     * STRING, LONG and DOUBLE columns with known duplicates.
     */
    @Test
    void copyStoresExactPerPartitionNdvForEveryColumnType() {
        copyDuplicates();

        List<Partition> partitions = engine.snapshot(TABLE).partitions();

        assertEquals(2, partitions.size());
        assertNdvs(partitions.get(0), 2L, 3L, 2L);
        assertNdvs(partitions.get(1), 1L, 1L, 1L);
    }

    /**
     * Verifies that the NDV survives an engine restart, so it is persisted in the
     * catalog file and not only kept in memory.
     */
    @Test
    void ndvIsPersistedInCatalog() {
        copyDuplicates();

        Catalog reloaded = new StorageEngine(directory, MAX_ROWS_PER_PARTITION).snapshot(TABLE);

        assertNdvs(reloaded.partitions().get(0), 2L, 3L, 2L);
    }

    /**
     * Verifies that every NDV gets its own DEBUG line with table, partition,
     * column, rows and ndv when it is created.
     *
     * @throws IOException if the log file cannot be read
     */
    @Test
    void eachNdvIsLoggedWhenCreated() throws IOException {
        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        try {
            copyDuplicates();
        } finally {
            MDC.remove("sessionId");
        }

        List<String> sessionLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8)
                .stream()
                .filter(line -> line.contains("," + session + ",") && line.contains(",DEBUG,StorageEngine,"))
                .toList();
        assertLogged(sessionLines, "table=ndvtrips partition=0 column=city rows=4 ndv=2");
        assertLogged(sessionLines, "table=ndvtrips partition=0 column=distance rows=4 ndv=3");
        assertLogged(sessionLines, "table=ndvtrips partition=0 column=price rows=4 ndv=2");
        assertLogged(sessionLines, "table=ndvtrips partition=1 column=city rows=3 ndv=1");
        assertEquals(6, sessionLines.stream().filter(line -> line.contains(" ndv=")).count());
    }

    /**
     * Verifies that a DELETE rewrite recomputes the NDV of the rewritten
     * partition and carries the untouched partition's NDV over.
     */
    @Test
    void deleteRewriteRecomputesNdv() {
        copyDuplicates();
        Partition untouched = engine.snapshot(TABLE).partitions().get(1);

        engine.deleteRows(engine.snapshot(TABLE), List.of(0), row -> row[1].equals(30L));

        List<Partition> partitions = engine.snapshot(TABLE).partitions();
        assertEquals(3, partitions.get(0).rowCount());
        assertNdvs(partitions.get(0), 2L, 2L, 2L);
        assertEquals(untouched, partitions.get(1));
    }

    /**
     * Verifies that a partition written before NDV was collected has a null NDV
     * and falls back to its row count, and that a later commit does not write
     * {@code "ndv": null} for it.
     *
     * @throws IOException if the catalog file cannot be rewritten or read
     */
    @Test
    void partitionWithoutNdvFallsBackToRowCount() throws IOException {
        copyDuplicates();
        removeNdvFromCatalog(catalogFile(1));

        StorageEngine restarted = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);
        Partition legacy = restarted.snapshot(TABLE).partitions().get(0);
        assertNull(legacy.statistics().get("city").ndv());
        assertEquals(4L, legacy.columnNdv("city"));

        restarted.copyFile(restarted.snapshot(TABLE), csvFile.toString());
        JsonNode partitionsJson = objectMapper.readTree(catalogFile(2).toFile()).get("partitions");
        assertFalse(partitionsJson.get(0).get("statistics").get("city").has("ndv"));
        assertTrue(partitionsJson.get(2).get("statistics").get("city").has("ndv"));
    }

    /**
     * Verifies that the column NDV lookup rejects a column without statistics.
     */
    @Test
    void columnNdvOfUnknownColumnThrows() {
        copyDuplicates();
        Partition partition = engine.snapshot(TABLE).partitions().get(0);

        assertThrows(IllegalArgumentException.class, () -> partition.columnNdv("missing"));
    }

    /**
     * Verifies that counting distinct values of an empty or null list throws,
     * like the min/max helper.
     */
    @Test
    void distinctCountOfEmptyValuesThrows() {
        assertThrows(IllegalArgumentException.class, () -> engine.distinctCount(List.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.distinctCount(null));
    }

    /**
     * Loads the CSV with known duplicates into the table as version 1.
     */
    private void copyDuplicates() {
        engine.copyFile(engine.snapshot(TABLE), csvFile.toString());
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
