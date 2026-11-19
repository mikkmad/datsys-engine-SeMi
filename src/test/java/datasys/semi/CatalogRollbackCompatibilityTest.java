package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.engine.StorageEngine.Partition;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;

/**
 * Tests that the engine opens catalogs written by a newer engine version (for
 * example a later version that adds a new column statistic), so rolling back to
 * this version keeps every table usable. Unknown fields are injected at every
 * nesting level of the catalog JSON, so a persisted record that is missing
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} fails these tests.
 */
class CatalogRollbackCompatibilityTest {

    // --- Test Configuration ---
    private static final int MAX_ROWS_PER_PARTITION = 2;
    private static final int ROWS_PER_COPY = 8;
    private static final String TABLE = "trips";
    private static final List<ColumnSpec> SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // --- JSON Tooling ---
    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- State Under Test ---
    private Path directory;
    private Path csvFile;
    private Catalog originalCatalog;

    /**
     * Creates the {@code trips} table, loads one CSV into it (version 1) and
     * rewrites the version 1 catalog file as a newer engine version would.
     *
     * @param tempDirectory JUnit-managed temporary directory
     * @throws IOException if the CSV resource or catalog file cannot be copied or
     *                     rewritten
     */
    @BeforeEach
    void setUp(@TempDir Path tempDirectory) throws IOException {
        directory = tempDirectory;
        csvFile = UtilsTest.copyResource(directory, "trips.csv");
        StorageEngine writer = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);
        writer.createTable(TABLE, SCHEMA);
        writer.copyFile(writer.snapshot(TABLE), csvFile.toString());
        originalCatalog = writer.snapshot(TABLE);
        injectUnknownFieldsAtEveryLevel(catalogFile(1));
    }

    /**
     * Verifies that a restarted engine loads a catalog with unknown fields at the
     * catalog, column, partition and statistics level and reads its known fields
     * and partition data unchanged.
     */
    @Test
    void restartedEngineLoadsCatalogWithUnknownFieldsAtEveryLevel() {
        StorageEngine engine = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);

        Catalog loaded = engine.snapshot(TABLE);

        assertEquals(originalCatalog, loaded);
        assertEquals(ROWS_PER_COPY, countRows(engine, loaded));
    }

    /**
     * Verifies that a historical catalog version with unknown fields is readable
     * through {@link StorageEngine#readCatalogVersion(String, long)}.
     */
    @Test
    void readCatalogVersionIgnoresUnknownFields() {
        StorageEngine engine = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);
        engine.copyFile(engine.snapshot(TABLE), csvFile.toString());

        Catalog historical = engine.readCatalogVersion(TABLE, 1);

        assertEquals(originalCatalog, historical);
    }

    /**
     * Verifies that a COPY after the rollback commits the next version, and that
     * the new catalog file drops the unknown fields (the accepted loss of the
     * newer version's statistics).
     *
     * @throws IOException if the new catalog file cannot be read
     */
    @Test
    void copyAfterRollbackCommitsAndDropsUnknownFields() throws IOException {
        StorageEngine engine = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);

        engine.copyFile(engine.snapshot(TABLE), csvFile.toString());

        Catalog next = engine.snapshot(TABLE);
        assertEquals(2L, next.version());
        assertEquals(2 * ROWS_PER_COPY, countRows(engine, next));
        String nextJson = Files.readString(catalogFile(2));
        assertTrue(Files.readString(catalogFile(1)).contains("\"futureStatistic\""));
        assertFalse(nextJson.contains("futureStatistic"));
        assertFalse(nextJson.contains("futureField"));
    }

    /**
     * Adds an unknown field to the catalog object, every column, every partition
     * and every column statistic, mimicking a catalog written by a newer engine.
     *
     * @param catalogFile catalog JSON file to rewrite in place
     * @throws IOException if the file cannot be read or written
     */
    private void injectUnknownFieldsAtEveryLevel(Path catalogFile) throws IOException {
        ObjectNode catalog = (ObjectNode) objectMapper.readTree(catalogFile.toFile());
        catalog.put("futureField", "catalog");
        for (JsonNode column : catalog.get("schema")) {
            ((ObjectNode) column).put("futureField", "column");
        }
        for (JsonNode partition : catalog.get("partitions")) {
            ((ObjectNode) partition).put("futureField", "partition");
            for (JsonNode statistics : partition.get("statistics")) {
                ((ObjectNode) statistics).put("futureStatistic", 2);
            }
        }
        objectMapper.writeValue(catalogFile.toFile(), catalog);
    }

    /**
     * Resolves the catalog file of one version of the test table.
     *
     * @param version catalog version, non-negative
     * @return path of {@code catalogs/trips-v<version>.json}
     */
    private Path catalogFile(long version) {
        return directory.resolve("catalogs").resolve("%s-v%d.json".formatted(TABLE, version));
    }

    /**
     * Counts the rows of every partition in a catalog snapshot.
     *
     * @param engine  engine that reads the partition files
     * @param catalog catalog snapshot whose partitions are read
     * @return total number of rows across all partitions
     */
    private static int countRows(StorageEngine engine, Catalog catalog) {
        int rowCount = 0;
        for (Partition partition : catalog.partitions()) {
            rowCount += engine.readPartition(catalog, partition).size();
        }
        return rowCount;
    }
}
