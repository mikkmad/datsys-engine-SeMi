package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import datasys.semi.engine.StorageEngine;
import datasys.semi.operators.Operator;
import datasys.semi.operators.ScanOperator;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;

/**
 * Exercise 4 §6 test 2: ScanOperator returns exactly the rows of the partitions
 * it is handed, including the empty partition list of a fully pruned query.
 */
class ScanOperatorTest {

    // --- Golden Table ---
    private static final List<ColumnSpec> SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void returnsExactlyTheRowsOfThePartitionsItIsHanded(@TempDir Path directory) throws IOException {
        StorageEngine engine = loadGoldenTable(directory);

        // Handed partitions 1 and 3 of 4: rows 3-4 and 7-8 of trips_sorted.csv.
        Operator scan = new ScanOperator(engine, "trips", List.of(1, 3));

        Object[][] expected = {
                { "Copenhagen", 88L, 99.99 },
                { "Odense", 95L, 120.75 },
                { "Aalborg", 210L, 340.5 },
                { "Esbjerg", 299L, 450.25 } };

        List<Object[]> rows = drain(scan);
        assertEquals(expected.length, rows.size());
        for (int index = 0; index < expected.length; index++) {
            assertArrayEquals(expected[index], rows.get(index));
        }
    }

    // A fully pruned query: the scan reads nothing and returns nothing.
    @Test
    void emptyPartitionListReturnsNoRows(@TempDir Path directory) throws IOException {
        StorageEngine engine = loadGoldenTable(directory);

        Operator scan = new ScanOperator(engine, "trips", List.of());
        scan.open();
        assertNull(scan.next());
        scan.close();
    }

    /**
     * Creates the golden trips table from the sorted CSV, split two rows per
     * partition so the scan has four partitions to choose from.
     *
     * @param directory the storage directory to build the table in
     * @return a storage engine holding the loaded table
     * @throws IOException if the golden CSV cannot be copied into the directory
     */
    private static StorageEngine loadGoldenTable(Path directory) throws IOException {
        Path csv = copyResource(directory, "trips_sorted.csv");
        StorageEngine engine = new StorageEngine(directory, 2);
        engine.createTable("trips", SCHEMA);
        engine.copyFile("trips", csv.toString());
        return engine;
    }

    /**
     * Runs an operator to completion and collects its output.
     *
     * @param operator the operator to drain
     * @return every row the operator emitted, in emission order
     */
    private static List<Object[]> drain(Operator operator) {
        List<Object[]> rows = new ArrayList<>();

        operator.open();
        Object[] row;
        while ((row = operator.next()) != null) {
            rows.add(row);
        }
        operator.close();

        return rows;
    }

    /**
     * Copies a test resource into the temporary storage directory.
     *
     * @param directory the destination directory
     * @param filename  the resource file name on the test classpath
     * @return the path of the copied file
     * @throws IOException if the resource cannot be read or written
     */
    private static Path copyResource(Path directory, String filename) throws IOException {
        Path target = directory.resolve(filename);
        try (InputStream stream = ScanOperatorTest.class.getResourceAsStream("/" + filename)) {
            if (stream != null) {
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            }
        }
        Files.copy(Path.of("src/test/resources", filename), target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
