package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Exercise 10 Required Test 6: the §5 join testbed through {@code ./engine -f}.
 *
 * <p>
 * Each case runs the packaged engine as a child process, in a fresh temporary
 * working directory, on a script that creates the four testbed tables, copies
 * their CSVs and runs one query. The sorted stdout must equal the sorted DuckDB
 * output committed in {@code joins/expected/queryN.csv}. CI has no DuckDB, so
 * the golden files are regenerated with {@code scripts/joins-duckdb-oracle.sh},
 * which reads the same {@code joins/queries/queryN.sql} files as this test.
 * Rows are compared sorted because neither the hash join nor DuckDB promises
 * an output order.
 *
 * <p>
 * Exercise 11 Required Test 6, the metamorphic test: the three- and four-table
 * queries run once under {@code -j syntax} and once under {@code -j optimized},
 * each as its own process in its own working directory, so each run writes its
 * own {@code logs/engine.log}. The sorted rows must be identical while the join
 * order, read from the log, must differ, and every executed join must have
 * left a line with {@code estRows} and {@code rowsOut}.
 */
class JoinTestbedIT {

    // --- Constants ---
    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();
    private static final Path JOINS_DIRECTORY = REPOSITORY_ROOT.resolve("src/test/resources/joins");
    private static final long ENGINE_TIMEOUT_IN_SECONDS = 60;
    private static final List<String> TESTBED_TABLES = List.of("orders", "customers", "products", "cities");
    private static final String SCHEMA = """
            CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
            CREATE TABLE products (p_name STRING, p_price DOUBLE);
            CREATE TABLE customers (c_name STRING, c_city STRING);
            CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);
            """;

    // --- Engine Log (following conventions from Exercise 1 §3) ---
    private static final Path ENGINE_LOG = Path.of("logs", "engine.log");
    private static final int LOG_FIELD_COUNT = 7;
    private static final int STATEMENT_NUMBER_FIELD = 2;
    private static final int MESSAGE_FIELD = 6;
    private static final Pattern JOIN_ORDER_LINE =
            Pattern.compile("statement=SELECT joinOrder=(\\S+) mode=(\\S+) estRows=\\d+");
    private static final Pattern JOIN_LINE =
            Pattern.compile("operator=HashJoin join=(\\d+) tables=\\S+ estRows=\\d+ .* rowsOut=\\d+");

    /**
     * Verifies that one testbed query through {@code ./engine -f} returns the
     * expected number of rows and, sorted, exactly the DuckDB rows.
     *
     * @param queryName        name of the query and golden file, e.g.
     *                         {@code query1}
     * @param expectedRowCount row count stated by Exercise 10 §5
     * @param workDirectory    fresh working directory for the engine process
     * @throws IOException          if a file cannot be read or written
     * @throws InterruptedException if waiting for the engine is interrupted
     */
    @ParameterizedTest(name = "{0} returns {1} rows equal to DuckDB")
    @CsvSource({ "query1, 4", "query2, 4", "query3, 4", "query4, 3" })
    void testbedQueryMatchesDuckDbSorted(String queryName, int expectedRowCount, @TempDir Path workDirectory)
            throws IOException, InterruptedException {
        Path script = writeScript(workDirectory, readQuery(queryName));

        List<String> engineRows = sorted(runEngine(workDirectory, script));
        List<String> duckDbRows = sorted(Files.readAllLines(
                JOINS_DIRECTORY.resolve("expected").resolve(queryName + ".csv"), StandardCharsets.UTF_8));

        assertEquals(expectedRowCount, duckDbRows.size(), "golden file row count");
        assertEquals(duckDbRows, engineRows);
    }

