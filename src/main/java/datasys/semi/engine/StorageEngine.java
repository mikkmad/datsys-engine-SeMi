package datasys.semi.engine;

import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;
import datasys.semi.schema.Comparison;
import datasys.semi.util.LogSanitizer;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Manages versioned table catalogs, immutable binary partition files, and
 * atomic catalog commits.
 *
 * <p>
 * Each table version is persisted as an immutable JSON file
 * {@code catalogs/
 * 
<table>
 * -v<version>.json} and swapped into the in-memory
 * catalog map atomically after the disk rename succeeds. Readers pin a
 * {@link Catalog} snapshot at statement start and never re-read live metadata.
 */
public final class StorageEngine {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private static final byte[] MAGIC = { 'S', 'E', 'M', 'I' };
    private static final short FORMAT_VERSION = 1;
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 10000;
    private static final int MAX_RETAINED_CATALOG_VERSIONS = 10;
    private static final Pattern CATALOG_FILE_PATTERN = Pattern.compile("^(.+)-v(\\d+)\\.json$");

    // --- Directory Paths ---
    private final Path dataDirectory;
    private final Path catalogsDirectory;
    private final Path dataFilesDirectory;

    // --- Partition Sizing & State ---
    private final int maxRowsPerPartition;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, Catalog> catalogs = new ConcurrentHashMap<>();

