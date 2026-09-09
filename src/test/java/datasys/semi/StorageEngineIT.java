package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageEngineIT {

    private static final List<ColumnSpec> SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // Test 1: Schema persistence - createTable, then a new StorageEngine on the
    // same directory still knows the table.
    @Test
    void schemaPersistenceAcrossRestart(@TempDir Path directory) {
        StorageEngine engine1 = new StorageEngine(directory);
        engine1.createTable("trips", SCHEMA);

        StorageEngine engine2 = new StorageEngine(directory);
        assertThrows(IllegalArgumentException.class, () -> engine2.createTable("trips", SCHEMA));
        List<Object[]> rows = engine2.select("trips", "distance", Comparison.GREATER_THAN, 0L);
        assertTrue(rows.isEmpty());
        assertTrue(Files.exists(directory.resolve("catalogs").resolve("trips.json")));
    }

    // Test 2: Duplicate table - second createTable with the same name throws.
    @Test
    void duplicateTableThrows(@TempDir Path directory) {
        StorageEngine engine = new StorageEngine(directory);
        engine.createTable("trips", SCHEMA);
        assertThrows(IllegalArgumentException.class, () -> engine.createTable("trips", SCHEMA));
    }

    // Test 3: Round trip - copy golden CSV; predicate matching everything returns
    // all 8 rows with correct values and types.
    @Test
    void roundTripMatchesAllRowsWithCorrectTypes(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory);
        engine.createTable("trips", SCHEMA);
        engine.copyFile("trips", csv.toString());

        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertEquals(8, rows.size());

        Object[][] expected = {
                { "Copenhagen", 12L, 23.5 },
                { "Aarhus", 187L, 301.0 },
                { "Odense", 95L, 120.75 },
                { "Copenhagen", 140L, 210.0 },
                { "Aalborg", 210L, 340.5 },
                { "Roskilde", 31L, 45.0 },
                { "Copenhagen", 88L, 99.99 },
                { "Esbjerg", 299L, 450.25 }
        };

        for (int i = 0; i < 8; i++) {
            assertArrayEquals(expected[i], rows.get(i));
            assertTrue(rows.get(i)[0] instanceof String);
            assertTrue(rows.get(i)[1] instanceof Long);
            assertTrue(rows.get(i)[2] instanceof Double);
        }
    }

    // Test 4: All comparisons × all types - =, <, > against STRING, LONG, and
    // DOUBLE (9 combinations) on golden data.
    @Test
    void allComparisonsAcrossAllTypesAgainstGoldenData(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory);
        engine.createTable("trips", SCHEMA);
        engine.copyFile("trips", csv.toString());

        // 1. STRING EQUALS
        List<Object[]> cityEquals = engine.select("trips", "city", Comparison.EQUALS, "Copenhagen");
        assertEquals(3, cityEquals.size());

        // 2. STRING LESS_THAN ("Aalborg" and "Aarhus" are lexicographically <
        // "Copenhagen")
        List<Object[]> cityLessThan = engine.select("trips", "city", Comparison.LESS_THAN, "Copenhagen");
        assertEquals(2, cityLessThan.size());

        // 3. STRING GREATER_THAN ("Esbjerg", "Odense", "Roskilde" are > "Copenhagen")
        List<Object[]> cityGreaterThan = engine.select("trips", "city", Comparison.GREATER_THAN, "Copenhagen");
        assertEquals(3, cityGreaterThan.size());

        // 4. LONG EQUALS: 95L (Odense)
        List<Object[]> distEquals = engine.select("trips", "distance", Comparison.EQUALS, 95L);
        assertEquals(1, distEquals.size());
        assertEquals("Odense", distEquals.get(0)[0]);

        // 5. LONG LESS_THAN: 12, 31, 88 are < 95L
        List<Object[]> distLessThan = engine.select("trips", "distance", Comparison.LESS_THAN, 95L);
        assertEquals(3, distLessThan.size());

        // 6. LONG GREATER_THAN: 140, 187, 210, 299 are > 95L
        List<Object[]> distGreaterThan = engine.select("trips", "distance", Comparison.GREATER_THAN, 95L);
        assertEquals(4, distGreaterThan.size());

        // 7. DOUBLE EQUALS: 120.75 (Odense)
        List<Object[]> priceEquals = engine.select("trips", "price", Comparison.EQUALS, 120.75);
        assertEquals(1, priceEquals.size());
        assertEquals("Odense", priceEquals.get(0)[0]);

        // 8. DOUBLE LESS_THAN: 23.5, 45.0 are < 50.0
        List<Object[]> priceLessThan = engine.select("trips", "price", Comparison.LESS_THAN, 50.0);
        assertEquals(2, priceLessThan.size());

        // 9. DOUBLE GREATER_THAN: 301.0, 340.5, 450.25 are > 300.0
        List<Object[]> priceGreaterThan = engine.select("trips", "price", Comparison.GREATER_THAN, 300.0);
        assertEquals(3, priceGreaterThan.size());
    }

    // Test 5: Empty result - a predicate matching nothing returns an empty list.
    @Test
    void emptyResultWhenPredicateMatchesNothing(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory);
        engine.createTable("trips", SCHEMA);
        engine.copyFile("trips", csv.toString());

        List<Object[]> rows = engine.select("trips", "city", Comparison.EQUALS, "NonExistentCity");
        assertTrue(rows.isEmpty());
    }

    // Test 6: Errors - unknown table, unknown column, and a type-mismatched
    // constant each throw.
    @Test
    void errorsOnUnknownTableColumnAndTypeMismatch(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory);
        engine.createTable("trips", SCHEMA);
        engine.copyFile("trips", csv.toString());

        // Unknown table
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("unknown_table", "distance", Comparison.EQUALS, 100L));

        // Unknown column
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "unknown_column", Comparison.EQUALS, 100L));

        // Type mismatch: Integer instead of Long
        assertThrows(IllegalArgumentException.class, () -> engine.select("trips", "distance", Comparison.EQUALS, 100));

        // Type mismatch: String instead of Double
        assertThrows(IllegalArgumentException.class, () -> engine.select("trips", "price", Comparison.EQUALS, "50.0"));
    }

    // Test 7: Partitioning - with maxRowsPerPartition = 2, golden file produces 4
    // partitions with correct min/max.
    @Test
    void partitioningProducesExpectedPartitionsAndStatistics(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory, 2);
        engine.createTable("trips", SCHEMA);
        engine.copyFile("trips", csv.toString());

        Path catalogPath = directory.resolve("catalogs").resolve("trips.json");
        assertTrue(Files.exists(catalogPath));

        ObjectMapper mapper = new ObjectMapper();
        StorageEngine.Catalog catalog = mapper.readValue(catalogPath.toFile(), StorageEngine.Catalog.class);

        assertEquals(4, catalog.partitions.size());

        // Partition 0: rows [Copenhagen,12,23.5] and [Aarhus,187,301.0]
        StorageEngine.Partition p0 = catalog.partitions.get(0);
        assertEquals(2, p0.rowCount);
        assertEquals("Aarhus", p0.statistics.get("city").min);
        assertEquals("Copenhagen", p0.statistics.get("city").max);
        assertEquals("12", p0.statistics.get("distance").min);
        assertEquals("187", p0.statistics.get("distance").max);
        assertEquals("23.5", p0.statistics.get("price").min);
        assertEquals("301.0", p0.statistics.get("price").max);

        // Partition 1: rows [Odense,95,120.75] and [Copenhagen,140,210.0]
        StorageEngine.Partition p1 = catalog.partitions.get(1);
        assertEquals(2, p1.rowCount);
        assertEquals("Copenhagen", p1.statistics.get("city").min);
        assertEquals("Odense", p1.statistics.get("city").max);
        assertEquals("95", p1.statistics.get("distance").min);
        assertEquals("140", p1.statistics.get("distance").max);
        assertEquals("120.75", p1.statistics.get("price").min);
        assertEquals("210.0", p1.statistics.get("price").max);

        // Partition 2: rows [Aalborg,210,340.5] and [Roskilde,31,45.0]
        StorageEngine.Partition p2 = catalog.partitions.get(2);
        assertEquals(2, p2.rowCount);
        assertEquals("Aalborg", p2.statistics.get("city").min);
        assertEquals("Roskilde", p2.statistics.get("city").max);
        assertEquals("31", p2.statistics.get("distance").min);
        assertEquals("210", p2.statistics.get("distance").max);
        assertEquals("45.0", p2.statistics.get("price").min);
        assertEquals("340.5", p2.statistics.get("price").max);

        // Partition 3: rows [Copenhagen,88,99.99] and [Esbjerg,299,450.25]
        StorageEngine.Partition p3 = catalog.partitions.get(3);
        assertEquals(2, p3.rowCount);
        assertEquals("Copenhagen", p3.statistics.get("city").min);
        assertEquals("Esbjerg", p3.statistics.get("city").max);
        assertEquals("88", p3.statistics.get("distance").min);
        assertEquals("299", p3.statistics.get("distance").max);
        assertEquals("99.99", p3.statistics.get("price").min);
        assertEquals("450.25", p3.statistics.get("price").max);
    }

    // Test 8: Pruning - CSV sorted by distance with maxRowsPerPartition = 2;
    // selective predicate reports >= 2 partitions pruned.
    @Test
    void pruningSkipsPartitionsUsingMinMax(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips_sorted.csv");
        StorageEngine engine = new StorageEngine(directory, 2);
        engine.createTable("trips", SCHEMA);
        engine.copyFile("trips", csv.toString());

        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, 200L);

        ScanStats stats = engine.getLastScanStats();
        assertEquals(4, stats.partitionsTotal());
        assertEquals(1, stats.partitionsRead());
        assertEquals(3, stats.partitionsPruned());
        assertTrue(stats.partitionsPruned() >= 2);

        assertEquals(2, rows.size());
        assertArrayEquals(new Object[] { "Aalborg", 210L, 340.5 }, rows.get(0));
        assertArrayEquals(new Object[] { "Esbjerg", 299L, 450.25 }, rows.get(1));
    }

    // Test 9: Data persistence - copy with engine A; a new engine B on the same
    // directory returns the same rows.
    @Test
    void dataPersistenceAcrossEngines(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engineA = new StorageEngine(directory, 2);
        engineA.createTable("trips", SCHEMA);
        engineA.copyFile("trips", csv.toString());

        List<Object[]> rowsA = engineA.select("trips", "distance", Comparison.GREATER_THAN, 100L);

        StorageEngine engineB = new StorageEngine(directory, 2);
        List<Object[]> rowsB = engineB.select("trips", "distance", Comparison.GREATER_THAN, 100L);

        assertEquals(rowsA.size(), rowsB.size());
        for (int i = 0; i < rowsA.size(); i++) {
            assertArrayEquals(rowsA.get(i), rowsB.get(i));
        }
    }

    private static Path copyResource(Path directory, String filename) throws IOException {
        Path target = directory.resolve(filename);
        try (InputStream stream = StorageEngineIT.class.getResourceAsStream("/" + filename)) {
            if (stream != null) {
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            }
        }
        Path localPath = Path.of("src/test/resources", filename);
        Files.copy(localPath, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}