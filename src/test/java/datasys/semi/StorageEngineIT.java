package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static datasys.semi.UtilsTest.copyResource;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;
import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.engine.StorageEngine.Partition;
import datasys.semi.executor.Executor;

class StorageEngineIT {

    // --- Test Schema ---
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
        List<Object[]> rows = new Executor(engine2).executeQuery(
                "SELECT * FROM trips WHERE distance > 0;");
        assertTrue(rows.isEmpty());
        assertTrue(Files.exists(directory.resolve("catalogs").resolve("trips-v0.json")));
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
        engine.copyFile(engine.snapshot("trips"), csv.toString());

        List<Object[]> rows = new Executor(engine).executeQuery(
                "SELECT * FROM trips WHERE distance > -1;");
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
        engine.copyFile(engine.snapshot("trips"), csv.toString());

        Executor executor = new Executor(engine);

        // 1. STRING EQUALS
        List<Object[]> cityEquals = executor.executeQuery(
                "SELECT * FROM trips WHERE city = 'Copenhagen';");
        assertEquals(3, cityEquals.size());

        // 2. STRING LESS_THAN ("Aalborg" and "Aarhus" are lexicographically <
        // "Copenhagen")
        List<Object[]> cityLessThan = executor.executeQuery(
                "SELECT * FROM trips WHERE city < 'Copenhagen';");
        assertEquals(2, cityLessThan.size());

        // 3. STRING GREATER_THAN ("Esbjerg", "Odense", "Roskilde" are > "Copenhagen")
        List<Object[]> cityGreaterThan = executor.executeQuery(
                "SELECT * FROM trips WHERE city > 'Copenhagen';");
        assertEquals(3, cityGreaterThan.size());

        // 4. LONG EQUALS: 95L (Odense)
        List<Object[]> distEquals = executor.executeQuery(
                "SELECT * FROM trips WHERE distance = 95;");
        assertEquals(1, distEquals.size());
        assertEquals("Odense", distEquals.get(0)[0]);

        // 5. LONG LESS_THAN: 12, 31, 88 are < 95L
        List<Object[]> distLessThan = executor.executeQuery(
                "SELECT * FROM trips WHERE distance < 95;");
        assertEquals(3, distLessThan.size());

        // 6. LONG GREATER_THAN: 140, 187, 210, 299 are > 95L
        List<Object[]> distGreaterThan = executor.executeQuery(
                "SELECT * FROM trips WHERE distance > 95;");
        assertEquals(4, distGreaterThan.size());

        // 7. DOUBLE EQUALS: 120.75 (Odense)
        List<Object[]> priceEquals = executor.executeQuery(
                "SELECT * FROM trips WHERE price = 120.75;");
        assertEquals(1, priceEquals.size());
        assertEquals("Odense", priceEquals.get(0)[0]);

        // 8. DOUBLE LESS_THAN: 23.5, 45.0 are < 50.0
        List<Object[]> priceLessThan = executor.executeQuery(
                "SELECT * FROM trips WHERE price < 50.0;");
        assertEquals(2, priceLessThan.size());