    /**
     * Exercise 11 Required Test 6: the optimizer rearranges the plan, the result
     * must not change. Runs one testbed query under both join order modes and
     * asserts identical sorted rows, different join orders in the logs, and one
     * log line with estimate and actual output per executed join.
     *
     * @param queryName     name of the query file, e.g. {@code query3}
     * @param tableCount    number of tables in its {@code FROM} list
     * @param workDirectory fresh directory; each mode runs in a subdirectory
     * @throws IOException          if a file cannot be read or written
     * @throws InterruptedException if waiting for the engine is interrupted
     */
    @ParameterizedTest(name = "{0}: same rows under -j syntax and -j optimized, different plans")
    @CsvSource({ "query2, 3", "query3, 4", "query4, 3" })
    void optimizedAndSyntaxPlansDifferButReturnIdenticalRows(String queryName, int tableCount,
            @TempDir Path workDirectory) throws IOException, InterruptedException {
        String query = readQuery(queryName);
        ModeRun syntax = runInMode(workDirectory, query, "syntax");
        ModeRun optimized = runInMode(workDirectory, query, "optimized");

        assertFalse(syntax.rows().isEmpty(), "testbed query returned no rows");
        assertEquals(syntax.rows(), optimized.rows());
        assertNotEquals(syntax.joinOrder(), optimized.joinOrder(),
                "both modes chose the same plan, so the test would compare a plan with itself");
        assertEveryJoinLogged(syntax, tableCount);
        assertEveryJoinLogged(optimized, tableCount);
    }

    /**
     * Runs the testbed script with one query under one join order mode, in its
     * own subdirectory, and reads the plan back from that run's log.
     *
     * @param workDirectory parent directory of the mode's working directory
     * @param query         the SELECT statement under test
     * @param mode          {@code syntax} or {@code optimized}
     * @return the run's sorted rows, join order and HashJoin lines
     * @throws IOException          if a file cannot be read or written
     * @throws InterruptedException if waiting for the engine is interrupted
     */
    private static ModeRun runInMode(Path workDirectory, String query, String mode)
            throws IOException, InterruptedException {
        Path modeDirectory = Files.createDirectories(workDirectory.resolve(mode));
        Path script = writeScript(modeDirectory, query);
        List<String> rows = sorted(runEngine(modeDirectory, script, "-j", mode));

        List<String[]> logLines = readLog(modeDirectory);
        List<String[]> joinOrderLines = logLines.stream()
                .filter(fields -> JOIN_ORDER_LINE.matcher(fields[MESSAGE_FIELD]).matches())
                .toList();
        assertEquals(1, joinOrderLines.size(), "expected one joinOrder line under -j " + mode);
        Matcher joinOrder = JOIN_ORDER_LINE.matcher(joinOrderLines.getFirst()[MESSAGE_FIELD]);
        assertTrue(joinOrder.matches());
        assertEquals(mode, joinOrder.group(2));

        String statementNumber = joinOrderLines.getFirst()[STATEMENT_NUMBER_FIELD];
        List<String> joinLines = logLines.stream()
                .filter(fields -> fields[STATEMENT_NUMBER_FIELD].equals(statementNumber))
                .map(fields -> fields[MESSAGE_FIELD])
                .filter(message -> message.startsWith("operator=HashJoin "))
                .toList();
        return new ModeRun(rows, joinOrder.group(1), joinLines);
    }

    /**
     * Asserts that every join of the plan left exactly one log line with its
     * estimate and actual output, numbered {@code 1..tableCount-1}.
     *
     * @param run        one mode's run
     * @param tableCount number of tables in the query
     */
    private static void assertEveryJoinLogged(ModeRun run, int tableCount) {
        List<Integer> joinNumbers = new ArrayList<>();
        for (String joinLine : run.joinLines()) {
            Matcher matcher = JOIN_LINE.matcher(joinLine);
            assertTrue(matcher.matches(), () -> "join line without estRows and rowsOut: " + joinLine);
            joinNumbers.add(Integer.parseInt(matcher.group(1)));
        }
        assertEquals(IntStream.rangeClosed(1, tableCount - 1).boxed().toList(),
                joinNumbers.stream().sorted().toList(), "one log line per executed join");
    }

