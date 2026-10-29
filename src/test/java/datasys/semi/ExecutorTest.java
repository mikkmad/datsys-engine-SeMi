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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import datasys.semi.engine.ConflictException;
import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.executor.Executor;
import datasys.semi.models.SelectStatement;
import datasys.semi.models.Statement;
import datasys.semi.operators.Operator;
import datasys.semi.parser.SqlParseException;
import datasys.semi.planner.Planner;

class ExecutorTest {

    // --- State Under Test ---
    private StorageEngine engine;
    private Path csvFile;

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
     * statementNumber to 0, and leaves subsequent statements unexecuted.
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
        assertTrue(engine.snapshot("trips").partitions().isEmpty());
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
     * Verifies that executing a failing statement emits an ERROR line containing
     * operation and failure reason with the active statement number in MDC.
     */
    @Test
    void executeFailureLogsStatementFailedWithActiveStatementNumber() throws IOException {
        Executor executor = new Executor(engine);
        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        String script = "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);\nSELECT * FROM missing_table;";

        try {
            assertThrows(IllegalArgumentException.class, () -> executor.execute(script));
        } finally {
            MDC.remove("sessionId");
        }

        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        boolean hasFailedError = logLines.stream()
                .filter(line -> line.contains("," + session + ","))
                .anyMatch(line -> line.contains(",2,") && line.contains(",ERROR,")
                        && line.contains("statement_failed operation=SELECT reason=unknown table: missing_table"));
        assertTrue(hasFailedError, "expected ERROR line with active statementNumber 2 and statement_failed message");
    }

    /**
     * Verifies that SELECT after each commit sees that commit's cumulative row
     * count.
     */
    @Test
    void selectAfterEachCommitSeesThatCommitsRows() {
        Executor executor = new Executor(engine);
        executor.execute("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);");
        assertEquals(0, executor.executeQuery("SELECT * FROM trips;").size());
        assertEquals(0L, engine.snapshot("trips").version());

        executor.execute("COPY trips FROM '%s';".formatted(csvFile.toString()));
        assertEquals(8, executor.executeQuery("SELECT * FROM trips;").size());
        assertEquals(1L, engine.snapshot("trips").version());

        executor.execute("COPY trips FROM '%s';".formatted(csvFile.toString()));
        assertEquals(16, executor.executeQuery("SELECT * FROM trips;").size());
        assertEquals(2L, engine.snapshot("trips").version());
    }

    /**
     * Verifies that a plan built from snapshot v1 still yields only the v1 rows
     * even after a v2 commit appends more partitions.
     */
    @Test
    void pinnedSnapshotIsUnaffectedByLaterCommit() {
        Executor executor = new Executor(engine);
        executor.execute("""
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                """.formatted(csvFile.toString()));

        Catalog snapshotV1 = engine.snapshot("trips");
        assertEquals(1L, snapshotV1.version());
        Planner planner = new Planner(engine);
        Operator pinnedPlan = planner.plan(
                new SelectStatement("trips", Optional.empty(), Optional.empty()), snapshotV1);

        // Commit v2 while pinnedPlan still holds snapshotV1
        executor.execute("COPY trips FROM '%s';".formatted(csvFile.toString()));
        assertEquals(2L, engine.snapshot("trips").version());

        List<Object[]> rowsFromPinnedPlan = new ArrayList<>();
        pinnedPlan.open();
        try {
            Object[] row;
            while ((row = pinnedPlan.next()) != null) {
                rowsFromPinnedPlan.add(row);
            }
        } finally {
            pinnedPlan.close();
        }

        assertEquals(8, rowsFromPinnedPlan.size());
        assertEquals(16, executor.executeQuery("SELECT * FROM trips;").size());
    }

    /**
     * Verifies that committing against a stale base catalog version throws
     * ConflictException (Exercise 8 §1).
     */
    @Test
    void commitWithStaleBaseVersionThrowsConflictException() {
        Executor executor = new Executor(engine);
        executor.execute("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);");

        Catalog staleSnapshotV0 = engine.snapshot("trips");
        executor.execute("COPY trips FROM '%s';".formatted(csvFile.toString()));
        assertEquals(1L, engine.snapshot("trips").version());

        assertThrows(ConflictException.class,
                () -> engine.copyFile(staleSnapshotV0, csvFile.toString()));
        assertEquals(1L, engine.snapshot("trips").version());
    }

    /**
     * Verifies that a failing DELETE statement in a script emits an ERROR log
     * with operation=DELETE, resets MDC statementNumber, and emits zero bytes to output.
     */
    @Test
    void deleteBindingFailureLogsStatementFailedAndLeavesOutputEmpty() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        Executor executor = new Executor(engine, output);

        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        String script = "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);\nDELETE FROM missing_table;";

        try {
            assertThrows(IllegalArgumentException.class, () -> executor.execute(script));
        } finally {
            MDC.remove("sessionId");
        }

