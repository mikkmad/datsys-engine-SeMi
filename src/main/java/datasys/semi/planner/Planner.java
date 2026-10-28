package datasys.semi.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.engine.StorageEngine.Partition;
import datasys.semi.engine.StorageEngine.Statistics;
import datasys.semi.models.BoundPredicate;
import datasys.semi.models.DeleteStatement;
import datasys.semi.models.Predicate;
import datasys.semi.models.ScanStats;
import datasys.semi.models.SelectStatement;
import datasys.semi.operators.FilterOperator;
import datasys.semi.operators.Operator;
import datasys.semi.operators.ProjectOperator;
import datasys.semi.operators.ScanOperator;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.util.LogSanitizer;

/**
 * Transforms bound SQL statements into executable Volcano operator pipelines
 * against a pinned {@link Catalog} snapshot.
 *
 * <p>
 * Partition pruning occurs here before any data file is opened. For queries
 * with a WHERE predicate, min/max partition statistics from the pinned snapshot
 * are evaluated, pruning decisions are logged, and surviving partition numbers
 * are passed into the operator tree.
 */
public final class Planner {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);
    private static final String DEFAULT_STATEMENT_NUMBER = "0";

    // --- Collaborators ---
    private final StorageEngine engine;

    // --- Observable Metrics ---
    private ScanStats lastScanStats = new ScanStats(0, 0, 0, 0);

    /**
     * Constructs a new Planner backed by the specified storage engine.
     *
     * @param engine storage engine managing catalogs and data files
     * @throws IllegalArgumentException if {@code engine} is null
     */
    public Planner(StorageEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        if (MDC.get("statementNumber") == null) {
            MDC.put("statementNumber", DEFAULT_STATEMENT_NUMBER);
        }
        this.engine = engine;
    }

    /**
     * Creates an operator execution plan for a {@code SELECT} statement against a
     * pinned {@link Catalog} snapshot.
     *
     * @param statement the bound select statement to plan
     * @param snapshot  pinned catalog snapshot of the target table
     * @return root operator of the planned pipeline
     * @throws IllegalArgumentException if {@code statement} or {@code snapshot} is
     *                                  null,
     *                                  the snapshot does not match the statement's
     *                                  table,
     *                                  or the statement references an unknown
     *                                  column
     */
    public Operator plan(SelectStatement statement, Catalog snapshot) {
        if (statement == null || snapshot == null) {
            throw new IllegalArgumentException("statement and snapshot must not be null");
        }
        if (!snapshot.table().equals(statement.tableName())) {
            throw new IllegalArgumentException("unknown table: " + statement.tableName());
        }

        List<ColumnSpec> schema = snapshot.columnSpecs();
        Operator root = statement.where().isEmpty()
                ? planWithoutPredicate(snapshot)
                : planWithPredicate(snapshot, schema, statement.where().get());

        if (statement.columns().isPresent()) {
            List<String> projectedColumnNames = statement.columns().get();
            int[] columnIndices = new int[projectedColumnNames.size()];
            for (int index = 0; index < projectedColumnNames.size(); index++) {
                columnIndices[index] = resolveColumnIndex(schema, projectedColumnNames.get(index));
            }
            root = new ProjectOperator(root, columnIndices);
        }

        return root;
    }

    /**
     * Creates a copy-on-write plan for a {@code DELETE} statement against a pinned
     * {@link Catalog} snapshot. The rewrite set comes from
     * {@link #partitionsThatMayMatch}, the same helper SELECT prunes with, in the
     * opposite role: partitions that may contain a match are rewritten, all others
     * are carried over. Without a WHERE clause every partition is rewritten and
     * every row matches.
     *
     * @param statement the bound delete statement to plan
     * @param snapshot  pinned catalog snapshot of the target table
     * @return rewrite set and row matcher for the storage engine
     * @throws IllegalArgumentException if {@code statement} or {@code snapshot} is
     *                                  null, the snapshot does not match the
     *                                  statement's table, or the predicate column
     *                                  is unknown
     */
    public DeletePlan planDelete(DeleteStatement statement, Catalog snapshot) {
        if (statement == null || snapshot == null) {
            throw new IllegalArgumentException("statement and snapshot must not be null");
        }
        if (!snapshot.table().equals(statement.tableName())) {
            throw new IllegalArgumentException("unknown table: " + statement.tableName());
        }
        if (statement.where().isEmpty()) {
            List<Integer> allPartitions = IntStream.range(0, snapshot.partitions().size()).boxed().toList();
            return new DeletePlan(allPartitions, row -> true);
        }

        Predicate predicate = statement.where().get();
        BoundPredicate boundPredicate = new BoundPredicate(
                resolveColumnIndex(snapshot.columnSpecs(), predicate.columnName()),
                predicate.comparison(), predicate.constant());
        return new DeletePlan(partitionsThatMayMatch(snapshot, predicate), boundPredicate::matches);
    }

    /**
     * Decides from the snapshot's min/max summaries which partitions may contain
     * rows satisfying {@code predicate}. This is the single pruning decision shared
     * by SELECT (partitions to read) and DELETE (partitions to rewrite); every
     * decision is logged.
     *
     * @param snapshot  pinned catalog snapshot of the table
     * @param predicate filter predicate with a constant of the column's type
     * @return partition numbers that may contain matches, in ascending order
     * @throws IllegalArgumentException if an argument is null or the predicate
     *                                  column is unknown
     */
    public List<Integer> partitionsThatMayMatch(Catalog snapshot, Predicate predicate) {
        if (snapshot == null || predicate == null) {
            throw new IllegalArgumentException("snapshot and predicate must not be null");
        }
        List<ColumnSpec> schema = snapshot.columnSpecs();
        ColumnSpec column = schema.get(resolveColumnIndex(schema, predicate.columnName()));
        return prunePartitions(snapshot.table(), snapshot.partitions(), column, predicate);
    }

    /**
     * Returns the scan statistics calculated during the most recent {@link #plan}
     * call.
     *
     * @return the last scan statistics
     */
    public ScanStats lastScanStats() {
        return lastScanStats;
    }

    /**
     * Builds a bare scan plan when no predicate is present.
     *
     * @param snapshot pinned catalog snapshot of the table being scanned
     * @return scan operator reading all partitions in the snapshot
     */
    private Operator planWithoutPredicate(Catalog snapshot) {
        int totalPartitions = snapshot.partitions().size();
        List<Integer> allPartitions = new ArrayList<>(totalPartitions);
        for (int index = 0; index < totalPartitions; index++) {
            allPartitions.add(index);
            LOGGER.debug("table={} partition={} decision=READ reason=noPredicate", snapshot.table(), index);
        }

        lastScanStats = new ScanStats(totalPartitions, totalPartitions, 0, snapshot.version());
        return new ScanOperator(engine, snapshot, allPartitions);
    }

    /**
     * Builds a filter-over-scan plan evaluating partition summaries for pruning.
     *
     * @param snapshot  pinned catalog snapshot of the table being scanned
     * @param schema    table schema from the snapshot
     * @param predicate filter predicate to evaluate
     * @return filter operator wrapping scan operator
     */
    private Operator planWithPredicate(Catalog snapshot, List<ColumnSpec> schema, Predicate predicate) {
        int columnIndex = resolveColumnIndex(schema, predicate.columnName());

        List<Integer> survivingPartitions = partitionsThatMayMatch(snapshot, predicate);
        int totalPartitions = snapshot.partitions().size();
        int readCount = survivingPartitions.size();
        int prunedCount = totalPartitions - readCount;

        lastScanStats = new ScanStats(totalPartitions, readCount, prunedCount, snapshot.version());

        BoundPredicate boundPredicate = new BoundPredicate(
                columnIndex, predicate.comparison(), predicate.constant());
        ScanOperator scanOperator = new ScanOperator(engine, snapshot, survivingPartitions);
        return new FilterOperator(scanOperator, boundPredicate);
    }

    /**
     * Evaluates partition min/max summaries against the predicate to prune
     * irrelevant partitions.
     *
     * @param tableName  table name for logging
     * @param partitions partition metadata list from the pinned snapshot
     * @param column     predicate column specification
     * @param predicate  the filter predicate
     * @return surviving partition numbers in ascending order
     */
    private List<Integer> prunePartitions(String tableName, List<Partition> partitions,
            ColumnSpec column, Predicate predicate) {
        List<Integer> survivingPartitions = new ArrayList<>();

        for (int partitionNumber = 0; partitionNumber < partitions.size(); partitionNumber++) {
            Partition partition = partitions.get(partitionNumber);
            Statistics statistics = partition.statistics().get(column.name());

            if (statistics == null) {
                LOGGER.debug("table={} partition={} decision=READ reason=missingStats", tableName, partitionNumber);
                survivingPartitions.add(partitionNumber);
                continue;
            }

            Object min = StorageEngine.parseStatistic(statistics.min(), column.type());
            Object max = StorageEngine.parseStatistic(statistics.max(), column.type());
            boolean shouldPrune = engine.shouldPrune(
                    column.type(), predicate.comparison(), predicate.constant(), min, max);

            LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    tableName, predicate.columnName(), predicate.comparison(),
                    LogSanitizer.sanitize(predicate.constant()),
                    partitionNumber, LogSanitizer.sanitize(statistics.min()), LogSanitizer.sanitize(statistics.max()),
                    shouldPrune ? "PRUNED" : "READ");

            if (!shouldPrune) {
                survivingPartitions.add(partitionNumber);
            }
        }

        return survivingPartitions;
    }

    /**
     * Resolves a column name to its zero-based position in the schema.
     *
     * @param schema     the table schema
     * @param columnName the column name to find
     * @return zero-based column position
     * @throws IllegalArgumentException if column name is not found
     */
    private static int resolveColumnIndex(List<ColumnSpec> schema, String columnName) {
        for (int index = 0; index < schema.size(); index++) {
            if (schema.get(index).name().equals(columnName)) {
                return index;
            }
        }
        throw new IllegalArgumentException("unknown column: " + columnName);
    }
}
