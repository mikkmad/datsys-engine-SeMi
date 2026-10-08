package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import datasys.semi.engine.StorageEngine;
import datasys.semi.executor.Executor;

/**
 * Unit and component tests for SQL script and query execution via {@link Executor}.
 *
 * <p>
 * Validates statement sequencing, Volcano operator pipeline draining, MDC lifecycle,
 * and enriched error logging on statement execution failures.
 */
class ExecutorTest {

    // --- State Under Test ---
    private StorageEngine engine;
    private Path csvFile;

    /**
     * Initializes a fresh storage engine with 2-row partitions and copies golden CSV data.
     *
     * @param directory temporary test directory injected by JUnit
     * @throws IOException if copying test resources fails
     */
    @BeforeEach
    void setUp(@TempDir Path directory) throws IOException {
        engine = new StorageEngine(directory, 2);
        csvFile = UtilsTest.copyResource(directory, "trips.csv");
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

    /**
     * Verifies that statement execution failure logs an enriched error message
     * with statement_failed operation and reason while statementNumber is active in MDC.
     *
     * @throws IOException if reading the engine log fails
     */
    @Test
    void executeFailureLogsStatementFailedWithActiveStatementNumber() throws IOException {
        Executor executor = new Executor(engine);
        String missingTable = "missing_" + UUID.randomUUID().toString().replace("-", "");
        String script = """
                CREATE TABLE valid_table (city STRING);
                SELECT * FROM %s;
                """.formatted(missingTable);

        assertThrows(IllegalArgumentException.class, () -> executor.execute(script));

        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        String expectedPattern = ",2,";
        String expectedMessage = "statement_failed operation=SELECT reason=unknown table: " + missingTable;
        boolean foundEnrichedError = logLines.stream().anyMatch(line ->
                line.contains(expectedPattern) && line.contains(",ERROR,") && line.contains(expectedMessage));

        assertTrue(foundEnrichedError, "expected statement_failed log line for statement 2");
    }

    /**
     * Verifies that executeQuery failure logs an enriched error message with
     * statement_failed operation=SELECT while statementNumber is 1 in MDC.
     *
     * @throws IOException if reading the engine log fails
     */
    @Test
    void executeQueryFailureLogsStatementFailed() throws IOException {
        Executor executor = new Executor(engine);
        String missingTable = "missing_query_" + UUID.randomUUID().toString().replace("-", "");

        assertThrows(IllegalArgumentException.class,
                () -> executor.executeQuery("SELECT * FROM " + missingTable + ";"));

        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        String expectedPattern = ",1,";
        String expectedMessage = "statement_failed operation=SELECT reason=unknown table: " + missingTable;
        boolean foundEnrichedError = logLines.stream().anyMatch(line ->
                line.contains(expectedPattern) && line.contains(",ERROR,") && line.contains(expectedMessage));

        assertTrue(foundEnrichedError, "expected statement_failed log line for executeQuery");
    }

    /**
     * Verifies that CREATE TABLE statement failure logs an enriched error message
     * with statement_failed operation=CREATE_TABLE while statementNumber is active in MDC.
     *
     * @throws IOException if reading the engine log fails
     */
    @Test
    void executeFailureLogsStatementFailedForCreateTable() throws IOException {
        Executor executor = new Executor(engine);
        String duplicateTable = "dup_" + UUID.randomUUID().toString().replace("-", "");
        String script = """
                CREATE TABLE %s (city STRING);
                CREATE TABLE %s (city STRING);
                """.formatted(duplicateTable, duplicateTable);

        assertThrows(IllegalArgumentException.class, () -> executor.execute(script));

        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        String expectedPattern = ",2,";
        String expectedMessage = "statement_failed operation=CREATE_TABLE reason=table already exists: " + duplicateTable;
        boolean foundEnrichedError = logLines.stream().anyMatch(line ->
                line.contains(expectedPattern) && line.contains(",ERROR,") && line.contains(expectedMessage));

        assertTrue(foundEnrichedError, "expected statement_failed log line for CREATE_TABLE on statement 2");
    }

    /**
     * Verifies that COPY statement failure logs an enriched error message
     * with statement_failed operation=COPY while statementNumber is active in MDC.
     *
     * @throws IOException if reading the engine log fails
     */
    @Test
    void executeFailureLogsStatementFailedForCopy() throws IOException {
        Executor executor = new Executor(engine);
        String testTable = "copy_fail_" + UUID.randomUUID().toString().replace("-", "");
        String missingCsv = "/tmp/nonexistent_" + UUID.randomUUID().toString().replace("-", "") + ".csv";
        String script = """
                CREATE TABLE %s (city STRING);
                COPY %s FROM '%s';
                """.formatted(testTable, testTable, missingCsv);

        assertThrows(IllegalArgumentException.class, () -> executor.execute(script));

        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        String expectedPattern = ",2,";
        String expectedMessage = "statement_failed operation=COPY reason=Could not read " + missingCsv;
        boolean foundEnrichedError = logLines.stream().anyMatch(line ->
                line.contains(expectedPattern) && line.contains(",ERROR,") && line.contains(expectedMessage));

        assertTrue(foundEnrichedError, "expected statement_failed log line for COPY on statement 2");
    }
}
