package datasys.semi.engine;

import datasys.semi.schema.*;

import java.io.BufferedReader;
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
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import datasys.semi.util.LogSanitizer;

public final class StorageEngine {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private static final byte[] MAGIC = { 'S', 'E', 'M', 'I' };
    private static final short FORMAT_VERSION = 1;
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 10000;

    // --- Directory Paths ---
    private final Path dataDirectory;
    private final Path catalogsDirectory;
    private final Path dataFilesDirectory;

    // --- Partition Sizing & State ---
    private final int maxRowsPerPartition;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, Catalog> catalogs = new HashMap<>();

    public StorageEngine(Path dataDirectory) {
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    public StorageEngine(Path dataDirectory, int maxRowsPerPartition) {
        if (dataDirectory == null || maxRowsPerPartition <= 0) {
            throw new IllegalArgumentException("data directory and partition size must be valid");
        }
        if (MDC.get("statementNumber") == null) {
            MDC.put("statementNumber", "0");
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
        LOGGER.debug("api=createTable table=%s".formatted(tableName));

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

        LOGGER.debug("table=%s columns=%d".formatted(tableName, schema.size()));
    }

    /**
     * Loads a headerless CSV file into an empty table by streaming it: at most one
     * partition of parsed rows is held in memory at a time. The copy is
     * all-or-nothing; the catalog is only updated after the last partition is
     * written, and a failed copy deletes every partition file it wrote.
     *
     * @param tableName   name of an existing table that has no partitions yet
     * @param csvFilePath path of the CSV file to load
     * @throws IllegalArgumentException      if the table is unknown, the file cannot
     *                                       be read, or a line is malformed
     * @throws UnsupportedOperationException if the table already holds data
     * @throws IllegalStateException         if a partition or the catalog cannot be
     *                                       written
     */
    public void copyFile(String tableName, String csvFilePath) {
        LOGGER.debug("api=copyFile table=%s file=%s".formatted(tableName, LogSanitizer.sanitize(csvFilePath)));
        Catalog catalog = requireCatalog(tableName);

        if (!catalog.partitions.isEmpty()) {
            throw new UnsupportedOperationException("copying more than one file per table is not supported");
        }

        long started = System.nanoTime();
        // Only valid while a table accepts a single COPY: data/<table>/ then holds no
        // committed partitions, so it can be cleared before and deleted after a failure.
        Path tableDirectory = dataFilesDirectory.resolve(tableName);
        boolean committed = false;

        try {
            deleteDirectoryRecursively(tableDirectory);
            List<Partition> partitions = streamCsvIntoPartitions(tableName, csvFilePath, catalog, tableDirectory);
            commitCatalog(catalog, partitions);
            committed = true;
            logCopySummary(tableName, csvFilePath, partitions, started);
        } catch (RuntimeException exception) {
            LOGGER.error("api=copyFile table={} error={}", tableName, LogSanitizer.sanitize(exception.getMessage()));
            throw exception;
        } finally {
            if (!committed) {
                discardFailedCopy(tableDirectory);
            }
        }
    }

    /**
     * Returns the list of partitions for a table in catalog order.
     *
     * @param tableName the name of the table to look up
     * @return unmodifiable list of partitions
     * @throws IllegalArgumentException if the table is unknown or tableName is null
     */
    public List<Partition> partitions(String tableName) {
        Catalog catalog = requireCatalog(tableName);
        return List.copyOf(catalog.partitions);
    }

    /**
     * Returns the table's schema, in column order.
     *
     * @param tableName the name of the table to look up
     * @return list of column specifications in column order
     * @throws IllegalArgumentException if the table is unknown or tableName is null
     */
    public List<ColumnSpec> schema(String tableName) {
        Catalog catalog = requireCatalog(tableName);
        return catalog.schema.stream()
                .map(column -> new ColumnSpec(column.name, ColumnType.valueOf(column.type)))
                .toList();
    }

    /**
     * Reads every row of one partition. No predicate is involved: this is the plain
     * read the scan operator performs once the planner has chosen the partition.
     *
     * @param tableName the table owning the partition
     * @param partition the partition to read, as taken from {@link #partitions}
     * @return all rows of the partition, in file order and schema column order
     * @throws IllegalArgumentException if the table is unknown or partition is null
     * @throws IllegalStateException    if the partition file cannot be read
     */
    public List<Object[]> readPartition(String tableName, Partition partition) {
        Catalog catalog = requireCatalog(tableName);
        if (partition == null) {
            throw new IllegalArgumentException("partition must not be null");
        }

        try {
            return readRows(dataDirectory.resolve(partition.path), catalog.schema);
        } catch (IOException exception) {
            LOGGER.error("api=readPartition table={} partition={} failed", tableName,
                    LogSanitizer.sanitize(partition.path));
            throw new IllegalStateException("Could not read partition " + partition.path, exception);
        }
    }

    /**
     * Reads every row of a catalog partition selected by its zero-based number.
     *
     * @param tableName       the table owning the partition
     * @param partitionNumber zero-based partition number in catalog order
     * @return all rows of the partition, in file order and schema column order
     * @throws IllegalArgumentException if the table is unknown or the partition
     *                                  number is outside the catalog range
     * @throws IllegalStateException    if the partition file cannot be read
     */
    public List<Object[]> readPartition(String tableName, int partitionNumber) {
        Catalog catalog = requireCatalog(tableName);
        if (partitionNumber < 0 || partitionNumber >= catalog.partitions.size()) {
            throw new IllegalArgumentException("partition number out of range: " + partitionNumber);
        }

        return readPartition(tableName, catalog.partitions.get(partitionNumber));
    }

    // --- Package-Private Methods (Visible for Unit Testing) ---

    public Object[] parseCsvLine(String line, String fileName, int lineNumber, List<Column> schema) {
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

    /**
     * Determines whether a partition should be pruned based on column min/max
     * summaries.
     *
     * @param type       column type
     * @param comparison comparison operator
     * @param constant   filter literal constant
     * @param min        partition minimum value
     * @param max        partition maximum value
     * @return true if the partition can be safely pruned, false otherwise
     */
    public boolean shouldPrune(ColumnType type, Comparison comparison, Object constant, Object min, Object max) {
        int lower = compare(constant, min);
        int upper = compare(constant, max);

        return switch (comparison) {
            case EQUALS -> lower < 0 || upper > 0;
            case LESS_THAN -> lower <= 0;
            case GREATER_THAN -> upper >= 0;
        };
    }

    public MinMax minMax(List<Object> values) {
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

    /**
     * Streams a CSV file line by line into partition files. Rows are buffered
     * until the buffer holds {@code maxRowsPerPartition} rows, then written as one
     * partition; the remaining rows form the last partition.
     *
     * @param tableName      table being loaded
     * @param csvFilePath    path of the CSV file to read
     * @param catalog        catalog of the table, providing its schema
     * @param tableDirectory directory receiving the partition files
     * @return written partitions in partition-number order; empty for an empty file
     * @throws IllegalArgumentException if the file cannot be read or a line is
     *                                  malformed
     * @throws IllegalStateException    if a partition file cannot be written
     */
    private List<Partition> streamCsvIntoPartitions(String tableName, String csvFilePath, Catalog catalog,
            Path tableDirectory) {
        List<Partition> partitions = new ArrayList<>();
        List<Object[]> partitionRows = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(Path.of(csvFilePath), StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                partitionRows.add(parseCsvLine(line, csvFilePath, lineNumber, catalog.schema));
                if (partitionRows.size() == maxRowsPerPartition) {
                    partitions.add(writePartitionFile(tableName, tableDirectory, partitions.size(), partitionRows,
                            catalog.schema));
                    partitionRows.clear();
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read " + csvFilePath, exception);
        }

        if (!partitionRows.isEmpty()) {
            partitions.add(writePartitionFile(tableName, tableDirectory, partitions.size(), partitionRows,
                    catalog.schema));
        }
        return partitions;
    }

    /**
     * Writes one buffered partition to {@code partition-<n>.dat} via a temporary
     * file and an atomic move, then computes and logs its min/max statistics.
     *
     * @param tableName       table owning the partition
     * @param tableDirectory  directory receiving the partition file
     * @param partitionNumber zero-based partition number
     * @param rows            non-empty rows of this partition
     * @param schema          table schema, in column order
     * @return catalog entry describing the written partition
     * @throws IllegalStateException if the partition file cannot be written
     */
    private Partition writePartitionFile(String tableName, Path tableDirectory, int partitionNumber,
            List<Object[]> rows, List<Column> schema) {
        Path finalPath = tableDirectory.resolve("partition-%d.dat".formatted(partitionNumber));
        Path temporaryPath = finalPath.resolveSibling(finalPath.getFileName() + ".tmp-" + UUID.randomUUID());

        try {
            Files.createDirectories(tableDirectory);
            writePartition(temporaryPath, rows, schema);
            moveAtomically(temporaryPath, finalPath);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not write partition " + partitionNumber + " of table " + tableName, exception);
        }

        return new Partition(dataDirectory.relativize(finalPath).toString(), rows.size(),
                computeStatistics(rows, schema, tableName, partitionNumber));
    }

    /**
     * Persists the table's catalog with its new partitions and caches it. This is
     * the commit point of a copy: before it, the partitions are invisible.
     *
     * @param catalog    current catalog of the table
     * @param partitions partitions written by the copy
     * @throws IllegalStateException if the catalog cannot be persisted
     */
    private void commitCatalog(Catalog catalog, List<Partition> partitions) {
        Catalog updated = new Catalog(catalog.table, catalog.schema, partitions);
        writeCatalog(updated);
        catalogs.put(catalog.table, updated);
    }

    /**
     * Logs the summary line of a successful copy.
     *
     * @param tableName   loaded table
     * @param csvFilePath path of the loaded CSV file
     * @param partitions  partitions written by the copy
     * @param started     {@link System#nanoTime()} value when the copy started
     */
    private static void logCopySummary(String tableName, String csvFilePath, List<Partition> partitions,
            long started) {
        long rowCount = partitions.stream().mapToLong(partition -> partition.rowCount).sum();
        LOGGER.debug("table=%s file=%s rows=%d partitions=%d durationMs=%d".formatted(
                tableName, LogSanitizer.sanitize(Path.of(csvFilePath).getFileName()), rowCount,
                partitions.size(), elapsedMillis(started)));
    }

    /**
     * Deletes the partition files of a copy that did not commit. A cleanup failure
     * is logged and not rethrown, so the exception that failed the copy is the one
     * that reaches the caller; the leftover files are unreferenced by the catalog.
     *
     * @param tableDirectory directory holding the uncommitted partition files
     */
    private static void discardFailedCopy(Path tableDirectory) {
        try {
            deleteDirectoryRecursively(tableDirectory);
        } catch (IllegalStateException exception) {
            LOGGER.error("api=copyFile cleanup failed error={}", LogSanitizer.sanitize(exception.getMessage()));
        }
    }

    /**
     * Deletes a directory and everything below it. A missing directory is a no-op.
     *
     * @param directory directory to delete
     * @throws IllegalStateException if a file or directory cannot be deleted
     */
    private static void deleteDirectoryRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not delete " + directory, exception);
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

    /**
     * Decodes every row of a partition file. This is the single place the binary
     * partition format is read; callers that need a subset filter afterwards.
     *
     * @param path   the partition data file to read
     * @param schema the table schema, in column order
     * @return all rows of the partition, in file order
     * @throws IOException if the file cannot be read or its header is invalid
     */
    private List<Object[]> readRows(Path path, List<Column> schema) throws IOException {
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
                rows.add(row);
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

            LOGGER.debug("table=%s partition=%d column=%s min=%s max=%s".formatted(
                    tableName, partitionNumber, schema.get(column).name,
                    LogSanitizer.sanitize(min), LogSanitizer.sanitize(max)));
        }

        return statistics;
    }

    // --- Type Parsing & Serialization Helpers ---

    private static Object parseValue(String value, ColumnType type) {
        return switch (type) {
            case STRING -> value;
            case LONG -> (value == null || value.isBlank()) ? 0L : Long.parseLong(value.trim());
            case DOUBLE -> {
                double parsed = Double.parseDouble(value);
                if (!Double.isFinite(parsed)) {
                    throw new IllegalArgumentException("non-finite double");
                }
                yield parsed;
            }
        };
    }

    /**
     * Parses a string or raw statistic value into the appropriate Java type for the
     * column.
     *
     * @param value raw statistic value
     * @param type  column type
     * @return parsed typed object
     */
    public static Object parseStatistic(Object value, ColumnType type) {
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
            throw new IOException("Atomic move not supported on this file system", exception);
        }
    }

    /**
     * Deletes temporary files left by a failed write. A failed delete is logged and
     * not rethrown, so the original write failure reaches the caller.
     *
     * @param paths temporary files to delete; missing files are ignored
     */
    private static void cleanupTemporaryFiles(List<Path> paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                LOGGER.error("api=cleanupTemporaryFiles file={} error={}", LogSanitizer.sanitize(path),
                        LogSanitizer.sanitize(exception.getMessage()));
            }
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
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

        public Column(String name, String type) {
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

    public static record MinMax(Object min, Object max) {
    }
}