        assertEquals("0", MDC.get("statementNumber"));
        assertEquals(0, buffer.size(), "stdout must remain completely empty for DELETE");

        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        boolean hasFailedError = logLines.stream()
                .filter(line -> line.contains("," + session + ","))
                .anyMatch(line -> line.contains(",2,") && line.contains(",ERROR,")
                        && line.contains("statement_failed operation=DELETE reason=unknown table: missing_table"));
        assertTrue(hasFailedError, "expected ERROR line with active statementNumber 2 and statement_failed operation=DELETE");
    }

    /**
     * Required Test 3: CREATE, COPY, COPY, DELETE produce versions 0 through 3 and
     * a SELECT after each commit sees exactly that commit's rows.
     */
    @Test
    void createCopyCopyDeleteProduceVersionsZeroThroughThree() {
        Executor executor = new Executor(engine);
        executor.execute("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);");
        assertEquals(0L, engine.snapshot("trips").version());
        assertEquals(0, executor.executeQuery("SELECT * FROM trips;").size());

        executor.execute("COPY trips FROM '%s';".formatted(csvFile.toString()));
        assertEquals(1L, engine.snapshot("trips").version());
        assertEquals(8, executor.executeQuery("SELECT * FROM trips;").size());

        executor.execute("COPY trips FROM '%s';".formatted(csvFile.toString()));
        assertEquals(2L, engine.snapshot("trips").version());
        assertEquals(16, executor.executeQuery("SELECT * FROM trips;").size());

        executor.execute("DELETE FROM trips WHERE city = 'Copenhagen';");
        assertEquals(3L, engine.snapshot("trips").version());
        List<Object[]> remaining = executor.executeQuery("SELECT * FROM trips;");
        assertEquals(10, remaining.size());
        assertTrue(remaining.stream().noneMatch(row -> row[0].equals("Copenhagen")));
        assertEquals(0, engine.pinnedReaderCount("trips", 3L));
    }

    /**
     * Verifies that a DELETE writes nothing to stdout and logs its summary line
     * with the committed version and partition counts.
     */
    @Test
    void deleteLogsSummaryLineWithVersionAndWritesNothingToStdout() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        Executor executor = new Executor(engine, output);

        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        String script = """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                DELETE FROM trips;
                """.formatted(csvFile.toString());
        try {
            executor.execute(script);
        } finally {
            MDC.remove("sessionId");
        }

        assertEquals(0, buffer.size(), "stdout must remain completely empty for DELETE");
        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        boolean hasSummary = logLines.stream()
                .filter(line -> line.contains("," + session + ","))
                .anyMatch(line -> line.contains(",3,") && line.contains(",DEBUG,") && line.contains(
                        "statement=DELETE table=trips rowsDeleted=8 partitionsRewritten=4 partitionsCarried=0 version=2 durationMs="));
        assertTrue(hasSummary, "expected DELETE summary line with version=2");
    }

    /**
     * Verifies that executeQuery rejects DELETE statements because it expects a single SELECT.
     */
    @Test
    void executeQueryRejectsDeleteStatement() {
        Executor executor = new Executor(engine);
        assertThrows(IllegalArgumentException.class, () -> executor.executeQuery("DELETE FROM trips;"));
    }

    /**
     * Verifies that executing a list with a null element throws IllegalArgumentException,
     * resolves operation name as UNKNOWN, and records an ERROR line without NPE.
     */
    @Test
    void executeListWithNullElementLogsErrorAndThrowsIllegalArgumentException() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        Executor executor = new Executor(engine, output);

        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        List<Statement> statementsWithNull = Collections.singletonList(null);

        try {
            assertThrows(IllegalArgumentException.class, () -> executor.execute(statementsWithNull));
        } finally {
            MDC.remove("sessionId");
        }

        assertEquals("0", MDC.get("statementNumber"));
        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        boolean hasFailedError = logLines.stream()
                .filter(line -> line.contains("," + session + ","))
                .anyMatch(line -> line.contains(",1,") && line.contains(",ERROR,")
                        && line.contains("statement_failed operation=UNKNOWN reason=statement must not be null"));
        assertTrue(hasFailedError, "expected ERROR line with statement_failed operation=UNKNOWN");
    }

    /**
     * Verifies that executeQuery, as a logging boundary, records exactly one
     * statement_failed ERROR line under statementNumber 1 when it rejects a
     * non-SELECT query, and resets statementNumber to 0 afterwards.
     */
    @Test
    void executeQueryRejectsNonSelectAndLogsStatementFailedOnce() throws IOException {
        Executor executor = new Executor(engine);
        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);

        try {
            assertThrows(IllegalArgumentException.class, () -> executor.executeQuery("DELETE FROM trips;"));
        } finally {
            MDC.remove("sessionId");
        }

        assertEquals("0", MDC.get("statementNumber"));
        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        List<String> errorLines = logLines.stream()
                .filter(line -> line.contains("," + session + ","))
                .filter(line -> line.contains(",ERROR,"))
                .toList();
        assertEquals(1, errorLines.size(), "expected exactly one ERROR line, logged at the boundary");
        assertTrue(errorLines.getFirst().contains("," + session + ",1,")
                && errorLines.getFirst().contains(
                        "statement_failed operation=SELECT reason=expected exactly one SELECT statement"),
                "expected statement_failed ERROR line with statementNumber 1");
    }

    /**
     * Verifies that execute(String) defaults a missing MDC statementNumber to 0,
     * so a script-level parse failure is logged with a numeric statementNumber
     * rather than an empty field.
     */
    @Test
    void parseFailureWithoutStatementNumberInMdcIsLoggedWithStatementNumberZero() throws IOException {
        Executor executor = new Executor(engine);
        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        MDC.remove("statementNumber");

        try {
            assertThrows(SqlParseException.class, () -> executor.execute("SELEC * FROM trips;"));
        } finally {
            MDC.remove("sessionId");
        }

        List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        boolean hasParseFailure = logLines.stream()
                .filter(line -> line.contains("," + session + ",0,"))
                .anyMatch(line -> line.contains(",ERROR,") && line.contains("failed line=1 col="));
        assertTrue(hasParseFailure, "expected parser ERROR line with statementNumber 0");
    }
}
