package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for the engine's logging promise (Exercise 5 §6 Test 1).
 *
 * <p>
 * Drives a failing statement through the front door ({@link Engine#main}) and
 * then reads the live
 * CSV log {@code logs/engine.log} to prove that the failure left an
 * {@code ERROR} line attributed
 * to the failing statement. The log file is shared with every other run, so the
 * test locates its
 * own session through a uniquely named missing table instead of relying on line
 * positions.
 */
class EngineLogIT {

    // --- Log File Layout ---
    private static final Path ENGINE_LOG_PATH = Path.of("logs", "engine.log");
    private static final int LOG_COLUMN_COUNT = 7;

    // --- Failing Script Shape ---
    private static final long FAILING_STATEMENT_NUMBER = 2;

    /**
     * Cleans up the configured system property after each test run.
     */
    @AfterEach
    void clearDataDirectoryProperty() {
        System.clearProperty("semi.data.dir");
    }

    /**
     * Verifies that a failing statement executed via the front door records an
     * ERROR
     * log line in the engine log with the active statement number and enriched
     * error message.
     *
     * @param tempDir temporary directory for storage engine data
     * @throws IOException if writing the script or reading the engine log fails
     */
    @Test
    void failingStatementLeavesErrorLineInEngineLog(@TempDir Path tempDir) throws IOException {
        System.setProperty("semi.data.dir", tempDir.resolve("data").toString());
        String missingTableName = "missing_" + UUID.randomUUID().toString().replace("-", "");
        Path scriptPath = writeFailingScript(tempDir, missingTableName);

        Engine.main(new String[] { "-f", scriptPath.toString() });

        List<String> rawLogLines = readEngineLog();
        String sessionId = findSessionIdOfError(rawLogLines, missingTableName);
        List<LogLine> sessionLines = parseSessionLines(rawLogLines, sessionId);
        boolean hasErrorForFailingStatement = sessionLines.stream()
                .filter(line -> line.logLevel().equals("ERROR"))
                .anyMatch(line -> line.statementNumber() == FAILING_STATEMENT_NUMBER);

        assertTrue(hasErrorForFailingStatement,
                "expected an ERROR line for statement " + FAILING_STATEMENT_NUMBER + " in session " + sessionId);

        LogLine executorError = sessionLines.stream()
                .filter(line -> line.logLevel().equals("ERROR") && line.statementNumber() == FAILING_STATEMENT_NUMBER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing error line for statement " + FAILING_STATEMENT_NUMBER));
        assertEquals("statement_failed operation=SELECT reason=unknown table: " + missingTableName,
                executorError.logMessage());
    }

    /**
     * Verifies that executing an unfiltered SELECT logs decision=READ
     * reason=noPredicate
     * for all partitions during the query execution.
     *
     * @param tempDir temporary directory for storage engine data
     * @throws IOException if writing the script or reading the engine log fails
     */
    @Test
    void unfilteredSelectLogsNoPredicateDecisionLines(@TempDir Path tempDir) throws IOException {
        System.setProperty("semi.data.dir", tempDir.resolve("data").toString());
        String tableName = "trips_" + UUID.randomUUID().toString().replace("-", "");
        Path csvPath = UtilsTest.copyResource(tempDir, "trips.csv");
        Path scriptPath = tempDir.resolve("unfiltered.sql");
        String script = """
                CREATE TABLE %s (city STRING, distance LONG, price DOUBLE);
                COPY %s FROM '%s';
                SELECT * FROM %s;
                """.formatted(tableName, tableName, csvPath.toString(), tableName);
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8);

        Engine.main(new String[] { "-f", scriptPath.toString() });

        List<String> rawLogLines = readEngineLog();
        String sessionId = findSessionIdOfMarker(rawLogLines, tableName);
        List<LogLine> sessionLines = parseSessionLines(rawLogLines, sessionId);

        boolean hasNoPredicateDecision = sessionLines.stream()
                .filter(line -> line.statementNumber() == 3)
                .anyMatch(line -> line.logMessage().contains("table=" + tableName)
                        && line.logMessage().contains("decision=READ reason=noPredicate"));

        assertTrue(hasNoPredicateDecision, "expected decision=READ reason=noPredicate in statement 3");
    }

    /**
     * Verifies that queries containing special characters (commas, double quotes,
     * newlines)
     * maintain strict 7-column CSV schema integrity and contain sanitized log
     * messages.
     *
     * @param tempDir temporary directory for storage engine data
     * @throws IOException if writing the script or reading the engine log fails
     */
    @Test
    void specialCharactersInPredicatesAndErrorsPreserveCsvIntegrity(@TempDir Path tempDir) throws IOException {
        System.setProperty("semi.data.dir", tempDir.resolve("data").toString());
        String tableName = "special_" + UUID.randomUUID().toString().replace("-", "");
        Path csvPath = UtilsTest.copyResource(tempDir, "trips.csv");
        Path scriptPath = tempDir.resolve("special.sql");
        String script = """
                CREATE TABLE %s (city STRING, distance LONG, price DOUBLE);
                COPY %s FROM '%s';
                SELECT * FROM %s WHERE city = 'New, "York"\\n';
                SELECT * FROM %s WHERE nonexistent_col = 'val';
                """.formatted(tableName, tableName, csvPath.toString(), tableName, tableName);
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8);

        Engine.main(new String[] { "-f", scriptPath.toString() });

        List<String> rawLogLines = readEngineLog();
        String sessionId = findSessionIdOfMarker(rawLogLines, tableName);
        List<LogLine> sessionLines = parseSessionLines(rawLogLines, sessionId);

        assertFalse(sessionLines.isEmpty(), "expected session lines to be recorded");

        for (LogLine line : sessionLines) {
            assertFalse(line.logMessage().contains(","), "log message must not contain unescaped commas: " + line);
            assertFalse(line.logMessage().contains("\""), "log message must not contain double quotes: " + line);
            assertFalse(line.logMessage().contains("\n"), "log message must not contain newlines: " + line);
            assertFalse(line.logMessage().contains("\r"), "log message must not contain carriage returns: " + line);
        }
    }

    /**
     * Writes a two-statement script whose second statement fails on an unknown
     * table.
     *
     * @param directory        directory to place the script in
     * @param missingTableName unique table name that does not exist in the catalog
     * @return path of the written script
     * @throws IOException if the script cannot be written
     */
    private static Path writeFailingScript(Path directory, String missingTableName) throws IOException {
        Path scriptPath = directory.resolve("failing.sql");
        String script = """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                SELECT * FROM %s;
                """.formatted(missingTableName);
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8);
        return scriptPath;
    }

    /**
     * Reads every raw line of the live engine log.
     *
     * @return raw log lines in file order
     * @throws IOException if the log file cannot be read
     */
    private static List<String> readEngineLog() throws IOException {
        assertTrue(Files.exists(ENGINE_LOG_PATH), "expected log file at " + ENGINE_LOG_PATH.toAbsolutePath());
        return Files.readAllLines(ENGINE_LOG_PATH, StandardCharsets.UTF_8);
    }

    /**
     * Finds the session that logged an {@code ERROR} line mentioning the given
     * marker.
     *
     * @param rawLogLines raw log lines to search
     * @param marker      text unique to the failing statement, such as its missing
     *                    table name
     * @return session id of the most recent matching {@code ERROR} line
     */
    private static String findSessionIdOfError(List<String> rawLogLines, String marker) {
        List<LogLine> matchingErrors = rawLogLines.stream()
                .filter(rawLine -> rawLine.contains(",ERROR,") && rawLine.contains(marker))
                .map(LogLine::parse)
                .toList();
        assertFalse(matchingErrors.isEmpty(), "expected an ERROR line mentioning " + marker);
        return matchingErrors.getLast().sessionId();
    }

    /**
     * Finds the session that logged any line mentioning the given marker.
     *
     * @param rawLogLines raw log lines to search
     * @param marker      text unique to the session, such as a table name
     * @return session id of the most recent matching line
     */
    private static String findSessionIdOfMarker(List<String> rawLogLines, String marker) {
        List<LogLine> matchingLines = rawLogLines.stream()
                .filter(rawLine -> rawLine.contains(marker))
                .map(LogLine::parse)
                .toList();
        assertFalse(matchingLines.isEmpty(), "expected a log line mentioning " + marker);
        return matchingLines.getLast().sessionId();
    }

    /**
     * Parses the lines of one session, requiring each to match the CSV log schema.
     *
     * <p>
     * Lines of other sessions are ignored, since the shared log also holds output
     * of unrelated runs.
     *
     * @param rawLogLines raw log lines to filter
     * @param sessionId   session whose lines are returned
     * @return parsed lines of the session in file order
     */
    private static List<LogLine> parseSessionLines(List<String> rawLogLines, String sessionId) {
        return rawLogLines.stream()
                .filter(rawLine -> rawLine.contains("," + sessionId + ","))
                .map(LogLine::parse)
                .toList();
    }

    /**
     * One row of the CSV log schema
     * {@code timestamp,sessionId,statementNumber,threadId,logLevel,className,logMessage}.
     *
     * @param sessionId       engine run identifier
     * @param statementNumber statement sequence number, zero outside a statement
     * @param logLevel        {@code DEBUG} or {@code ERROR}
     * @param logMessage      free-text message without commas
     */
    private record LogLine(String sessionId, long statementNumber, String logLevel, String logMessage) {

        /**
         * Parses one raw log line into its schema columns.
         *
         * @param rawLine one line of {@code logs/engine.log}
         * @return the parsed log line
         * @throws NumberFormatException if the statement number column is not numeric
         */
        static LogLine parse(String rawLine) {
            String[] columns = rawLine.split(",", -1);
            assertEquals(LOG_COLUMN_COUNT, columns.length, "malformed log line: " + rawLine);
            return new LogLine(columns[1], Long.parseLong(columns[2]), columns[4], columns[6]);
        }
    }
}
