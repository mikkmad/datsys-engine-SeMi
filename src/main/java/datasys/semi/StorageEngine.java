package datasys.semi;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class StorageEngine {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private static final byte[] MAGIC = { 'S', 'E', 'M', 'I' };
    private static final short FORMAT_VERSION = 1;
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 1024;

    // --- Directory Paths ---
    private final Path dataDirectory;
    private final Path catalogsDirectory;
    private final Path dataFilesDirectory;

    // --- Partition Sizing & State ---
    private final int maxRowsPerPartition;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, Catalog> catalogs = new HashMap<>();

    // --- Observable Metrics ---
    private ScanStats lastScanStats = new ScanStats(0, 0, 0);

    public StorageEngine(Path dataDirectory) {
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    public StorageEngine(Path dataDirectory, int maxRowsPerPartition) {
        if (dataDirectory == null || maxRowsPerPartition <= 0) {
            throw new IllegalArgumentException("data directory and partition size must be valid");
        }

        this.dataDirectory = dataDirectory;
        this.catalogsDirectory = dataDirectory.resolve("catalogs");
        this.dataFilesDirectory = dataDirectory.resolve("data");
        this.maxRowsPerPartition = maxRowsPerPartition;

        try {
            Files.createDirectories(catalogsDirectory);
            Files.createDirectories(dataFilesDirectory);
            loadCatalogs();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not open storage directory " + dataDirectory, exception);
        }
    }

    public void createTable(String tableName, List<ColumnSpec> columns) {
        log("api=createTable table=%s".formatted(tableName));
        requireTableName(tableName);

        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("column list must not be empty");
        }
        if (catalogs.containsKey(tableName)) {
            throw new IllegalArgumentException("table already exists: " + tableName);
        }

        List<Column> schema = new ArrayList<>();
        for (ColumnSpec column : columns) {
            if (column == null || column.name() == null || column.name().isBlank() || column.type() == null
                    || schema.stream().anyMatch(existing -> existing.name.equals(column.name()))) {
                throw new IllegalArgumentException("invalid or duplicate column");
            }
            schema.add(new Column(column.name(), column.type().name()));
        }

        Catalog catalog = new Catalog(tableName, schema, new ArrayList<>());
        writeCatalog(catalog);
        catalogs.put(tableName, catalog);

        log("table=%s columns=%d".formatted(tableName, schema.size()));
    }

    public void copyFile(String tableName, String csvFilePath) {
        log("api=copyFile table=%s file=%s".formatted(tableName, csvFilePath));
        Catalog catalog = requireCatalog(tableName);

        if (!catalog.partitions.isEmpty()) {
            throw new UnsupportedOperationException("copying more than one file per table is not supported");
        }

        long started = System.nanoTime();
        List<Object[]> rows = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(Path.of(csvFilePath), StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                rows.add(parseCsvLine(line, csvFilePath, lineNumber, catalog.schema));
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read " + csvFilePath, exception);
        }

        List<Partition> partitions = new ArrayList<>();
        List<Path> temporaryFiles = new ArrayList<>();

        try {
            for (int start = 0,
                    partitionNumber = 0; start < rows.size(); start += maxRowsPerPartition, partitionNumber++) {
                int end = Math.min(start + maxRowsPerPartition, rows.size());
                List<Object[]> partitionRows = rows.subList(start, end);

                Path finalPath = dataFilesDirectory.resolve(tableName)
                        .resolve("partition-%d.dat".formatted(partitionNumber));
                Files.createDirectories(finalPath.getParent());

                Path temporaryPath = finalPath.resolveSibling(finalPath.getFileName() + ".tmp-" + UUID.randomUUID());
                temporaryFiles.add(temporaryPath);

                writePartition(temporaryPath, partitionRows, catalog.schema);
                moveAtomically(temporaryPath, finalPath);

                partitions.add(new Partition(
                        dataDirectory.relativize(finalPath).toString(),
                        partitionRows.size(),
                        computeStatistics(partitionRows, catalog.schema, tableName, partitionNumber)));
            }

            Catalog updated = new Catalog(catalog.table, catalog.schema, partitions);
            writeCatalog(updated);
            catalogs.put(tableName, updated);

            log("table=%s file=%s rows=%d partitions=%d durationMs=%d".formatted(
                    tableName, Path.of(csvFilePath).getFileName(), rows.size(), partitions.size(),
                    elapsedMillis(started)));

        } catch (IOException exception) {
            cleanupTemporaryFiles(temporaryFiles);
            throw new IllegalStateException("Could not write copy of " + csvFilePath, exception);
        }
    }

    public List<Object[]> select(String tableName, String columnName, Comparison comparison, Object constant) {
        log("api=select table=%s column=%s comparison=%s const=%s".formatted(tableName, columnName, comparison,
                constant));
        Catalog catalog = requireCatalog(tableName);

        int predicateColumn = columnIndex(catalog.schema, columnName);
        Column column = catalog.schema.get(predicateColumn);
        validateConstant(column.type, constant);

        long started = System.nanoTime();
        int read = 0;
        int pruned = 0;

        List<Object[]> result = new ArrayList<>();
        ColumnType type = ColumnType.valueOf(column.type);

        for (int partitionNumber = 0; partitionNumber < catalog.partitions.size(); partitionNumber++) {
            Partition partition = catalog.partitions.get(partitionNumber);
            Statistics statistics = partition.statistics.get(columnName);

            Object min = parseStatistic(statistics.min, type);
            Object max = parseStatistic(statistics.max, type);

            boolean shouldPrune = shouldPrune(type, comparison, constant, min, max);
            log("table=%s column=%s comparison=%s const=%s partition=%d min=%s max=%s decision=%s".formatted(
                    tableName, columnName, comparison, constant, partitionNumber, statistics.min, statistics.max,
                    shouldPrune ? "PRUNED" : "READ"));

            if (shouldPrune) {
                pruned++;
                continue;
            }

            read++;
            try {
                result.addAll(readMatchingRows(
                        dataDirectory.resolve(partition.path),
                        catalog.schema,
                        predicateColumn,
                        comparison,
                        constant));
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read partition " + partition.path, exception);
            }
        }

        lastScanStats = new ScanStats(catalog.partitions.size(), read, pruned);

        log("table=%s column=%s comparison=%s const=%s partitionsRead=%d partitionsPruned=%d rowsOut=%d durationMs=%d"
                .formatted(tableName, columnName, comparison, constant, read, pruned, result.size(),
                        elapsedMillis(started)));

        return result;
    }

    public ScanStats getLastScanStats() {
        return lastScanStats;
    }

    // --- Package-Private Methods (Visible for Unit Testing) ---

    Object[] parseCsvLine(String line, String fileName, int lineNumber, List<Column> schema) {
        String[] fields = line.split(",", -1);
        if (fields.length != schema.size()) {
            throw parseError(fileName, lineNumber, "expected " + schema.size() + " fields but found " + fields.length);
        }

        Object[] values = new Object[schema.size()];
        for (int index = 0; index < fields.length; index++) {
            try {
                values[index] = parseValue(fields[index], ColumnType.valueOf(schema.get(index).type));
            } catch (RuntimeException exception) {
                throw parseError(fileName, lineNumber, "invalid " + schema.get(index).type + " value", exception);
            }
        }

        return values;
    }

    boolean shouldPrune(ColumnType type, Comparison comparison, Object constant, Object min, Object max) {
        int lower = compare(constant, min);
        int upper = compare(constant, max);

        return switch (comparison) {
            case EQUALS -> lower < 0 || upper > 0;
            case LESS_THAN -> lower <= 0;
            case GREATER_THAN -> upper >= 0;
        };
    }

    byte[] encodeValue(ColumnType type, Object value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);

            writeValue(output, type, value);
            output.flush();

            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode value", exception);
        }
    }

    Object decodeValue(ColumnType type, byte[] bytes) {
        try {
            return readValue(new DataInputStream(new ByteArrayInputStream(bytes)), type);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not decode value", exception);
        }
    }

    MinMax minMax(List<Object> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }

        Comparator<Object> comparator = StorageEngine::compare;
        Object min = values.stream().min(comparator).orElseThrow();
        Object max = values.stream().max(comparator).orElseThrow();

        return new MinMax(min, max);
    }

    // --- Internal Storage & Partition Management ---

    private void loadCatalogs() throws IOException {
        try (var paths = Files.list(catalogsDirectory)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".json")).toList()) {
                Catalog catalog = objectMapper.readValue(path.toFile(), Catalog.class);
                catalogs.put(catalog.table, catalog);
            }
        }
    }

    private void writeCatalog(Catalog catalog) {
        Path target = catalogsDirectory.resolve(catalog.table + ".json");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());

        try {
            objectMapper.writeValue(temporary.toFile(), catalog);
            moveAtomically(temporary, target);
        } catch (IOException exception) {
            cleanupTemporaryFiles(List.of(temporary));
            throw new IllegalStateException("Could not persist catalog for " + catalog.table, exception);
        }
    }

    private void writePartition(Path path, List<Object[]> rows, List<Column> schema) throws IOException {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
            output.write(MAGIC);
            output.writeShort(FORMAT_VERSION);
            output.writeInt(rows.size());
            output.writeShort(schema.size());

            for (Object[] row : rows) {
                for (int column = 0; column < schema.size(); column++) {
                    writeValue(output, ColumnType.valueOf(schema.get(column).type), row[column]);
                }
            }
        }
    }

    private List<Object[]> readMatchingRows(Path path, List<Column> schema, int predicateColumn,
            Comparison comparison, Object constant) throws IOException {
        List<Object[]> rows = new ArrayList<>();

        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!java.util.Arrays.equals(magic, MAGIC) || input.readUnsignedShort() != FORMAT_VERSION) {
                throw new IOException("invalid partition header");
            }

            int rowCount = input.readInt();
            int columnCount = input.readUnsignedShort();
            if (rowCount < 0 || columnCount != schema.size()) {
                throw new IOException("invalid partition header");
            }

            for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                Object[] row = new Object[schema.size()];
                for (int column = 0; column < schema.size(); column++) {
                    row[column] = readValue(input, ColumnType.valueOf(schema.get(column).type));
                }

                if (matches(row[predicateColumn], comparison, constant)) {
                    rows.add(row);
                }
            }
        }

        return rows;
    }

    private Map<String, Statistics> computeStatistics(List<Object[]> rows, List<Column> schema, String tableName,
            int partitionNumber) {
        Map<String, Statistics> statistics = new HashMap<>();

        for (int column = 0; column < schema.size(); column++) {
            int columnIndex = column;
            List<Object> values = rows.stream().map(row -> row[columnIndex]).toList();
            MinMax columnStatistics = minMax(values);

            Object min = columnStatistics.min;
            Object max = columnStatistics.max;

            statistics.put(schema.get(column).name,
                    new Statistics(String.valueOf(columnStatistics.min), String.valueOf(columnStatistics.max)));

            log("table=%s partition=%d column=%s min=%s max=%s".formatted(
                    tableName, partitionNumber, schema.get(column).name, min, max));
        }

        return statistics;
    }

    // --- Type Parsing & Serialization Helpers ---

    private static Object parseValue(String value, ColumnType type) {
        return switch (type) {
            case STRING -> value;
            case LONG -> Long.parseLong(value);
            case DOUBLE -> {
                double parsed = Double.parseDouble(value);
                if (!Double.isFinite(parsed)) {
                    throw new IllegalArgumentException("non-finite double");
                }
                yield parsed;
            }
        };
    }

    private static Object parseStatistic(Object value, ColumnType type) {
        if (type == ColumnType.STRING) {
            return String.valueOf(value);
        }
        if (!(value instanceof Number number)) {
            return parseValue(String.valueOf(value), type);
        }
        return type == ColumnType.LONG ? number.longValue() : number.doubleValue();
    }

    private static void writeValue(DataOutputStream output, ColumnType type, Object value) throws IOException {
        switch (type) {
            case STRING -> {
                byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                output.writeInt(bytes.length);
                output.write(bytes);
            }
            case LONG -> output.writeLong((Long) value);
            case DOUBLE -> output.writeLong(Double.doubleToLongBits((Double) value));
        }
    }

    private static Object readValue(DataInputStream input, ColumnType type) throws IOException {
        return switch (type) {
            case STRING -> {
                int length = input.readInt();
                if (length < 0) {
                    throw new IOException("negative string length");
                }

                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) {
                    throw new IOException("truncated string");
                }

                yield new String(bytes, StandardCharsets.UTF_8);
            }
            case LONG -> input.readLong();
            case DOUBLE -> Double.longBitsToDouble(input.readLong());
        };
    }

    // --- Predicate Evaluation & Comparisons ---

    private static boolean matches(Object value, Comparison comparison, Object constant) {
        int result = compare(value, constant);
        return switch (comparison) {
            case EQUALS -> result == 0;
            case LESS_THAN -> result < 0;
            case GREATER_THAN -> result > 0;
        };
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int compare(Object left, Object right) {
        return ((Comparable) left).compareTo(right);
    }

    private static IllegalArgumentException parseError(String file, int line, String message) {
        return new IllegalArgumentException(file + ": line " + line + ": " + message);
    }

    private static IllegalArgumentException parseError(String file, int line, String message, Throwable cause) {
        return new IllegalArgumentException(file + ": line " + line + ": " + message, cause);
    }

    // --- Validation & File Operations ---

    private Catalog requireCatalog(String tableName) {
        Catalog catalog = catalogs.get(tableName);
        if (catalog == null) {
            throw new IllegalArgumentException("unknown table: " + tableName);
        }
        return catalog;
    }

    private static int columnIndex(List<Column> schema, String columnName) {
        for (int index = 0; index < schema.size(); index++) {
            if (schema.get(index).name.equals(columnName)) {
                return index;
            }
        }
        throw new IllegalArgumentException("unknown column: " + columnName);
    }

    private static void validateConstant(String typeName, Object constant) {
        boolean valid = switch (ColumnType.valueOf(typeName)) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };
        if (!valid) {
            throw new IllegalArgumentException("constant type does not match column type");
        }
    }

    private static void requireTableName(String tableName) {
        if (tableName == null || tableName.isBlank() || tableName.contains("/") || tableName.contains("\\")
                || tableName.equals(".") || tableName.equals("..")) {
            throw new IllegalArgumentException("invalid table name");
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanupTemporaryFiles(List<Path> paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static void log(String message) {
        if (MDC.get("sessionId") == null) {
            MDC.put("sessionId", UUID.randomUUID().toString());
        }
        if (MDC.get("statementNumber") == null) {
            MDC.put("statementNumber", "0");
        }
        LOGGER.debug(message.replace(',', ';'));
    }

    // --- Catalog & Storage Data Models ---

    public static final class Catalog {
        public String table;
        public List<Column> schema;
        public List<Partition> partitions;

        public Catalog() {
        }

        Catalog(String table, List<Column> schema, List<Partition> partitions) {
            this.table = table;
            this.schema = schema;
            this.partitions = partitions;
        }
    }

    public static final class Column {
        @JsonProperty("column_name")
        public String name;

        @JsonProperty("column_type")
        public String type;

        public Column() {
        }

        Column(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    public static final class Partition {
        public String path;
        public int rowCount;
        public Map<String, Statistics> statistics;

        public Partition() {
        }

        Partition(String path, int rowCount, Map<String, Statistics> statistics) {
            this.path = path;
            this.rowCount = rowCount;
            this.statistics = statistics;
        }
    }

    public static final class Statistics {
        public String min;
        public String max;

        public Statistics() {
        }

        Statistics(Object min, Object max) {
            this.min = String.valueOf(min);
            this.max = String.valueOf(max);
        }
    }

    record MinMax(Object min, Object max) {
    }
}
