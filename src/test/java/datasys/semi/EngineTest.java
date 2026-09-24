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
        assertEquals("Team SeMi", new Engine().teamName());
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
}
