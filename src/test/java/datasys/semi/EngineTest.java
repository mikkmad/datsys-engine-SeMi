package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests validating CLI argument dispatching, team identification, and
 * stream routing.
 */
class EngineTest {

    @Test
    void teamName() {
        assertEquals("Team SeMi", Engine.teamName());
    }

    @Test
    void runWithNoArgumentsPrintsTeamNameAndUsage(@TempDir Path tempDir) {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outContent, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errContent, true, StandardCharsets.UTF_8);

        Engine.run(new String[0], tempDir, out, err);

        String stdout = outContent.toString(StandardCharsets.UTF_8);
        String stderr = errContent.toString(StandardCharsets.UTF_8);

        assertTrue(stdout.contains("Team SeMi"));
        assertTrue(stdout.contains("Usage:"));
        assertTrue(stderr.isEmpty());
    }

    @Test
    void runWithNullArgumentsPrintsTeamNameAndUsage(@TempDir Path tempDir) {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outContent, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errContent, true, StandardCharsets.UTF_8);

        Engine.run(null, tempDir, out, err);

        String stdout = outContent.toString(StandardCharsets.UTF_8);
        String stderr = errContent.toString(StandardCharsets.UTF_8);

        assertTrue(stdout.contains("Team SeMi"));
        assertTrue(stdout.contains("Usage:"));
        assertTrue(stderr.isEmpty());
    }

    @Test
    void runWithInvalidArgumentsWritesToErrAndLeavesOutEmpty(@TempDir Path tempDir) {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outContent, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errContent, true, StandardCharsets.UTF_8);

        Engine.run(new String[] { "-unknown", "flag" }, tempDir, out, err);

        String stdout = outContent.toString(StandardCharsets.UTF_8);
        String stderr = errContent.toString(StandardCharsets.UTF_8);

        assertTrue(stdout.isEmpty());
        assertTrue(stderr.contains("Error:"));
    }

    @Test
    void runWithNonExistentScriptWritesToErrAndLeavesOutEmpty(@TempDir Path tempDir) {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outContent, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errContent, true, StandardCharsets.UTF_8);

        Engine.run(new String[] { "-f", tempDir.resolve("missing.sql").toString() }, tempDir, out, err);

        String stdout = outContent.toString(StandardCharsets.UTF_8);
        String stderr = errContent.toString(StandardCharsets.UTF_8);

        assertTrue(stdout.isEmpty());
        assertTrue(stderr.contains("Error:"));
    }

    /**
     * Verifies that {@code -j syntax} and {@code -j optimized} are accepted before
     * or after {@code -c}.
     *
     * @param tempDir temporary data directory
     */
    @Test
    void joinOrderFlagIsAcceptedBeforeOrAfterStatementFlag(@TempDir Path tempDir) {
        String sql = "CREATE TABLE t (a LONG); SELECT * FROM t;";

        assertEquals("", runAndCaptureErr(new String[] { "-j", "syntax", "-c", sql }, tempDir.resolve("one")));
        assertEquals("", runAndCaptureErr(new String[] { "-c", sql, "-j", "optimized" }, tempDir.resolve("two")));
    }

    /**
     * Verifies that an unknown join order mode, a repeated flag, or both
     * {@code -c} and {@code -f} are rejected on standard error.
     *
     * @param tempDir temporary data directory
     */
    @Test
    void invalidJoinOrderArgumentsAreRejected(@TempDir Path tempDir) {
        String unknownMode = runAndCaptureErr(new String[] { "-j", "fastest", "-c", "SELECT * FROM t;" }, tempDir);
        assertTrue(unknownMode.contains("unknown join order mode: fastest"), unknownMode);

        assertTrue(runAndCaptureErr(new String[] { "-j", "syntax", "-j", "optimized" }, tempDir)
                .contains("invalid arguments"));
        assertTrue(runAndCaptureErr(new String[] { "-c", "SELECT 1;", "-f", "x.sql" }, tempDir)
                .contains("invalid arguments"));
        assertTrue(runAndCaptureErr(new String[] { "-c", "SELECT 1;", "-j" }, tempDir)
                .contains("invalid arguments"));
    }

    /**
     * Runs the engine and returns what it wrote to standard error.
     *
     * @param args    command-line arguments
     * @param dataDir data directory for the run
     * @return standard error output
     */
    private static String runAndCaptureErr(String[] args, Path dataDir) {
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errContent, true, StandardCharsets.UTF_8);
        Engine.run(args, dataDir, out, err);
        return errContent.toString(StandardCharsets.UTF_8);
    }
}