        // 9. DOUBLE GREATER_THAN: 301.0, 340.5, 450.25 are > 300.0
        List<Object[]> priceGreaterThan = executor.executeQuery(
                "SELECT * FROM trips WHERE price > 300.0;");
        assertEquals(3, priceGreaterThan.size());
    }

    // Test 5: Empty result - a predicate matching nothing returns an empty list.
    @Test
    void emptyResultWhenPredicateMatchesNothing(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory);
        engine.createTable("trips", SCHEMA);
        engine.copyFile(engine.snapshot("trips"), csv.toString());

        List<Object[]> rows = new Executor(engine).executeQuery(
                "SELECT * FROM trips WHERE city = 'NonExistentCity';");
        assertTrue(rows.isEmpty());
    }

    // Test 6: Errors - unknown table, unknown column, and a type-mismatched
    // constant each throw.
    @Test
    void errorsOnUnknownTableColumnAndTypeMismatch(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory);
        engine.createTable("trips", SCHEMA);
        engine.copyFile(engine.snapshot("trips"), csv.toString());

        // Unknown table
        assertThrows(IllegalArgumentException.class,
                () -> new Executor(engine).executeQuery(
                        "SELECT * FROM unknown_table WHERE distance = 100;"));

        // Unknown column
        assertThrows(IllegalArgumentException.class,
                () -> new Executor(engine).executeQuery(
                        "SELECT * FROM trips WHERE unknown_column = 100;"));

        // Type mismatch: String instead of Double
        assertThrows(IllegalArgumentException.class, () -> new Executor(engine).executeQuery(
                "SELECT * FROM trips WHERE price = '50.0';"));
    }

    // Test 7: Partitioning - with maxRowsPerPartition = 2, golden file produces 4
    // partitions with correct min/max.
    @Test
    void partitioningProducesExpectedPartitionsAndStatistics(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory, 2);
        engine.createTable("trips", SCHEMA);
        engine.copyFile(engine.snapshot("trips"), csv.toString());

        Path catalogPath = directory.resolve("catalogs").resolve("trips-v1.json");
        assertTrue(Files.exists(catalogPath));

        ObjectMapper mapper = new ObjectMapper();
        Catalog catalog = mapper.readValue(catalogPath.toFile(), Catalog.class);

        assertEquals(1L, catalog.version());
        assertEquals(4, catalog.partitions().size());

        // Partition 0: rows [Copenhagen,12,23.5] and [Aarhus,187,301.0]
        Partition p0 = catalog.partitions().get(0);
        assertEquals(2, p0.rowCount());
        assertEquals("Aarhus", p0.statistics().get("city").min());
        assertEquals("Copenhagen", p0.statistics().get("city").max());
        assertEquals("12", p0.statistics().get("distance").min());
        assertEquals("187", p0.statistics().get("distance").max());
        assertEquals("23.5", p0.statistics().get("price").min());
        assertEquals("301.0", p0.statistics().get("price").max());

        // Partition 1: rows [Odense,95,120.75] and [Copenhagen,140,210.0]
        Partition p1 = catalog.partitions().get(1);
        assertEquals(2, p1.rowCount());
        assertEquals("Copenhagen", p1.statistics().get("city").min());
        assertEquals("Odense", p1.statistics().get("city").max());
        assertEquals("95", p1.statistics().get("distance").min());
        assertEquals("140", p1.statistics().get("distance").max());
        assertEquals("120.75", p1.statistics().get("price").min());
        assertEquals("210.0", p1.statistics().get("price").max());

        // Partition 2: rows [Aalborg,210,340.5] and [Roskilde,31,45.0]
        Partition p2 = catalog.partitions().get(2);
        assertEquals(2, p2.rowCount());
        assertEquals("Aalborg", p2.statistics().get("city").min());
        assertEquals("Roskilde", p2.statistics().get("city").max());
        assertEquals("31", p2.statistics().get("distance").min());
        assertEquals("210", p2.statistics().get("distance").max());
        assertEquals("45.0", p2.statistics().get("price").min());
        assertEquals("340.5", p2.statistics().get("price").max());

        // Partition 3: rows [Copenhagen,88,99.99] and [Esbjerg,299,450.25]
        Partition p3 = catalog.partitions().get(3);
        assertEquals(2, p3.rowCount());
        assertEquals("Copenhagen", p3.statistics().get("city").min());
        assertEquals("Esbjerg", p3.statistics().get("city").max());
        assertEquals("88", p3.statistics().get("distance").min());
        assertEquals("299", p3.statistics().get("distance").max());
        assertEquals("99.99", p3.statistics().get("price").min());
        assertEquals("450.25", p3.statistics().get("price").max());
    }

    // Test 8: Data persistence - copy with engine A; a new engine B on the same
    // directory returns the same rows.
    @Test
    void dataPersistenceAcrossEngines(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engineA = new StorageEngine(directory, 2);
        engineA.createTable("trips", SCHEMA);
        engineA.copyFile(engineA.snapshot("trips"), csv.toString());

        List<Object[]> rowsA = new Executor(engineA).executeQuery(
                "SELECT * FROM trips WHERE distance > 100;");

        StorageEngine engineB = new StorageEngine(directory, 2);
        List<Object[]> rowsB = new Executor(engineB).executeQuery(
                "SELECT * FROM trips WHERE distance > 100;");

        assertEquals(rowsA.size(), rowsB.size());
        for (int i = 0; i < rowsA.size(); i++) {
            assertArrayEquals(rowsA.get(i), rowsB.get(i));
        }
    }

    // Streaming COPY: a malformed line found after full partitions were already
    // written fails the whole copy and leaves no partition files or catalog
    // entries.
    @Test
    void malformedLineAfterFullPartitionsLeavesNoPartitionsBehind(@TempDir Path directory) throws IOException {
        Path csv = directory.resolve("broken.csv");
        Files.writeString(csv, "Aarhus,1,1.0\nOdense,2,2.0\nVejle,3,3.0\nHerning,4,4.0\nAalborg,oops,5.0");
        StorageEngine engine = new StorageEngine(directory, 2);
        engine.createTable("trips", SCHEMA);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> engine.copyFile(engine.snapshot("trips"), csv.toString()));

        assertTrue(failure.getMessage().contains("broken.csv: line 5"));
        assertTrue(engine.snapshot("trips").partitions().isEmpty());
        assertFalse(Files.exists(directory.resolve("data").resolve("trips")));
        assertTrue(new StorageEngine(directory, 2).snapshot("trips").partitions().isEmpty());
    }

    // Streaming COPY: a failed copy leaves the table empty, so a later valid copy
    // into the same table succeeds.
    @Test
    void failedCopyCanBeRetriedWithValidFile(@TempDir Path directory) throws IOException {
        Path broken = directory.resolve("broken.csv");
        Files.writeString(broken, "Aarhus,1,1.0\nOdense,2,2.0\nVejle,x,3.0");
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory, 2);
        engine.createTable("trips", SCHEMA);

        assertThrows(IllegalArgumentException.class,
                () -> engine.copyFile(engine.snapshot("trips"), broken.toString()));
        engine.copyFile(engine.snapshot("trips"), csv.toString());

        assertEquals(4, engine.snapshot("trips").partitions().size());
        assertEquals(8, new Executor(engine).executeQuery("SELECT * FROM trips;").size());
    }

    // Streaming COPY: rows are flushed in partitions of at most
    // maxRowsPerPartition,
    // with the remainder in a final, smaller partition.
    @Test
    void copyStreamsIntoPartitionsOfAtMostMaxRows(@TempDir Path directory) throws IOException {
        Path csv = directory.resolve("eleven.csv");
        Files.writeString(csv, String.join("\n", IntStream.rangeClosed(1, 11)
                .mapToObj(row -> "City" + row + "," + row + "," + row + ".5")
                .toList()));
        StorageEngine engine = new StorageEngine(directory, 4);
        engine.createTable("trips", SCHEMA);

        engine.copyFile(engine.snapshot("trips"), csv.toString());

        List<Integer> rowCounts = engine.snapshot("trips").partitions().stream()
                .map(Partition::rowCount)
                .toList();
        assertEquals(List.of(4, 4, 3), rowCounts);
        assertEquals(11, new Executor(engine).executeQuery("SELECT * FROM trips;").size());
    }

    // Exercise 7.2: CREATE TABLE -> COPY -> COPY produces versions 0, 1, 2 and sums
    // partitions.
    @Test
    void createCopyCopyProducesVersionsZeroThroughTwo(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory, 2);

        engine.createTable("trips", SCHEMA);
        assertEquals(0L, engine.snapshot("trips").version());
        assertEquals(0, engine.snapshot("trips").partitions().size());

        engine.copyFile(engine.snapshot("trips"), csv.toString());
        assertEquals(1L, engine.snapshot("trips").version());
        assertEquals(4, engine.snapshot("trips").partitions().size());

        engine.copyFile(engine.snapshot("trips"), csv.toString());
        assertEquals(2L, engine.snapshot("trips").version());
        assertEquals(8, engine.snapshot("trips").partitions().size());
    }

    // Exercise 7.2: A second COPY appends new UUID-named partitions without
    // overwriting v1 partitions.
    @Test
    void secondCopyAppendsWithoutOverwritingExistingPartitions(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory, 2);
        engine.createTable("trips", SCHEMA);

        engine.copyFile(engine.snapshot("trips"), csv.toString());
        Catalog v1 = engine.snapshot("trips");
        Map<String, byte[]> v1FileBytes = new HashMap<>();
        for (Partition partition : v1.partitions()) {
            v1FileBytes.put(partition.path(), Files.readAllBytes(directory.resolve(partition.path())));
        }

        engine.copyFile(v1, csv.toString());
        Catalog v2 = engine.snapshot("trips");
        assertEquals(8, v2.partitions().size());

        Set<String> allPaths = new HashSet<>();
        for (Partition partition : v2.partitions()) {
            assertTrue(allPaths.add(partition.path()), "duplicate partition path: " + partition.path());
        }

        for (int index = 0; index < v1.partitions().size(); index++) {
            String path = v1.partitions().get(index).path();
            assertEquals(path, v2.partitions().get(index).path());
            assertArrayEquals(v1FileBytes.get(path), Files.readAllBytes(directory.resolve(path)));
        }
    }

    // Exercise 7.2: Each commit writes its own catalog file with a matching version
    // field.
    @Test
    void eachCommitWritesItsOwnCatalogFile(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine = new StorageEngine(directory, 2);
        engine.createTable("trips", SCHEMA);
        engine.copyFile(engine.snapshot("trips"), csv.toString());
        engine.copyFile(engine.snapshot("trips"), csv.toString());

        ObjectMapper mapper = new ObjectMapper();
        for (long version = 0; version <= 2; version++) {
            Path file = directory.resolve("catalogs").resolve("trips-v" + version + ".json");
            assertTrue(Files.exists(file), "expected catalog file " + file);
            Catalog onDisk = mapper.readValue(file.toFile(), Catalog.class);
            assertEquals(version, onDisk.version());
        }
    }

    // Exercise 7.2: Restarting on the same directory loads the highest version and
    // ignores stray .tmp-* files.
    @Test
    void freshEngineLoadsHighestCatalogVersion(@TempDir Path directory) throws IOException {
        Path csv = copyResource(directory, "trips.csv");
        StorageEngine engine1 = new StorageEngine(directory, 2);
        engine1.createTable("trips", SCHEMA);
        engine1.copyFile(engine1.snapshot("trips"), csv.toString());
        engine1.copyFile(engine1.snapshot("trips"), csv.toString());

        Path strayTmp = directory.resolve("catalogs").resolve("trips-v99.json.tmp-leftover");
        Files.writeString(strayTmp, "{broken json", StandardCharsets.UTF_8);

        StorageEngine engine2 = new StorageEngine(directory, 2);
        Catalog loaded = engine2.snapshot("trips");
        assertEquals(2L, loaded.version());
        assertEquals(8, loaded.partitions().size());
    }

    // Exercise 7.2: A failed second COPY leaves v1 intact, keeps v1 partitions
    // readable, and deletes new partitions.
    @Test
    void failedSecondCopyKeepsPreviousVersionAndPartitions(@TempDir Path directory) throws IOException {
        Path validCsv = copyResource(directory, "trips.csv");
        Path brokenCsv = directory.resolve("broken_second.csv");
        Files.writeString(brokenCsv,
                "Aarhus,1,1.0\nOdense,2,2.0\nVejle,3,3.0\nHerning,4,4.0\nAalborg,bad_long,5.0");

        StorageEngine engine = new StorageEngine(directory, 2);
        engine.createTable("trips", SCHEMA);
        engine.copyFile(engine.snapshot("trips"), validCsv.toString());

        Catalog v1 = engine.snapshot("trips");
        assertEquals(1L, v1.version());
        assertEquals(4, v1.partitions().size());

        assertThrows(IllegalArgumentException.class,
                () -> engine.copyFile(engine.snapshot("trips"), brokenCsv.toString()));

        Catalog afterFailure = engine.snapshot("trips");
        assertEquals(1L, afterFailure.version());
        assertEquals(4, afterFailure.partitions().size());

        try (Stream<Path> dataFiles = Files.list(directory.resolve("data").resolve("trips"))) {
            assertEquals(4L, dataFiles.count());
        }

        List<Object[]> rows = new Executor(engine).executeQuery("SELECT * FROM trips;");
        assertEquals(8, rows.size());
    }

    // Exercise 7.2: Retention keeps only the 10 newest catalog versions and
    // readCatalogVersion throws on dropped ones.
    @Test
    void retentionKeepsOnlyTenNewestCatalogVersions(@TempDir Path directory) throws IOException {
        Path csv = directory.resolve("two_rows.csv");
        Files.writeString(csv, "Copenhagen,12,23.5\nAarhus,187,301.0\n", StandardCharsets.UTF_8);

        StorageEngine engine = new StorageEngine(directory, 2);
        engine.createTable("trips", SCHEMA);
        for (int copyIndex = 1; copyIndex <= 12; copyIndex++) {
            engine.copyFile(engine.snapshot("trips"), csv.toString());
        }

        assertEquals(12L, engine.snapshot("trips").version());

        for (long droppedVersion = 0; droppedVersion <= 2; droppedVersion++) {
            long versionToRead = droppedVersion;
            assertFalse(Files.exists(directory.resolve("catalogs").resolve("trips-v" + versionToRead + ".json")));
            assertThrows(IllegalStateException.class,
                    () -> engine.readCatalogVersion("trips", versionToRead));
        }

        for (long retainedVersion = 3; retainedVersion <= 12; retainedVersion++) {
            assertTrue(Files.exists(directory.resolve("catalogs").resolve("trips-v" + retainedVersion + ".json")));
            Catalog catalog = engine.readCatalogVersion("trips", retainedVersion);
            assertEquals(retainedVersion, catalog.version());
        }
    }
}
