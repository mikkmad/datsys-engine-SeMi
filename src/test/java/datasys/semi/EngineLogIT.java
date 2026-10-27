package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static datasys.semi.UtilsTest.copyResource;

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
 * Integration test for the engine's logging promise (Exercise 5 §6 Test 1) and
 * versioned statement summary logging (Exercise 7 §2).
 *
 * <p>
 * Drives statements through the front door ({@link Engine#main}) and then reads
 * the live
 * CSV log {@code logs/engine.log}. The log file is shared with every other run,
 * so tests locate
 * their own session through uniquely named tables instead of relying on line
 * positions.
 */
class EngineLogIT {

        // --- Log File Layout ---
        private static final Path ENGINE_LOG_PATH = Path.of("logs", "engine.log");
        private static final int LOG_COLUMN_COUNT = 7;

        // --- Failing Script Shape ---
        private static final long FAILING_STATEMENT_NUMBER = 2;

        @AfterEach
        void clearDataDirectoryProperty() {
                System.clearProperty("semi.data.dir");
        }

        @Test
        void failingStatementLeavesErrorLineInEngineLog(@TempDir Path tempDir) throws IOException {
                System.setProperty("semi.data.dir", tempDir.resolve("data").toString());
                String missingTableName = "missing_" + UUID.randomUUID().toString().replace("-", "");
                Path scriptPath = writeFailingScript(tempDir, missingTableName);

                Engine.main(new String[] { "-f", scriptPath.toString() });

                List<String> rawLogLines = readEngineLog();
                String sessionId = findSessionIdOfError(rawLogLines, missingTableName);
                boolean hasErrorForFailingStatement = parseSessionLines(rawLogLines, sessionId).stream()
                                .filter(line -> line.logLevel().equals("ERROR"))
                                .anyMatch(line -> line.statementNumber() == FAILING_STATEMENT_NUMBER);

                assertTrue(hasErrorForFailingStatement,
                                "expected an ERROR line for statement " + FAILING_STATEMENT_NUMBER + " in session "
                                                + sessionId);

                LogLine statementError = parseSessionLines(rawLogLines, sessionId).stream()
                                .filter(line -> line.logLevel().equals("ERROR")
                                                && line.statementNumber() == FAILING_STATEMENT_NUMBER
                                                && line.logMessage().startsWith("statement_failed"))
                                .findFirst()
                                .orElseThrow();
                assertEquals("statement_failed operation=SELECT reason=unknown table: " + missingTableName,
                                statementError.logMessage());
        }

        /**
         * Verifies that statement summary lines in the engine log carry the committed
         * or read version for CREATE_TABLE, COPY, and SELECT.
         */
        @Test
        void summaryLinesCarryVersion(@TempDir Path tempDir) throws IOException {
                System.setProperty("semi.data.dir", tempDir.resolve("data").toString());
                Path csvPath = copyResource(tempDir, "trips.csv");
                String tableName = "trips_" + UUID.randomUUID().toString().replace("-", "");
                Path scriptPath = tempDir.resolve("versioned.sql");

                String script = """
                                CREATE TABLE %s (city STRING, distance LONG, price DOUBLE);
                                COPY %s FROM '%s';
                                SELECT * FROM %s WHERE distance > 100;
                                """.formatted(tableName, tableName, csvPath.toString().replace("\\", "/"), tableName);
                Files.writeString(scriptPath, script, StandardCharsets.UTF_8);

                Engine.main(new String[] { "-f", scriptPath.toString() });

                List<String> rawLogLines = readEngineLog();
                String sessionId = rawLogLines.stream()
                                .filter(rawLine -> rawLine.contains(tableName))
                                .map(LogLine::parse)
                                .toList()
                                .getLast()
                                .sessionId();

                List<String> messages = parseSessionLines(rawLogLines, sessionId).stream()
                                .map(LogLine::logMessage)
                                .toList();

                assertTrue(messages.stream().anyMatch(
                                msg -> msg.startsWith("statement=CREATE_TABLE table=" + tableName
                                                + " version=0 durationMs=")),
                                "expected CREATE_TABLE summary with version=0");
                assertTrue(messages.stream().anyMatch(
                                msg -> msg.startsWith(
                                                "statement=COPY table=" + tableName
                                                                + " file=" + csvPath
                                                                                + " rows=8 partitions=1 version=1 durationMs=")),
                                "expected COPY summary with version=1");
                assertTrue(messages.stream().anyMatch(
                                msg -> msg.startsWith(
                                                "statement=SELECT table=" + tableName
                                                                + " rowsOut=4 partitionsRead=1 partitionsPruned=0 version=1 durationMs=")),
                                "expected SELECT summary with version=1");
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
