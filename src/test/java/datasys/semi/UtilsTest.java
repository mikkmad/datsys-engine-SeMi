package datasys.semi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Common test utility methods shared across unit and integration test suites.
 */
public final class UtilsTest {

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private UtilsTest() {
    }

    /**
     * Copies a test resource to a target directory.
     *
     * @param directory destination directory
     * @param filename  name of the resource file in resources or source directory
     * @return path to the copied file in the destination directory
     * @throws IOException if copying fails or file cannot be found
     */
    public static Path copyResource(Path directory, String filename) throws IOException {
        Path target = directory.resolve(filename);
        try (InputStream stream = UtilsTest.class.getResourceAsStream("/" + filename)) {
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
