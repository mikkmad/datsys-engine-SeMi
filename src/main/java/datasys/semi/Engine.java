package datasys.semi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class Engine {

    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    public static void main(String[] args) throws Exception {
        // Logging context
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");

        // Workspace setup
        Path demoDirectory = Path.of(System.getProperty("user.dir"), "semi-demo");
        deleteDirectory(demoDirectory);
        Files.createDirectories(demoDirectory);

        // Input CSV resource resolution
        Path csvFile = resolveCsvFile();

        // StorageEngine initialization and schema definition
        StorageEngine storage = new StorageEngine(demoDirectory, 2);
        storage.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));

        // Bulk load CSV into partitioned binary storage
        storage.copyFile("trips", csvFile.toString());

        // Golden queries
        System.out.println("distance > 100: " + format(storage.select(
                "trips", "distance", Comparison.GREATER_THAN, 100L)));

        System.out.println("city = Copenhagen: " + format(storage.select(
                "trips", "city", Comparison.EQUALS, "Copenhagen")));

        System.out.println("price < 50.0: " + format(storage.select(
                "trips", "price", Comparison.LESS_THAN, 50.0)));

        LOGGER.debug("engine stopped");
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String format(List<Object[]> rows) {
        return rows.stream().map(row -> Arrays.toString(row)).toList().toString();
    }

    private static Path resolveCsvFile() throws Exception {
        Path directPath = Path.of("src", "main", "resources", "trips.csv");
        if (Files.exists(directPath)) {
            return directPath;
        }

        var resource = Engine.class.getResource("/trips.csv");
        if (resource != null) {
            return Path.of(resource.toURI());
        }

        throw new IllegalStateException("Could not locate src/main/resources/trips.csv");
    }

    String teamName() {
        return "Team SeMi";
    }
}