    /**
     * Reads a run's CSV log, split into the seven fields of Exercise 1 §3.
     *
     * @param workDirectory working directory the engine process ran in
     * @return all log lines of that run
     * @throws IOException if the log cannot be read
     */
    private static List<String[]> readLog(Path workDirectory) throws IOException {
        return Files.readAllLines(workDirectory.resolve(ENGINE_LOG), StandardCharsets.UTF_8).stream()
                .map(line -> line.split(",", LOG_FIELD_COUNT))
                .filter(fields -> fields.length == LOG_FIELD_COUNT)
                .toList();
    }

    /**
     * Reads a testbed query, the same file the DuckDB oracle script reads.
     *
     * @param queryName name of the query file without extension
     * @return the SELECT statement
     * @throws IOException if the file cannot be read
     */
    private static String readQuery(String queryName) throws IOException {
        return Files.readString(JOINS_DIRECTORY.resolve("queries").resolve(queryName + ".sql"),
                StandardCharsets.UTF_8);
    }

    /**
     * Writes the testbed script: the four tables, a COPY per table, then the
     * query.
     *
     * @param workDirectory directory to write the script into
     * @param query         the SELECT statement under test
     * @return path to the written script
     * @throws IOException if the script cannot be written
     */
    private static Path writeScript(Path workDirectory, String query) throws IOException {
        StringBuilder script = new StringBuilder(SCHEMA);
        for (String table : TESTBED_TABLES) {
            String csvPath = JOINS_DIRECTORY.resolve(table + ".csv").toString().replace("\\", "/");
            script.append("COPY ").append(table).append(" FROM '").append(csvPath).append("';\n");
        }
        script.append(query);
        Path scriptPath = workDirectory.resolve("testbed.sql");
        Files.writeString(scriptPath, script.toString(), StandardCharsets.UTF_8);
        return scriptPath;
    }

    /**
     * Runs {@code ./engine [arguments] -f script} in the given working directory
     * and returns its stdout lines. Output streams go to files so the child never
     * blocks on a full pipe.
     *
     * @param workDirectory   working directory of the engine process
     * @param script          script to execute
     * @param engineArguments extra CLI arguments, e.g. {@code -j syntax}
     * @return the non-empty stdout lines
     * @throws IOException          if the process cannot start or its output
     *                              cannot be read
     * @throws InterruptedException if waiting for the engine is interrupted
     */
    private static List<String> runEngine(Path workDirectory, Path script, String... engineArguments)
            throws IOException, InterruptedException {
        Path stdout = workDirectory.resolve("stdout.csv");
        Path stderr = workDirectory.resolve("stderr.log");
        List<String> command = new ArrayList<>(List.of(REPOSITORY_ROOT.resolve("engine").toString()));
        command.addAll(List.of(engineArguments));
        command.addAll(List.of("-f", script.toString()));
        Process engine = new ProcessBuilder(command)
                .directory(workDirectory.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();

        boolean finished = engine.waitFor(ENGINE_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            engine.destroyForcibly();
        }
        assertTrue(finished, "engine did not finish within " + ENGINE_TIMEOUT_IN_SECONDS + " s");
        assertEquals(0, engine.exitValue(), () -> "engine failed: " + readQuietly(stderr));
        return Files.readAllLines(stdout, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /**
     * Returns the rows in natural string order, the equivalent of
     * {@code LC_ALL=C sort}.
     *
     * @param rows CSV rows
     * @return a sorted copy
     */
    private static List<String> sorted(List<String> rows) {
        return rows.stream().sorted().toList();
    }

    /**
     * Reads a diagnostic file for an assertion message.
     *
     * @param file file to read
     * @return its content, or a note explaining why it could not be read
     *         (documented fallback: the message is only diagnostic)
     */
    private static String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "<could not read " + file + ": " + exception.getMessage() + ">";
        }
    }

    /**
     * One engine run under one join order mode, as read from its stdout and log.
     *
     * @param rows      sorted result rows
     * @param joinOrder the {@code joinOrder=} value of the SELECT, e.g.
     *                  {@code products+orders+customers}
     * @param joinLines the SELECT's {@code operator=HashJoin} log messages
     */
    private record ModeRun(List<String> rows, String joinOrder, List<String> joinLines) {
    }
}
