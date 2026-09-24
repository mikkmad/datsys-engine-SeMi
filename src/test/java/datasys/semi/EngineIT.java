package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static datasys.semi.UtilsTest.copyResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end integration tests for the SQL front door CLI entry point (Exercise
 * 4 §6 Test 6).
 *
 * <p>
 * Validates script execution byte-for-byte CSV outputs, single statement
 * queries,
 * stdout cleanliness on failures, and stderr error routing.
 */
class EngineIT {

    // --- Stream Redirection State ---
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream capturedOut;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void setUpStreams() {
        originalOut = System.out;
        originalErr = System.err;
        capturedOut = new ByteArrayOutputStream();
        capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.clearProperty("semi.data.dir");
    }

    @Test
    void executesScriptEndToEndAndMatchesCsvByteForByte(@TempDir Path tempDir) throws IOException {
        Path csvPath = copyResource(tempDir, "trips.csv");
        Path dataDir = tempDir.resolve("data");
        System.setProperty("semi.data.dir", dataDir.toString());

        Path scriptPath = tempDir.resolve("query.sql");
        String script = """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                SELECT * FROM trips WHERE distance > 100;
                """.formatted(csvPath.toString().replace("\\", "/"));
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8);

        Engine.main(new String[] { "-f", scriptPath.toString() });

        List<String> expectedLines = List.of(
                "Aarhus,187,301.0",
                "Copenhagen,140,210.0",
                "Aalborg,210,340.5",
                "Esbjerg,299,450.25");
        String expectedCsv = String.join(System.lineSeparator(), expectedLines) + System.lineSeparator();
        byte[] expectedBytes = expectedCsv.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = capturedOut.toByteArray();

        assertArrayEquals(expectedBytes, actualBytes);
    }

    @Test
    void failingScriptWritesToStderrAndLeavesStdoutClean(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        System.setProperty("semi.data.dir", dataDir.toString());

        Path scriptPath = tempDir.resolve("failing.sql");
        String script = "SELECT * FROM non_existent_table;";
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8);

        Engine.main(new String[] { "-f", scriptPath.toString() });

        byte[] actualBytes = capturedOut.toByteArray();
        assertEquals(0, actualBytes.length);

        String errString = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(errString.contains("Error:"));
        assertTrue(errString.contains("non_existent_table"));
    }

    @Test
    void singleStatementExecutionProducesCsv(@TempDir Path tempDir) throws IOException {
        Path csvPath = copyResource(tempDir, "trips.csv");
        Path dataDir = tempDir.resolve("data");
        System.setProperty("semi.data.dir", dataDir.toString());

        Path setupScript = tempDir.resolve("setup.sql");
        String script = """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                """.formatted(csvPath.toString().replace("\\", "/"));
        Files.writeString(setupScript, script, StandardCharsets.UTF_8);

        Engine.main(new String[] { "-f", setupScript.toString() });
        capturedOut.reset();

        Engine.main(new String[] { "SELECT * FROM trips WHERE distance > 200;" });

        List<String> expectedLines = List.of(
                "Aalborg,210,340.5",
                "Esbjerg,299,450.25");
        String expectedCsv = String.join(System.lineSeparator(), expectedLines) + System.lineSeparator();
        byte[] expectedBytes = expectedCsv.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = capturedOut.toByteArray();

        assertArrayEquals(expectedBytes, actualBytes);
    }

    @Test
    void noArgumentsPrintsTeamNameAndUsage(@TempDir Path tempDir) {
        System.setProperty("semi.data.dir", tempDir.resolve("data").toString());

        Engine.main(new String[0]);

        String outString = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(outString.contains("Team SeMi"));
        assertTrue(outString.contains("Usage:"));
    }

    @Test
    void invalidArgumentsWritesToStderrAndLeavesStdoutClean(@TempDir Path tempDir) {
        System.setProperty("semi.data.dir", tempDir.resolve("data").toString());

        Engine.main(new String[] { "-x", "foo.sql" });

        byte[] actualBytes = capturedOut.toByteArray();
        assertEquals(0, actualBytes.length);

        String errString = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(errString.contains("Error:"));
    }
}
