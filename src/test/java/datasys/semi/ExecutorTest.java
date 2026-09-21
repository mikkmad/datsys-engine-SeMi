package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import datasys.semi.engine.StorageEngine;
import datasys.semi.executor.Executor;

class ExecutorTest {

    // --- State Under Test ---
    private StorageEngine engine;
    private Path csvFile;

    @BeforeEach
    void setUp(@TempDir Path directory) throws IOException {
        engine = new StorageEngine(directory, 2);
        csvFile = copyResource(directory, "trips.csv");
    }

    /**
     * Verifies end-to-end execution of a multi-statement script producing CSV
     * output.
     */
    @Test
    void executesMultiStatementScriptAndEmitsHeaderlessCsv() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        Executor executor = new Executor(engine, output);

        String script = """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                SELECT * FROM trips WHERE city = 'Odense';
                """.formatted(csvFile.toString());

        executor.execute(script);

        String result = buffer.toString(StandardCharsets.UTF_8).trim();
        assertEquals("Odense,95,120.75", result);
        assertEquals("0", MDC.get("statementNumber"));
    }

    /**
     * Verifies execution without WHERE clause returns all rows in CSV format.
     */
    @Test
    void executesSelectWithoutWhereClause() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        Executor executor = new Executor(engine, output);

        String script = """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                SELECT * FROM trips;
                """.formatted(csvFile.toString());

        executor.execute(script);

        List<String> lines = buffer.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(8, lines.size());
        assertEquals("Copenhagen,12,23.5", lines.get(0));
        assertEquals("Esbjerg,299,450.25", lines.get(7));
    }

    /**
     * Verifies programmatic executeQuery returns matching in-memory rows.
     */
    @Test
    void executeQueryReturnsInMemoryRows() {
        Executor executor = new Executor(engine);
        String setup = """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                """.formatted(csvFile.toString());
        executor.execute(setup);

        List<Object[]> rows = executor.executeQuery("SELECT * FROM trips WHERE distance = 95;");
        assertEquals(1, rows.size());
        assertArrayEquals(new Object[] { "Odense", 95L, 120.75 }, rows.get(0));
        assertEquals("0", MDC.get("statementNumber"));
    }

    /**
     * Verifies execution halts at the first failing statement, restores MDC
     * statementNumber to 0,
     * and leaves subsequent statements unexecuted.
     */
    @Test
    void haltsExecutionAtFirstErrorAndResetsMdc() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        Executor executor = new Executor(engine, output);

        String failingScript = """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                SELECT * FROM nonexistent_table;
                COPY trips FROM '%s';
                """.formatted(csvFile.toString());

        assertThrows(IllegalArgumentException.class, () -> executor.execute(failingScript));
        assertEquals("0", MDC.get("statementNumber"));

        // Third statement (COPY) was skipped due to failure on statement 2
        assertTrue(engine.partitions("trips").isEmpty());
    }

    /**
     * Validates error handling on invalid constructor and method arguments.
     */
    @Test
    void validationErrorsOnInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new Executor(null));
        assertThrows(IllegalArgumentException.class, () -> new Executor(engine, null));

        Executor executor = new Executor(engine);
        assertThrows(IllegalArgumentException.class, () -> executor.execute((String) null));
        assertThrows(IllegalArgumentException.class, () -> executor.executeQuery(null));
        assertThrows(IllegalArgumentException.class,
                () -> executor.executeQuery("CREATE TABLE t (x STRING); SELECT * FROM t;"));
    }

    private static Path copyResource(Path directory, String filename) throws IOException {
        Path target = directory.resolve(filename);
        try (InputStream stream = ExecutorTest.class.getResourceAsStream("/" + filename)) {
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
