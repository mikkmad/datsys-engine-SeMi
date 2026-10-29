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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
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
 *
 * <p>
 * Partition files are reclaimed by reference: a file is deleted only once no
 * retained catalog version (the newest {@link #MAX_RETAINED_CATALOG_VERSIONS})
 * and no pinned version still lists it. Pinning, releasing, committing and the
 * retention window all update under the per-table lock of
 * {@code catalogs.compute}, so the class is safe for concurrent statements.
 * Orphans left by a crash are swept once at startup.
 *
 * <p>
 * Concurrent writers follow first-committer-wins: a {@code COPY} or
 * {@code DELETE} commits only if the table is still at the version of its
 * snapshot, otherwise it aborts with {@link ConflictException} and its
 * uncommitted partition files are deleted immediately.
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

    // --- Version Retention & Readers (mutated only inside catalogs.compute) ---
    private final Map<String, List<Catalog>> retainedVersions = new ConcurrentHashMap<>();
    private final Map<PinKey, PinnedVersion> pinnedVersions = new ConcurrentHashMap<>();

    // --- Table Directory Lifecycle ---
    private final Object tableDirectoryLock = new Object();

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
     * Pins the current committed catalog version of a table for the duration of a
     * statement by incrementing that version's reader count. Partition files of a
     * pinned version are never garbage-collected. Every successful call must be
     * matched by exactly one {@link #releaseSnapshot(Catalog)}.
     *
     * @param tableName name of the table to pin
     * @return immutable catalog snapshot at the latest committed version
     * @throws IllegalArgumentException if the table does not exist or
     *                                  {@code tableName} is null
     */
    public Catalog pinSnapshot(String tableName) {
        if (tableName == null) {
            throw new IllegalArgumentException("unknown table: null");
        }
        AtomicReference<Catalog> pinned = new AtomicReference<>();
        catalogs.computeIfPresent(tableName, (name, current) -> {
            pinnedVersions.merge(new PinKey(name, current.version()), new PinnedVersion(current, 1),
                    (existing, added) -> new PinnedVersion(existing.catalog(), existing.readers() + 1));
            pinned.set(current);
            return current;
        });
        if (pinned.get() == null) {
            throw new IllegalArgumentException("unknown table: " + tableName);
        }
        return pinned.get();
    }

    /**
     * Releases one reader of a version pinned by {@link #pinSnapshot(String)}. When
     * the last reader of a version outside the retention window releases it, the
     * partition files no retained or pinned version references are deleted.
     *
     * @param snapshot catalog snapshot previously returned by
     *                 {@link #pinSnapshot(String)}
     * @throws IllegalArgumentException if {@code snapshot} is null
     * @throws IllegalStateException    if {@code snapshot} is not currently pinned
     */
    public void releaseSnapshot(Catalog snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        Set<String> unreachable = new HashSet<>();
        catalogs.compute(snapshot.table(), (name, current) -> {
            boolean lastReader = decrementReaders(new PinKey(name, snapshot.version()));
            if (lastReader && !isRetained(name, snapshot.version())) {
                unreachable.addAll(collectUnreachablePartitions(name, List.of(snapshot)));
            }
            return current;
        });
        deletePartitionFiles(snapshot.table(), unreachable);
    }

    /**
     * Returns the number of running statements that currently pin a version.
     *
     * @param tableName table name
     * @param version   catalog version
     * @return reader count, or 0 if the version is not pinned
     */
    public int pinnedReaderCount(String tableName, long version) {
        PinnedVersion pinned = pinnedVersions.get(new PinKey(tableName, version));
        return pinned == null ? 0 : pinned.readers();
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
     * @throws IllegalStateException    if a partition file cannot be written or the
     *                                  catalog cannot be persisted
     * @throws ConflictException        if the table's version advanced since
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
     * Deletes rows copy-on-write: partitions outside {@code rewriteSet} are carried
     * over by reference, every partition in it is re-read and its surviving rows
     * are written to a new partition file, and the resulting partition list is
     * committed as the next version. Original partition files are never modified.
     *
     * <p>
     * A partition in the rewrite set without any matching row is carried over
     * unchanged; a partition whose rows all match is dropped. A new version is
     * committed even if no row matches. If the commit fails, the new partition
     * files written by this call are deleted and the table keeps its version.
     *
     * @param snapshot     pinned catalog snapshot of the target table
     * @param rewriteSet   zero-based partition numbers that may contain matches
     * @param rowMatcher   row test returning true for rows to delete
     * @return summary of deleted rows, rewritten and carried partitions, and the
     *         committed version
     * @throws IllegalArgumentException if any argument is null
     * @throws IllegalStateException    if a partition cannot be read or written
     * @throws ConflictException        if the table's version advanced since
     *                                  {@code snapshot} was taken
     */
    public DeleteResult deleteRows(Catalog snapshot, List<Integer> rewriteSet, Predicate<Object[]> rowMatcher) {
        if (snapshot == null || rewriteSet == null || rowMatcher == null) {
            throw new IllegalArgumentException("snapshot, rewrite set and row matcher must not be null");
        }
        LOGGER.debug("api=deleteRows table={} version={} rewriteSet={}", snapshot.table(), snapshot.version(),
                rewriteSet.size());

        Path tableDirectory = dataFilesDirectory.resolve(snapshot.table());
        List<Path> writtenFiles = new ArrayList<>();
        boolean committed = false;
        try {
            DeleteResult result = rewriteAndCommit(snapshot, Set.copyOf(rewriteSet), rowMatcher, tableDirectory,
                    writtenFiles);
            committed = true;
            return result;
        } finally {
            if (!committed) {
                discardWrittenPartitions(tableDirectory, writtenFiles);
            }
        }
    }

    /**
     * Atomically commits {@code next} on disk and in memory if the table's current
     * version still matches {@code base}, advances the table's retention window,
     * and deletes partition files that became unreachable.
     *
     * <p>
     * Validation, the atomic rename of the catalog file and the in-memory swap
     * form one critical section per table: {@code catalogs.compute} runs them
     * atomically for the table's key, so only one writer commits at a time. The
     * catalog is serialized to a uniquely named temporary file beforehand; a
     * writer that fails validation never renames it and the file is deleted.
     *
     * @param base expected current catalog snapshot, or {@code null} when creating
     *             version 0
     * @param next new catalog version to persist and publish
     * @throws IllegalArgumentException if {@code next} is null or creating a table
     *                                  that already exists
     * @throws IllegalStateException    if persistence fails
     * @throws ConflictException        if the current version does not match
     *                                  {@code base}
     */
    public void commitCatalog(Catalog base, Catalog next) {
        if (next == null) {
            throw new IllegalArgumentException("next catalog must not be null");
        }

        Path target = catalogsDirectory.resolve(catalogFileName(next.table(), next.version()));
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Set<String> unreachable = new HashSet<>();

        try {
            writeTemporaryCatalog(next, temporary);
            catalogs.compute(next.table(), (tableName, current) -> {
                validateCommitBase(tableName, base, current);
                publishCatalogAtomically(next, temporary, target);
                List<Catalog> dropped = advanceRetentionWindow(tableName, next);
                unreachable.addAll(collectUnreachablePartitions(tableName, dropped));
                return next;
            });
        } finally {
            cleanupTemporaryFiles(List.of(temporary));
        }

        deletePartitionFiles(next.table(), unreachable);
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
     * Scans {@code catalogs/}, loads the newest retained catalog versions of every
     * table, publishes the highest one as the table's current catalog, and sweeps
     * files left behind by a crash. Non-matching filenames are ignored.
     *
     * @throws IOException if listing or reading catalog files fails
     */
    private void loadCatalogs() throws IOException {
        sweepTemporaryFiles(catalogsDirectory);

        for (List<VersionedFile> tableFiles : listCatalogFilesByTable().values()) {
            List<Catalog> retained = readRetainedCatalogs(tableFiles);
            Catalog latest = retained.getLast();
            catalogs.put(latest.table(), latest);
            retainedVersions.put(latest.table(), retained);
            sweepOrphanedPartitions(latest.table(), retained);
            LOGGER.debug("table={} version={} retainedVersions={} loaded", latest.table(), latest.version(),
                    retained.size());
        }
    }

    /**
     * Groups the versioned catalog files in {@code catalogs/} by table name.
     *
     * @return map from table name to its catalog files, in no particular order
     * @throws IOException if the directory cannot be listed
     */
    private Map<String, List<VersionedFile>> listCatalogFilesByTable() throws IOException {
        Map<String, List<VersionedFile>> filesByTable = new HashMap<>();

        try (Stream<Path> paths = Files.list(catalogsDirectory)) {
            for (Path path : paths.toList()) {
                Matcher matcher = CATALOG_FILE_PATTERN.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    filesByTable.computeIfAbsent(matcher.group(1), name -> new ArrayList<>())
                            .add(new VersionedFile(Long.parseLong(matcher.group(2)), path));
                }
            }
        }
        return filesByTable;
    }

    /**
     * Reads the newest {@link #MAX_RETAINED_CATALOG_VERSIONS} catalog files of one
     * table in ascending version order.
     *
     * @param tableFiles all catalog files of one table
     * @return retained catalogs, oldest first; the last entry is the current
     *         version
     * @throws IOException if a catalog file cannot be read
     */
    private List<Catalog> readRetainedCatalogs(List<VersionedFile> tableFiles) throws IOException {
        List<VersionedFile> sorted = tableFiles.stream()
                .sorted(Comparator.comparingLong(VersionedFile::version))
                .toList();
        List<VersionedFile> newest = sorted.subList(
                Math.max(0, sorted.size() - MAX_RETAINED_CATALOG_VERSIONS), sorted.size());

        List<Catalog> retained = new ArrayList<>(newest.size());
        for (VersionedFile file : newest) {
            retained.add(objectMapper.readValue(file.path().toFile(), Catalog.class));
        }
        return List.copyOf(retained);
    }

    /**
     * Deletes partition files of {@code tableName} that no retained catalog lists,
     * plus temporary files, in {@code data/<table>/}. Runs only at startup, when no
     * statement can be writing uncommitted partitions; these are the orphans of a
     * {@code COPY} or {@code DELETE} that crashed before its commit.
     *
     * @param tableName table whose data directory is swept
     * @param retained  retained catalog versions of the table
     * @throws IOException if the data directory cannot be listed
     */
    private void sweepOrphanedPartitions(String tableName, List<Catalog> retained) throws IOException {
        Path tableDirectory = dataFilesDirectory.resolve(tableName);
        if (!Files.isDirectory(tableDirectory)) {
            return;
        }
        sweepTemporaryFiles(tableDirectory);

        Set<String> referenced = partitionPaths(retained);
        Set<String> orphaned = new HashSet<>();
        try (Stream<Path> paths = Files.list(tableDirectory)) {
            paths.map(path -> dataDirectory.relativize(path).toString())
                    .filter(relativePath -> relativePath.endsWith(".dat") && !referenced.contains(relativePath))
                    .forEach(orphaned::add);
        }
        deletePartitionFiles(tableName, orphaned);
    }

    /**
     * Deletes leftover {@code *.tmp-*} files of interrupted atomic writes in a
     * directory. Failures are logged and not rethrown.
     *
     * @param directory directory to sweep
     * @throws IOException if the directory cannot be listed
     */
    private static void sweepTemporaryFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            List<Path> temporaryFiles = paths
                    .filter(path -> path.getFileName().toString().contains(".tmp-"))
                    .toList();
            temporaryFiles.forEach(path -> LOGGER.debug("api=sweepTemporaryFiles file={} deleted",
                    LogSanitizer.sanitize(path.getFileName())));
            cleanupTemporaryFiles(temporaryFiles);
        }
    }

    /**
     * Adds {@code next} to the table's in-memory retention window and removes the
     * versions that fall out of it. Must run inside {@code catalogs.compute} for
     * the table.
     *
     * @param tableName table being committed
     * @param next      newly committed catalog version
     * @return catalog versions that left the retention window, oldest first
     */
    private List<Catalog> advanceRetentionWindow(String tableName, Catalog next) {
        List<Catalog> window = new ArrayList<>(retainedVersions.getOrDefault(tableName, List.of()));
        window.add(next);

        List<Catalog> dropped = new ArrayList<>();
        while (window.size() > MAX_RETAINED_CATALOG_VERSIONS) {
            dropped.add(window.removeFirst());
        }
        retainedVersions.put(tableName, List.copyOf(window));
        return dropped;
    }

    /**
     * Returns whether a version of a table is still inside its retention window.
     *
     * @param tableName table name
     * @param version   catalog version
     * @return true if the version is retained
     */
    private boolean isRetained(String tableName, long version) {
        return retainedVersions.getOrDefault(tableName, List.of()).stream()
                .anyMatch(catalog -> catalog.version() == version);
    }

    /**
     * Decrements the reader count of a pinned version and forgets the version once
     * its last reader is gone. Must run inside {@code catalogs.compute} for the
     * table.
     *
     * @param key pinned table version
     * @return true if this call released the last reader
     * @throws IllegalStateException if the version is not pinned
     */
    private boolean decrementReaders(PinKey key) {
        PinnedVersion pinned = pinnedVersions.get(key);
        if (pinned == null) {
            throw new IllegalStateException(
                    "version " + key.version() + " of table " + key.table() + " is not pinned");
        }
        if (pinned.readers() > 1) {
            pinnedVersions.put(key, new PinnedVersion(pinned.catalog(), pinned.readers() - 1));
            return false;
        }
        pinnedVersions.remove(key);
        return true;
    }

    /**
     * Collects the partition paths of {@code candidates} that no retained version
     * and no pinned version of the table still references. Must run inside
     * {@code catalogs.compute} for the table so the reference sets are stable.
     *
     * @param tableName  table name
     * @param candidates catalog versions that just became unreachable
     * @return relative partition paths that can safely be deleted
     */
    private Set<String> collectUnreachablePartitions(String tableName, List<Catalog> candidates) {
        Set<String> unreachable = partitionPaths(candidates);
        if (unreachable.isEmpty()) {
            return unreachable;
        }
        unreachable.removeAll(partitionPaths(retainedVersions.getOrDefault(tableName, List.of())));
        pinnedVersions.forEach((key, pinned) -> {
            if (key.table().equals(tableName)) {
                unreachable.removeAll(partitionPaths(List.of(pinned.catalog())));
            }
        });
        return unreachable;
    }

    /**
     * Returns the relative paths of all partitions listed by the given catalogs.
     *
     * @param versions catalog versions to inspect
     * @return mutable set of relative partition paths
     */
    private static Set<String> partitionPaths(List<Catalog> versions) {
        Set<String> paths = new HashSet<>();
        for (Catalog catalog : versions) {
            catalog.partitions().forEach(partition -> paths.add(partition.path()));
        }
        return paths;
    }

    /**
     * Deletes unreachable partition files. Failures are logged and not rethrown so
     * garbage collection never rolls back an already committed version.
     *
     * @param tableName     table owning the partitions
     * @param relativePaths partition paths relative to the data directory
     */
    private void deletePartitionFiles(String tableName, Set<String> relativePaths) {
        for (String relativePath : relativePaths) {
            try {
                Files.deleteIfExists(dataDirectory.resolve(relativePath));
                LOGGER.debug("api=collectGarbage table={} partition={} deleted", tableName,
                        LogSanitizer.sanitize(relativePath));
            } catch (IOException exception) {
                LOGGER.error("api=collectGarbage table={} partition={} error={}", tableName,
                        LogSanitizer.sanitize(relativePath), LogSanitizer.sanitize(exception.getMessage()));
            }
        }
    }

    /**
     * Rewrites the partitions of the rewrite set, builds the next partition list
     * and commits it as the next version.
     *
     * @param snapshot       pinned catalog snapshot of the target table
     * @param rewriteSet     partition numbers that may contain matches
     * @param rowMatcher     row test returning true for rows to delete
     * @param tableDirectory directory receiving new partition files
     * @param writtenFiles   collector recording new partition files for rollback
     * @return summary of the committed delete
     * @throws IllegalStateException if a partition cannot be read or written, or
     *                               the commit fails
     */
    private DeleteResult rewriteAndCommit(Catalog snapshot, Set<Integer> rewriteSet, Predicate<Object[]> rowMatcher,
            Path tableDirectory, List<Path> writtenFiles) {
        List<Partition> nextPartitions = new ArrayList<>();
        long rowsDeleted = 0;
        int partitionsRewritten = 0;

        for (int partitionNumber = 0; partitionNumber < snapshot.partitions().size(); partitionNumber++) {
            PartitionRewrite rewrite = rewriteSet.contains(partitionNumber)
                    ? rewritePartition(snapshot, partitionNumber, rowMatcher, tableDirectory, writtenFiles)
                    : carryPartition(snapshot, partitionNumber);
            rewrite.replacement().ifPresent(nextPartitions::add);
            rowsDeleted += rewrite.rowsDeleted();
            partitionsRewritten += rewrite.rowsDeleted() > 0 ? 1 : 0;
        }

        Catalog next = snapshot.withPartitions(nextPartitions);
        commitCatalog(snapshot, next);
        int partitionsCarried = snapshot.partitions().size() - partitionsRewritten;
        return new DeleteResult(rowsDeleted, partitionsRewritten, partitionsCarried, next.version());
    }

    /**
     * Carries a partition outside the rewrite set over by reference.
     *
     * @param snapshot        pinned catalog snapshot
     * @param partitionNumber zero-based partition number in the snapshot
     * @return rewrite outcome keeping the original partition entry
     */
    private static PartitionRewrite carryPartition(Catalog snapshot, int partitionNumber) {
        LOGGER.debug("table={} partition={} decision=CARRIED reason=pruned", snapshot.table(), partitionNumber);
        return new PartitionRewrite(Optional.of(snapshot.partitions().get(partitionNumber)), 0);
    }

    /**
     * Copies the rows of one partition that do not match into a new partition. A
     * partition without matches is carried over; a fully matched one is dropped.
     *
     * @param snapshot        pinned catalog snapshot
     * @param partitionNumber zero-based partition number in the snapshot
     * @param rowMatcher      row test returning true for rows to delete
     * @param tableDirectory  directory receiving the new partition file
     * @param writtenFiles    collector recording new partition files
     * @return replacement partition (if any) and the number of rows removed
     * @throws IllegalStateException if the partition cannot be read or written
     */
    private PartitionRewrite rewritePartition(Catalog snapshot, int partitionNumber, Predicate<Object[]> rowMatcher,
            Path tableDirectory, List<Path> writtenFiles) {
        Partition original = snapshot.partitions().get(partitionNumber);
        List<Object[]> rows = readPartition(snapshot, original);
        List<Object[]> survivors = rows.stream().filter(rowMatcher.negate()).toList();
        int rowsDeleted = rows.size() - survivors.size();

        if (rowsDeleted == 0) {
            LOGGER.debug("table={} partition={} decision=CARRIED reason=noMatch", snapshot.table(), partitionNumber);
            return new PartitionRewrite(Optional.of(original), 0);
        }
        LOGGER.debug("table={} partition={} decision=REWRITTEN rowsDeleted={} rowsKept={}", snapshot.table(),
                partitionNumber, rowsDeleted, survivors.size());
        if (survivors.isEmpty()) {
            return new PartitionRewrite(Optional.empty(), rowsDeleted);
        }
        return new PartitionRewrite(Optional.of(writePartitionFile(snapshot.table(), tableDirectory, partitionNumber,
                survivors, snapshot.schema(), writtenFiles)), rowsDeleted);
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
            reserveTemporaryFile(tableDirectory, temporaryPath);
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
     * Creates the table directory if needed and an empty temporary file in it, as
     * one step under {@code tableDirectoryLock}. Once the temporary file exists the
     * directory is non-empty, so a concurrent writer that aborts cannot remove it
     * in {@link #discardWrittenPartitions(Path, List)}.
     *
     * @param tableDirectory directory receiving the partition file
     * @param temporaryPath  temporary partition file to create
     * @throws IOException if the directory or file cannot be created
     */
    private void reserveTemporaryFile(Path tableDirectory, Path temporaryPath) throws IOException {
        synchronized (tableDirectoryLock) {
            Files.createDirectories(tableDirectory);
            Files.createFile(temporaryPath);
        }
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
     * Deletes only the partition files written by a failed or conflicting
     * {@code COPY} or {@code DELETE}, and removes {@code data/<table>/} only if it
     * is now empty. The emptiness check and removal run under
     * {@code tableDirectoryLock}, so a concurrent writer reserving a file in the
     * directory never sees it vanish.
     *
     * @param tableDirectory directory holding the table's partition files
     * @param writtenFiles   partition files written by the aborted statement
     */
    private void discardWrittenPartitions(Path tableDirectory, List<Path> writtenFiles) {
        cleanupTemporaryFiles(writtenFiles);
        synchronized (tableDirectoryLock) {
            removeDirectoryIfEmpty(tableDirectory);
        }
    }

    /**
     * Removes a directory if it exists and is empty. Failures are logged and not
     * rethrown so cleanup never masks the original failure.
     *
     * @param directory directory to remove
     */
    private static void removeDirectoryIfEmpty(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> remaining = Files.list(directory)) {
            if (remaining.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        } catch (IOException exception) {
            LOGGER.error("api=discardWrittenPartitions dir={} error={}", LogSanitizer.sanitize(directory),
                    LogSanitizer.sanitize(exception.getMessage()));
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
     * committing a new version (first committer wins). Must run inside the
     * table's commit critical section.
     *
     * @param tableName table being committed
     * @param base      expected base catalog, or {@code null} for initial table
     *                  creation
     * @param current   current in-memory catalog entry
     * @throws IllegalArgumentException if creating a table that already exists
     * @throws IllegalStateException    if updating a table that has no current
     *                                  catalog
     * @throws ConflictException        if another statement committed since
     *                                  {@code base} was taken
     */
    private static void validateCommitBase(String tableName, Catalog base, Catalog current) {
        if (base == null) {
            if (current != null) {
                throw new IllegalArgumentException("table already exists: " + tableName);
            }
            return;
        }
        if (current == null) {
            throw new IllegalStateException("unknown table at commit: " + tableName);
        }
        if (current.version() != base.version()) {
            throw new ConflictException(tableName, base.version(), current.version());
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

    /**
     * Identifies one catalog version of one table in the reader-count map.
     *
     * @param table   table name
     * @param version catalog version
     */
    private record PinKey(String table, long version) {
    }

    /**
     * A pinned catalog version together with its number of running readers.
     *
     * @param catalog pinned catalog snapshot
     * @param readers number of statements currently pinning it; always positive
     */
    private record PinnedVersion(Catalog catalog, int readers) {
    }

    /**
     * Outcome of rewriting or carrying one partition during a {@code DELETE}.
     *
     * @param replacement partition entry for the next version, or empty if the
     *                    partition was dropped
     * @param rowsDeleted number of rows removed from the partition
     */
    private record PartitionRewrite(Optional<Partition> replacement, int rowsDeleted) {
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
            return withPartitions(combined);
        }

        /**
         * Returns a new catalog snapshot at {@code version + 1} whose partition list is
         * replaced by {@code nextPartitions}.
         *
         * @param nextPartitions complete partition list of the next version
         * @return next catalog version with the same table name and schema
         */
        public Catalog withPartitions(List<Partition> nextPartitions) {
            return new Catalog(table, version + 1, schema, nextPartitions);
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