    /**
     * Constructs a storage engine with the default partition capacity.
     *
     * @param dataDirectory root directory holding {@code catalogs/} and
     *                      {@code data/}
     * @throws IllegalArgumentException if {@code dataDirectory} is null
     * @throws IllegalStateException    if the storage directories or catalogs
     *                                  cannot be opened
     */
    public StorageEngine(Path dataDirectory) {
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    /**
     * Constructs a storage engine with an explicit maximum row count per partition.
     *
     * @param dataDirectory       root directory holding {@code catalogs/} and
     *                            {@code data/}
     * @param maxRowsPerPartition maximum number of rows written to a single
     *                            partition file
     * @throws IllegalArgumentException if {@code dataDirectory} is null or
     *                                  {@code maxRowsPerPartition} is non-positive
     * @throws IllegalStateException    if the storage directories or catalogs
     *                                  cannot be opened
     */
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
            LOGGER.error("api=StorageEngine dir={} error={}", LogSanitizer.sanitize(dataDirectory),
                    LogSanitizer.sanitize(exception.getMessage()));
            throw new IllegalStateException("Could not open storage directory " + dataDirectory, exception);
        }
    }

    /**
     * Returns the current committed catalog snapshot for a table.
     *
     * @param tableName name of the table to look up
     * @return immutable catalog snapshot at the latest committed version
     * @throws IllegalArgumentException if the table does not exist or
     *                                  {@code tableName} is null
     */
    public Catalog snapshot(String tableName) {
        if (tableName == null) {
            throw new IllegalArgumentException("unknown table: null");
        }
        Catalog catalog = catalogs.get(tableName);
        if (catalog == null) {
            throw new IllegalArgumentException("unknown table: " + tableName);
        }
        return catalog;
    }

    /**
     * Creates a new empty table at version 0 and persists its initial catalog.
     *
     * @param tableName name of the table to create
     * @param columns   ordered column specifications
     * @throws IllegalArgumentException if the table name or column list is invalid,
     *                                  or the table already exists
     * @throws IllegalStateException    if the initial catalog file cannot be
     *                                  persisted
     */
    public void createTable(String tableName, List<ColumnSpec> columns) {
        LOGGER.debug("api=createTable table={}", LogSanitizer.sanitize(tableName));
        requireTableName(tableName);
        List<Column> schema = validateAndConvertColumns(columns);

        Catalog initial = new Catalog(tableName, 0L, schema, List.of());
        commitCatalog(null, initial);

        LOGGER.debug("table={} version=0 columns={}", tableName, schema.size());
    }

    /**
     * Streams a headerless CSV file into new UUID-named partition files and commits
     * the next catalog version with the new partitions appended to
     * {@code snapshot}.
     *
     * <p>
     * The load is all-or-nothing: committed partitions are never touched, and if
     * reading or writing fails, only the partition files written by this invocation
     * are deleted while the table remains at its previous version.
     *
     * @param snapshot    pinned catalog snapshot of the target table
     * @param csvFilePath path to the CSV file to load
     * @return summary containing rows loaded, partitions written, and the committed
     *         version
     * @throws IllegalArgumentException if {@code snapshot} is null, the CSV cannot
     *                                  be read, or a line is malformed
     * @throws IllegalStateException    if a partition file cannot be written, the
     *                                  catalog cannot be persisted,
     *                                  or the table's version advanced since
     *                                  {@code snapshot} was taken
     */
    public CopyResult copyFile(Catalog snapshot, String csvFilePath) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        String tableName = snapshot.table();
        LOGGER.debug("api=copyFile table={} file={}", tableName, LogSanitizer.sanitize(csvFilePath));

        long started = System.nanoTime();
        Path tableDirectory = dataFilesDirectory.resolve(tableName);
        List<Path> writtenFiles = new ArrayList<>();
        boolean committed = false;

        try {
            List<Partition> addedPartitions = streamCsvIntoPartitions(snapshot, csvFilePath, tableDirectory,
                    writtenFiles);
            Catalog nextCatalog = snapshot.withAppendedPartitions(addedPartitions);
            commitCatalog(snapshot, nextCatalog);
            committed = true;

            long rowCount = addedPartitions.stream().mapToLong(Partition::rowCount).sum();
            logCopySummary(tableName, csvFilePath, rowCount, addedPartitions.size(), nextCatalog.version(), started);
            return new CopyResult(rowCount, addedPartitions.size(), nextCatalog.version());
        } catch (RuntimeException exception) {
            LOGGER.error("api=copyFile table={} error={}", tableName, LogSanitizer.sanitize(exception.getMessage()));
            throw exception;
        } finally {
            if (!committed) {
                discardWrittenPartitions(tableDirectory, writtenFiles);
            }
        }
    }

    /**
     * Atomically commits {@code next} on disk and in memory if the table's current
     * version still matches {@code base}.
     *
     * @param base expected current catalog snapshot, or {@code null} when creating
     *             version 0
     * @param next new catalog version to persist and publish
     * @throws IllegalArgumentException if {@code next} is null or creating a table
     *                                  that already exists
     * @throws IllegalStateException    if the current version does not match
     *                                  {@code base} or persistence fails
     */
    public void commitCatalog(Catalog base, Catalog next) {
        if (next == null) {
            throw new IllegalArgumentException("next catalog must not be null");
        }

        Path target = catalogsDirectory.resolve(catalogFileName(next.table(), next.version()));
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());

        try {
            writeTemporaryCatalog(next, temporary);
            catalogs.compute(next.table(), (tableName, current) -> {
                validateCommitBase(tableName, base, current);
                publishCatalogAtomically(next, temporary, target);
                return next;
            });
        } finally {
            cleanupTemporaryFiles(List.of(temporary));
        }

        pruneOldCatalogVersions(next.table());
    }

    /**
     * Reads a specific historical catalog version of a table from disk.
     *
     * @param tableName name of the table
     * @param version   version number to read
     * @return deserialized catalog snapshot for the requested version
     * @throws IllegalStateException if the version file has been dropped by
     *                               retention or cannot be read
     */
    public Catalog readCatalogVersion(String tableName, long version) {
        Path path = catalogsDirectory.resolve(catalogFileName(tableName, version));
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    "version " + version + " of table " + tableName + " is no longer available");
        }

        try {
            return objectMapper.readValue(path.toFile(), Catalog.class);
        } catch (IOException exception) {
            LOGGER.error("api=readCatalogVersion table={} version={} error={}",
                    LogSanitizer.sanitize(tableName), version, LogSanitizer.sanitize(exception.getMessage()));
            throw new IllegalStateException("Could not read catalog version " + version + " for " + tableName,
                    exception);
        }
    }

    /**
     * Reads every row of one partition using the schema from the pinned catalog
     * snapshot.
     *
     * @param snapshot  pinned catalog snapshot owning the partition
     * @param partition partition metadata entry to read
     * @return all rows of the partition, in file order and schema column order
     * @throws IllegalArgumentException if {@code snapshot} or {@code partition} is
     *                                  null
     * @throws IllegalStateException    if the partition file cannot be read
     */
    public List<Object[]> readPartition(Catalog snapshot, Partition partition) {
        if (snapshot == null || partition == null) {
            throw new IllegalArgumentException("snapshot and partition must not be null");
        }

        try {
            return readRows(dataDirectory.resolve(partition.path()), snapshot.schema());
        } catch (IOException exception) {
            LOGGER.error("api=readPartition table={} partition={} failed", snapshot.table(),
                    LogSanitizer.sanitize(partition.path()));
            throw new IllegalStateException("Could not read partition " + partition.path(), exception);
        }
    }

    // --- Package-Private & Public Helpers (Visible for Unit Testing) ---

    /**
     * Parses a single CSV line into typed column values according to the provided
     * schema.
     *
     * @param line       raw CSV line without line terminator
     * @param fileName   source file path used in error reporting
     * @param lineNumber one-based line number used in error reporting
     * @param schema     ordered column definitions
     * @return array of typed column values
     * @throws IllegalArgumentException if the field count or any value is invalid
     */
    public Object[] parseCsvLine(String line, String fileName, int lineNumber, List<Column> schema) {
        String[] fields = line.split(",", -1);
        if (fields.length != schema.size()) {
            throw parseError(fileName, lineNumber, "expected " + schema.size() + " fields but found " + fields.length);
        }

        Object[] values = new Object[schema.size()];
        for (int index = 0; index < fields.length; index++) {
            try {
                values[index] = parseValue(fields[index], ColumnType.valueOf(schema.get(index).type()));
            } catch (RuntimeException exception) {
                throw parseError(fileName, lineNumber, "invalid " + schema.get(index).type() + " value", exception);
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

    /**
     * Computes the minimum and maximum values of a non-empty list of comparable
     * values.
     *
     * @param values non-empty list of column values
     * @return pair of minimum and maximum values
     * @throws IllegalArgumentException if {@code values} is null or empty
     */
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

    /**
     * Scans {@code catalogs/}, selects the highest version file per table, and
     * populates the in-memory catalog map. Temporary files and non-matching
     * filenames are ignored.
     *
     * @throws IOException if listing or reading catalog files fails
     */
    private void loadCatalogs() throws IOException {
        Map<String, VersionedFile> latestByTable = new HashMap<>();

        try (Stream<Path> paths = Files.list(catalogsDirectory)) {
            for (Path path : paths.toList()) {
                Matcher matcher = CATALOG_FILE_PATTERN.matcher(path.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                String tableName = matcher.group(1);
                long version = Long.parseLong(matcher.group(2));
                latestByTable.merge(tableName, new VersionedFile(version, path),
                        (existing, candidate) -> candidate.version() > existing.version() ? candidate : existing);
            }
        }

        for (VersionedFile latest : latestByTable.values()) {
            Catalog catalog = objectMapper.readValue(latest.path().toFile(), Catalog.class);
            catalogs.put(catalog.table(), catalog);
            LOGGER.debug("table={} version={} loaded", catalog.table(), catalog.version());
        }
    }

    /**
     * Serializes {@code catalog} to a temporary file before entering the catalog
     * map's critical section.
     *
     * @param catalog   catalog version to serialize
     * @param temporary temporary file path to write
     * @throws IllegalStateException if the temporary file cannot be written
     */
    private void writeTemporaryCatalog(Catalog catalog, Path temporary) {
        try {
            objectMapper.writeValue(temporary.toFile(), catalog);
        } catch (IOException exception) {
            LOGGER.error("api=writeCatalog table={} version={} error={}", catalog.table(), catalog.version(),
                    LogSanitizer.sanitize(exception.getMessage()));
            throw new IllegalStateException("Could not persist catalog for " + catalog.table(), exception);
        }
    }

    /**
     * Atomically renames a pre-written temporary catalog file into {@code target}.
     *
     * @param catalog   catalog version being published
     * @param temporary pre-written temporary file path
     * @param target    final versioned catalog file path
     * @throws IllegalStateException if the atomic rename fails
     */
    private static void publishCatalogAtomically(Catalog catalog, Path temporary, Path target) {
        try {
            moveAtomically(temporary, target);
        } catch (IOException exception) {
            LOGGER.error("api=writeCatalog table={} version={} error={}", catalog.table(), catalog.version(),
                    LogSanitizer.sanitize(exception.getMessage()));
            throw new IllegalStateException("Could not persist catalog for " + catalog.table(), exception);
        }
    }

    /**
     * Retains only the newest {@link #MAX_RETAINED_CATALOG_VERSIONS} catalog files
     * for {@code tableName} on disk, deleting older version files in ascending
     * numerical version order. Failures are logged and not rethrown so post-commit
     * housekeeping never rolls back an already committed catalog version.
     *
     * @param tableName table whose old catalog versions should be pruned
     */
    private void pruneOldCatalogVersions(String tableName) {
        Pattern tablePattern = Pattern.compile("^" + Pattern.quote(tableName) + "-v(\\d+)\\.json$");
        List<VersionedFile> matches = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(catalogsDirectory)) {
            for (Path path : stream) {
                Matcher matcher = tablePattern.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    matches.add(new VersionedFile(Long.parseLong(matcher.group(1)), path));
                }
            }
            matches.sort(Comparator.comparingLong(VersionedFile::version));
            for (int index = 0; index < matches.size() - MAX_RETAINED_CATALOG_VERSIONS; index++) {
                Files.deleteIfExists(matches.get(index).path());
            }
        } catch (IOException exception) {
            LOGGER.error("api=pruneOldCatalogVersions table={} error={}", tableName,
                    LogSanitizer.sanitize(exception.getMessage()));
        }
    }

    /**
     * Streams a CSV file line by line into UUID-named partition files.
     *
     * @param snapshot       pinned catalog snapshot providing the schema and base
     *                       partition count
     * @param csvFilePath    path of the CSV file to read
     * @param tableDirectory directory receiving the partition files
     * @param writtenFiles   collector recording each committed partition file path
     *                       for rollback cleanup
     * @return written partitions in load order; empty for an empty file
     * @throws IllegalArgumentException if the file cannot be read or a line is
     *                                  malformed
     * @throws IllegalStateException    if a partition file cannot be written
     */
    private List<Partition> streamCsvIntoPartitions(Catalog snapshot, String csvFilePath, Path tableDirectory,
            List<Path> writtenFiles) {
        List<Partition> partitions = new ArrayList<>();
        List<Object[]> partitionRows = new ArrayList<>();
        int baseOffset = snapshot.partitions().size();

        try (BufferedReader reader = Files.newBufferedReader(Path.of(csvFilePath), StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                partitionRows.add(parseCsvLine(line, csvFilePath, lineNumber, snapshot.schema()));
                if (partitionRows.size() == maxRowsPerPartition) {
                    partitions.add(writePartitionFile(snapshot.table(), tableDirectory,
                            baseOffset + partitions.size(), partitionRows, snapshot.schema(), writtenFiles));
                    partitionRows.clear();
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read " + csvFilePath, exception);
        }

        if (!partitionRows.isEmpty()) {
            partitions.add(writePartitionFile(snapshot.table(), tableDirectory,
                    baseOffset + partitions.size(), partitionRows, snapshot.schema(), writtenFiles));
        }
        return partitions;
    }

    /**
     * Writes one buffered partition to {@code partition-<uuid>.dat} via a temporary
     * file and an atomic move, records its path in {@code writtenFiles}, and
     * computes and logs its min/max statistics.
     *
     * @param tableName       table owning the partition
     * @param tableDirectory  directory receiving the partition file
     * @param partitionNumber catalog-wide zero-based partition ordinal for logging
     * @param rows            non-empty rows of this partition
     * @param schema          table schema, in column order
     * @param writtenFiles    collector receiving the final path after the atomic
     *                        move
     * @return catalog entry describing the written partition
     * @throws IllegalStateException if the partition file cannot be written
     */
    private Partition writePartitionFile(String tableName, Path tableDirectory, int partitionNumber,
            List<Object[]> rows, List<Column> schema, List<Path> writtenFiles) {
        Path finalPath = tableDirectory.resolve("partition-%s.dat".formatted(UUID.randomUUID()));
        Path temporaryPath = finalPath.resolveSibling(finalPath.getFileName() + ".tmp-" + UUID.randomUUID());

        try {
            Files.createDirectories(tableDirectory);
            writePartition(temporaryPath, rows, schema);
            moveAtomically(temporaryPath, finalPath);
            writtenFiles.add(finalPath);
        } catch (IOException exception) {
            LOGGER.error("api=writePartitionFile table={} partition={} error={}", tableName, partitionNumber,
                    LogSanitizer.sanitize(exception.getMessage()));
            throw new IllegalStateException(
                    "Could not write partition " + partitionNumber + " of table " + tableName, exception);
        } finally {
            cleanupTemporaryFiles(List.of(temporaryPath));
        }

        return new Partition(dataDirectory.relativize(finalPath).toString(), rows.size(),
                computeStatistics(rows, schema, tableName, partitionNumber));
    }

    /**
     * Logs the debug summary line of a successful copy inside the storage engine.
     *
     * @param tableName      loaded table
     * @param csvFilePath    path of the loaded CSV file
     * @param rowCount       number of rows loaded
     * @param partitionCount number of partitions written
     * @param version        committed catalog version
     * @param started        {@link System#nanoTime()} value when the copy started
     */
    private static void logCopySummary(String tableName, String csvFilePath, long rowCount, int partitionCount,
            long version, long started) {
        LOGGER.debug("table={} file={} rows={} partitions={} version={} durationMs={}",
                tableName, LogSanitizer.sanitize(Path.of(csvFilePath).getFileName()), rowCount,
                partitionCount, version, elapsedMillis(started));
    }

    /**
     * Deletes only the partition files written by a failed {@code COPY}, and
     * removes {@code data/
     * 
    <table>
     * /} only if it is now empty.
     *
     * @param tableDirectory directory holding the table's partition files
     * @param writtenFiles   partition files written during the failed {@code COPY}
     */
    private static void discardWrittenPartitions(Path tableDirectory, List<Path> writtenFiles) {
        cleanupTemporaryFiles(writtenFiles);
        if (!Files.isDirectory(tableDirectory)) {
            return;
        }

        try (Stream<Path> remaining = Files.list(tableDirectory)) {
            if (remaining.findAny().isEmpty()) {
                Files.deleteIfExists(tableDirectory);
            }
        } catch (IOException exception) {
            LOGGER.error("api=copyFile cleanup failed error={}", LogSanitizer.sanitize(exception.getMessage()));
        }
    }

    /**
     * Writes rows in the binary {@code SEMI} partition format to {@code path}.
     *
     * @param path   target file path
     * @param rows   rows to serialize
     * @param schema table schema in column order
     * @throws IOException if writing to the stream fails
     */
    private void writePartition(Path path, List<Object[]> rows, List<Column> schema) throws IOException {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
            output.write(MAGIC);
            output.writeShort(FORMAT_VERSION);
            output.writeInt(rows.size());
            output.writeShort(schema.size());

            for (Object[] row : rows) {
                for (int column = 0; column < schema.size(); column++) {
                    writeValue(output, ColumnType.valueOf(schema.get(column).type()), row[column]);
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
            if (!Arrays.equals(magic, MAGIC) || input.readUnsignedShort() != FORMAT_VERSION) {
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
                    row[column] = readValue(input, ColumnType.valueOf(schema.get(column).type()));
                }
                rows.add(row);
            }
        }

        return rows;
    }

    /**
     * Computes per-column min/max statistics for a partition and logs each summary.
     *
     * @param rows            non-empty partition rows
     * @param schema          table schema in column order
     * @param tableName       table name for logging
     * @param partitionNumber catalog-wide partition ordinal for logging
     * @return map from column name to min/max statistics
     */
    private Map<String, Statistics> computeStatistics(List<Object[]> rows, List<Column> schema, String tableName,
            int partitionNumber) {
        Map<String, Statistics> statistics = new HashMap<>();

        for (int column = 0; column < schema.size(); column++) {
            int columnIndex = column;
            List<Object> values = rows.stream().map(row -> row[columnIndex]).toList();
            MinMax columnStatistics = minMax(values);

            Object min = columnStatistics.min();
            Object max = columnStatistics.max();
            String columnName = schema.get(column).name();

            statistics.put(columnName, new Statistics(String.valueOf(min), String.valueOf(max)));

            LOGGER.debug("table={} partition={} column={} min={} max={}",
                    tableName, partitionNumber, columnName,
                    LogSanitizer.sanitize(min), LogSanitizer.sanitize(max));
        }

        return statistics;
    }

    // --- Type Parsing & Serialization Helpers ---

    /**
     * Parses a raw string token into a typed column value.
     *
     * @param value raw text token
     * @param type  target column type
     * @return parsed String, Long, or finite Double
     */
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

    /**
     * Writes a single typed column value to the binary partition stream.
     *
     * @param output binary output stream
     * @param type   column type
     * @param value  typed column value
     * @throws IOException if writing fails
     */
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

    /**
     * Reads a single typed column value from the binary partition stream.
     *
     * @param input binary input stream
     * @param type  column type
     * @return decoded String, Long, or Double value
     * @throws IOException if reading fails or string framing is invalid
     */
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

    /**
     * Compares two homogeneous comparable values.
     *
     * @param left  left operand
     * @param right right operand
     * @return negative, zero, or positive comparison result
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int compare(Object left, Object right) {
        return ((Comparable) left).compareTo(right);
    }

    /**
     * Formats a CSV line parse error without a cause.
     *
     * @param file    CSV file path
     * @param line    one-based line number
     * @param message error detail
     * @return formatted IllegalArgumentException
     */
    private static IllegalArgumentException parseError(String file, int line, String message) {
        return new IllegalArgumentException(file + ": line " + line + ": " + message);
    }

    /**
     * Formats a CSV line parse error with an underlying cause.
     *
     * @param file    CSV file path
     * @param line    one-based line number
     * @param message error detail
     * @param cause   underlying exception
     * @return formatted IllegalArgumentException
     */
    private static IllegalArgumentException parseError(String file, int line, String message, Throwable cause) {
        return new IllegalArgumentException(file + ": line " + line + ": " + message, cause);
    }

    // --- Validation & File Operations ---

    /**
     * Validates that the in-memory catalog state matches {@code base} prior to
     * committing a new version.
     *
     * @param tableName table being committed
     * @param base      expected base catalog, or {@code null} for initial table
     *                  creation
     * @param current   current in-memory catalog entry
     * @throws IllegalArgumentException if creating a table that already exists
     * @throws IllegalStateException    if updating a table whose current version
     *                                  differs from {@code base}
     */
    private static void validateCommitBase(String tableName, Catalog base, Catalog current) {
        if (base == null) {
            if (current != null) {
                throw new IllegalArgumentException("table already exists: " + tableName);
            }
            return;
        }
        if (current == null || current.version() != base.version()) {
            long currentVersion = current == null ? -1L : current.version();
            throw new IllegalStateException(
                    "stale base version for table " + tableName + ": expected " + base.version() + " but found "
                            + currentVersion);
        }
    }

    /**
     * Validates column specifications for {@code CREATE TABLE} and converts them to
     * catalog columns.
     *
     * @param columns column specifications to validate
     * @return list of catalog column records
     * @throws IllegalArgumentException if {@code columns} is null, empty, or
     *                                  contains invalid/duplicate columns
     */
    private static List<Column> validateAndConvertColumns(List<ColumnSpec> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("column list must not be empty");
        }

        List<Column> schema = new ArrayList<>(columns.size());
        for (ColumnSpec column : columns) {
            if (column == null || column.name() == null || column.name().isBlank() || column.type() == null
                    || schema.stream().anyMatch(existing -> existing.name().equals(column.name()))) {
                throw new IllegalArgumentException("invalid or duplicate column");
            }
            schema.add(new Column(column.name(), column.type().name()));
        }
        return schema;
    }

    /**
     * Validates that a table name is non-blank and safe for file paths.
     *
     * @param tableName table name to check
     * @throws IllegalArgumentException if {@code tableName} is null, blank, or
     *                                  contains path separators
     */
    private static void requireTableName(String tableName) {
        if (tableName == null || tableName.isBlank() || tableName.contains("/") || tableName.contains("\\")
                || tableName.equals(".") || tableName.equals("..")) {
            throw new IllegalArgumentException("invalid table name: " + tableName);
        }
    }

    /**
     * Formats the versioned catalog filename {@code 
     * 
    <table>
     * -v<version>.json}.
     *
     * @param tableName table name
     * @param version   catalog version number
     * @return catalog filename
     */
    private static String catalogFileName(String tableName, long version) {
        return "%s-v%d.json".formatted(tableName, version);
    }

    /**
     * Atomically moves {@code source} to {@code target}, replacing any existing
     * file.
     *
     * @param source temporary file path
     * @param target final file path
     * @throws IOException if the atomic move fails or is unsupported
     */
    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic move not supported on this file system", exception);
        }
    }

    /**
     * Deletes temporary or uncommitted files left by a failed write. A failed
     * delete is logged and not rethrown, so the original write failure reaches the
     * caller.
     *
     * @param paths files to delete; missing files are ignored
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

    /**
     * Computes elapsed milliseconds since {@code started}.
     *
     * @param started starting {@link System#nanoTime()} value
     * @return elapsed time in milliseconds
     */
    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    // --- Internal Helper Records ---

    /**
     * Associates a parsed numerical catalog version with its file path.
     *
     * @param version parsed catalog version number
     * @param path    catalog file path
     */
    private record VersionedFile(long version, Path path) {
    }

    // --- Catalog & Storage Data Models ---

    /**
     * Immutable versioned table catalog snapshot.
     *
     * @param table      table name
     * @param version    monotonically increasing catalog version (0 at creation)
     * @param schema     ordered column definitions
     * @param partitions ordered immutable partition descriptors
     */
    public record Catalog(String table, long version, List<Column> schema, List<Partition> partitions) {

        /**
         * Creates an immutable catalog snapshot with defensive copies of the schema and
         * partition lists.
         */
        public Catalog {
            schema = schema == null ? List.of() : List.copyOf(schema);
            partitions = partitions == null ? List.of() : List.copyOf(partitions);
        }

        /**
         * Returns a new catalog snapshot at {@code version + 1} with {@code added}
         * appended after this snapshot's existing partitions.
         *
         * @param added partitions written by a {@code COPY} operation
         * @return next catalog version containing all existing and added partitions
         */
        public Catalog withAppendedPartitions(List<Partition> added) {
            List<Partition> combined = new ArrayList<>(partitions.size() + added.size());
            combined.addAll(partitions);
            combined.addAll(added);
            return new Catalog(table, version + 1, schema, combined);
        }

        /**
         * Returns the schema converted to {@link ColumnSpec} records in column order.
         *
         * @return immutable list of column specifications
         */
        public List<ColumnSpec> columnSpecs() {
            return schema.stream()
                    .map(column -> new ColumnSpec(column.name(), ColumnType.valueOf(column.type())))
                    .toList();
        }
    }

    /**
     * Immutable column definition stored in a table catalog.
     *
     * @param name column name
     * @param type column type name matching {@link ColumnType}
     */
    public record Column(
            @JsonProperty("column_name") String name,
            @JsonProperty("column_type") String type) {
    }

    /**
     * Immutable descriptor of a written partition file and its per-column
     * statistics.
     *
     * @param path       relative path to the partition file from
     *                   {@code dataDirectory}
     * @param rowCount   number of rows stored in the partition
     * @param statistics per-column min/max summaries keyed by column name
     */
    public record Partition(String path, int rowCount, Map<String, Statistics> statistics) {

        /**
         * Creates an immutable partition descriptor with a defensive copy of its
         * statistics map.
         */
        public Partition {
            statistics = statistics == null ? Map.of() : Map.copyOf(statistics);
        }
    }

    /**
     * Immutable string-serialized minimum and maximum values for a single column in
     * a partition.
     *
     * @param min minimum value serialized as a string
     * @param max maximum value serialized as a string
     */
    public record Statistics(String min, String max) {
    }

    /**
     * Pair of typed minimum and maximum values computed over a column's values.
     *
     * @param min minimum column value
     * @param max maximum column value
     */
    public record MinMax(Object min, Object max) {
    }
}
