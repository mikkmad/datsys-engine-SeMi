package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
        Path script = writeScript(workDirectory, Files.readString(
                JOINS_DIRECTORY.resolve("queries").resolve(queryName + ".sql"), StandardCharsets.UTF_8));

        List<String> engineRows = sorted(runEngine(workDirectory, script));
        List<String> duckDbRows = sorted(Files.readAllLines(
                JOINS_DIRECTORY.resolve("expected").resolve(queryName + ".csv"), StandardCharsets.UTF_8));

        assertEquals(expectedRowCount, duckDbRows.size(), "golden file row count");
        assertEquals(duckDbRows, engineRows);
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
     * Runs {@code ./engine -f script} in the given working directory and returns
     * its stdout lines. Output streams go to files so the child never blocks on
     * a full pipe.
     *
     * @param workDirectory working directory of the engine process
     * @param script        script to execute
     * @return the non-empty stdout lines
     * @throws IOException          if the process cannot start or its output
     *                              cannot be read
     * @throws InterruptedException if waiting for the engine is interrupted
     */
    private static List<String> runEngine(Path workDirectory, Path script) throws IOException, InterruptedException {
        Path stdout = workDirectory.resolve("stdout.csv");
        Path stderr = workDirectory.resolve("stderr.log");
        Process engine = new ProcessBuilder(REPOSITORY_ROOT.resolve("engine").toString(), "-f", script.toString())
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
}
