package datasys.semi.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
import datasys.semi.models.JoinPredicate;
import datasys.semi.models.Predicate;
import datasys.semi.models.ScanStats;
import datasys.semi.models.SelectStatement;
import datasys.semi.operators.FilterOperator;
import datasys.semi.operators.HashJoinOperator;
import datasys.semi.operators.Operator;
import datasys.semi.operators.ProjectOperator;
import datasys.semi.operators.ScanOperator;
import datasys.semi.schema.ColumnResolver;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ResolvedColumn;
import datasys.semi.schema.TableSchema;
import datasys.semi.util.LogSanitizer;

/**
 * Transforms bound SQL statements into executable Volcano operator pipelines
 * against pinned {@link Catalog} snapshots, one per {@code FROM} table.
 *
 * <p>
 * Partition pruning occurs here before any data file is opened. For each table
 * with WHERE filters, min/max partition statistics from its pinned snapshot are
 * evaluated, pruning decisions are logged, and surviving partition numbers are
 * passed into that table's scan. Multi-table queries are joined left-deep in
 * {@code FROM}-list order with {@link HashJoinOperator}s; filters stay below
 * the joins. Not thread-safe: the per-table scan statistics of the last plan
 * are kept as mutable state, so each executor owns its planner.
 */
public final class Planner {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);
    private static final String DEFAULT_STATEMENT_NUMBER = "0";
    private static final ScanStats EMPTY_SCAN_STATS = new ScanStats(0, 0, 0, 0);

    // --- Collaborators ---
    private final StorageEngine engine;

    // --- Observable Metrics ---
    private Map<String, ScanStats> lastScanStatsByTable = new LinkedHashMap<>();

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
     * Creates an operator execution plan for a single-table {@code SELECT}
     * statement against a pinned {@link Catalog} snapshot.
     *
     * @param statement the bound select statement to plan
     * @param snapshot  pinned catalog snapshot of the only {@code FROM} table
     * @return root operator of the planned pipeline
     * @throws IllegalArgumentException if {@code statement} or {@code snapshot} is
     *                                  null, the statement lists another or more
     *                                  than one table, or a column is unknown
     */
    public Operator plan(SelectStatement statement, Catalog snapshot) {
        if (statement == null || snapshot == null) {
            throw new IllegalArgumentException("statement and snapshot must not be null");
        }
        return plan(statement, List.of(snapshot));
    }

    /**
     * Creates an operator execution plan for a {@code SELECT} statement over one
     * or more tables, joined left-deep in {@code FROM}-list order.
     *
     * <p>
     * Each table gets its own pipeline: a scan of the partitions that survive
     * pruning, under one filter per WHERE filter on that table's columns. The
     * pipelines are then folded left to right: each new table probes a hash table
     * built from the tables joined so far, through the one join predicate that
     * connects it to them. Output rows are the table schemas concatenated in
     * {@code FROM}-list order, optionally projected onto a column list.
     *
     * @param statement the bound select statement to plan
     * @param snapshots pinned catalog snapshots in {@code FROM}-list order, one
     *                  per table
     * @return root operator of the planned pipeline
     * @throws IllegalArgumentException if an argument is null, the snapshots do
     *                                  not match the {@code FROM} list, a column
     *                                  is unknown, a table is not connected to the
     *                                  tables before it, or a table is connected
     *                                  by more than one join predicate
     */
    public Operator plan(SelectStatement statement, List<Catalog> snapshots) {
        if (statement == null || snapshots == null) {
            throw new IllegalArgumentException("statement and snapshots must not be null");
        }
        requireMatchingSnapshots(statement.tableNames(), snapshots);
        ColumnResolver resolver = new ColumnResolver(snapshots.stream()
                .map(snapshot -> new TableSchema(snapshot.table(), snapshot.columnSpecs()))
                .toList());

        lastScanStatsByTable = new LinkedHashMap<>();
        List<List<Predicate>> filtersByTable = assignFiltersToTables(statement.filters(), resolver,
                snapshots.size());
        Operator root = planTablePipeline(snapshots.getFirst(), filtersByTable.getFirst());
        for (int tablePosition = 1; tablePosition < snapshots.size(); tablePosition++) {
            Operator probe = planTablePipeline(snapshots.get(tablePosition), filtersByTable.get(tablePosition));
            root = planJoin(root, probe, tablePosition, statement, resolver);
        }

        if (statement.columns().isPresent()) {
            root = planProjection(root, statement.columns().get(), resolver);
        }
        return root;
    }

    /**
     * Verifies that the snapshots are exactly the {@code FROM}-list tables, in
     * order.
     *
     * @param tableNames tables in {@code FROM}-list order
     * @param snapshots  pinned catalog snapshots
     * @throws IllegalArgumentException if the counts differ or a snapshot is
     *                                  missing or for a different table
     */
    private static void requireMatchingSnapshots(List<String> tableNames, List<Catalog> snapshots) {
        if (snapshots.size() != tableNames.size()) {
            throw new IllegalArgumentException("SELECT over " + String.join(", ", tableNames)
                    + " needs one pinned snapshot per FROM table but got " + snapshots.size());
        }
        for (int position = 0; position < tableNames.size(); position++) {
            Catalog snapshot = snapshots.get(position);
            if (snapshot == null || !snapshot.table().equals(tableNames.get(position))) {
                throw new IllegalArgumentException("unknown table: " + tableNames.get(position));
            }
        }
    }

    /**
     * Groups the WHERE filters by the table that owns their column, so each
     * filter can be applied below the joins.
     *
     * @param filters    WHERE filters in source order
     * @param resolver   column resolver over the {@code FROM}-list schemas
     * @param tableCount number of {@code FROM} tables
     * @return one list of filters per table, in {@code FROM}-list order
     * @throws IllegalArgumentException if a filter column is unknown or ambiguous
     */
    private static List<List<Predicate>> assignFiltersToTables(List<Predicate> filters, ColumnResolver resolver,
            int tableCount) {
        List<List<Predicate>> filtersByTable = new ArrayList<>();
        for (int tablePosition = 0; tablePosition < tableCount; tablePosition++) {
            filtersByTable.add(new ArrayList<>());
        }
        for (Predicate filter : filters) {
            filtersByTable.get(resolver.resolve(filter.columnName()).tablePosition()).add(filter);
        }
        return filtersByTable;
    }

    /**
     * Builds one table's pipeline: a scan over the partitions that survive
     * pruning, with the table's filters stacked above it.
     *
     * @param snapshot pinned catalog snapshot of the table
     * @param filters  filters on this table's columns; may be empty
     * @return the table pipeline
     * @throws IllegalArgumentException if a filter column is unknown
     */
    private Operator planTablePipeline(Catalog snapshot, List<Predicate> filters) {
        return filters.isEmpty()
                ? planWithoutPredicate(snapshot)
                : planWithPredicates(snapshot, snapshot.columnSpecs(), filters);
    }

    /**
     * Joins the next table's pipeline onto the tables joined so far: the
     * intermediate result is the build side and the new table probes.
     *
     * @param joinedSoFar   pipeline producing the concatenated rows of tables
     *                      {@code 0..tablePosition-1}
     * @param probe         pipeline of the new table
     * @param tablePosition {@code FROM}-list position of the new table
     * @param statement     the select statement, for its join predicates and
     *                      table names
     * @param resolver      column resolver over the {@code FROM}-list schemas
     * @return hash join of the two pipelines
     * @throws IllegalArgumentException if no join predicate, or more than one,
     *                                  connects the new table to the earlier ones
     */
    private static Operator planJoin(Operator joinedSoFar, Operator probe, int tablePosition,
            SelectStatement statement, ColumnResolver resolver) {
        JoinPredicate connecting = findConnectingJoin(statement, tablePosition, resolver);
        ResolvedColumn left = resolver.resolve(connecting.leftColumn());
        ResolvedColumn right = resolver.resolve(connecting.rightColumn());
        boolean leftIsNewTable = left.tablePosition() == tablePosition;
        ResolvedColumn newTableSide = leftIsNewTable ? left : right;
        ResolvedColumn joinedSide = leftIsNewTable ? right : left;
        return new HashJoinOperator(joinedSoFar, probe, joinedSide.joinedIndex(),
                newTableSide.columnIndexInTable());
    }

    /**
     * Finds the join predicate that connects a table to the tables listed before
     * it. A predicate connects table {@code i} exactly when the later of its two
     * tables is {@code i}, so every predicate belongs to exactly one fold step.
     *
     * <p>
     * Simplification: only one connecting predicate per table is supported; a
     * second one is rejected rather than applied as a filter on the join output.
     *
     * @param statement     the select statement
     * @param tablePosition {@code FROM}-list position of the new table
     * @param resolver      column resolver over the {@code FROM}-list schemas
     * @return the single connecting join predicate
     * @throws IllegalArgumentException if no predicate or more than one predicate
     *                                  connects the table
     */
    private static JoinPredicate findConnectingJoin(SelectStatement statement, int tablePosition,
            ColumnResolver resolver) {
        List<JoinPredicate> connecting = statement.joins().stream()
                .filter(join -> laterTablePosition(join, resolver) == tablePosition)
                .toList();
        String tableName = statement.tableNames().get(tablePosition);
        if (connecting.isEmpty()) {
            String earlierTables = String.join(", ", statement.tableNames().subList(0, tablePosition));
            throw new IllegalArgumentException("table " + tableName
                    + " is not connected by a join predicate to the tables before it (" + earlierTables
                    + "); reorder the FROM list so that each table joins a table listed before it");
        }
        if (connecting.size() > 1) {
            throw new IllegalArgumentException("table " + tableName + " is connected by "
                    + connecting.size() + " join predicates (" + describeJoins(connecting)
                    + "); only one join predicate per table is supported");
        }
        return connecting.getFirst();
    }

    /**
     * Returns the {@code FROM}-list position of the later of a join's two tables.
     *
     * @param join     the join predicate
     * @param resolver column resolver over the {@code FROM}-list schemas
     * @return the larger of the two table positions
     * @throws IllegalArgumentException if a join column is unknown or ambiguous
     */
    private static int laterTablePosition(JoinPredicate join, ColumnResolver resolver) {
        return Math.max(resolver.resolve(join.leftColumn()).tablePosition(),
                resolver.resolve(join.rightColumn()).tablePosition());
    }

    /**
     * Formats join predicates for an error message.
     *
     * @param joins join predicates
     * @return predicates as {@code a = b} separated by {@code " and "}
     */
    private static String describeJoins(List<JoinPredicate> joins) {
        return joins.stream()
                .map(join -> join.leftColumn() + " = " + join.rightColumn())
                .collect(Collectors.joining(" and "));
    }

    /**
     * Wraps a pipeline in a projection onto the named columns of the joined row.
     *
     * @param child                pipeline producing joined rows
     * @param projectedColumnNames columns to keep, in output order
     * @param resolver             column resolver over the {@code FROM}-list
     *                             schemas
     * @return project operator over {@code child}
     * @throws IllegalArgumentException if a projected column is unknown or
     *                                  ambiguous
     */
    private static Operator planProjection(Operator child, List<String> projectedColumnNames,
            ColumnResolver resolver) {
        int[] columnIndices = new int[projectedColumnNames.size()];
        for (int index = 0; index < projectedColumnNames.size(); index++) {
            columnIndices[index] = resolver.resolve(projectedColumnNames.get(index)).joinedIndex();
        }
        return new ProjectOperator(child, columnIndices);
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
     * Returns the scan statistics of the first {@code FROM} table planned by the
     * most recent {@link #plan} call; for a single-table query, its only table.
     *
     * @return the first table's last scan statistics, or all zeros before any
     *         plan
     */
    public ScanStats lastScanStats() {
        return lastScanStatsByTable.isEmpty() ? EMPTY_SCAN_STATS : lastScanStatsByTable.values().iterator().next();
    }

    /**
     * Returns the scan statistics of every table planned by the most recent
     * {@link #plan} call.
     *
     * @return unmodifiable map from table name to its scan statistics, in
     *         {@code FROM}-list order
     */
    public Map<String, ScanStats> lastScanStatsByTable() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(lastScanStatsByTable));
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

        lastScanStatsByTable.put(snapshot.table(),
                new ScanStats(totalPartitions, totalPartitions, 0, snapshot.version()));
        return new ScanOperator(engine, snapshot, allPartitions);
    }

    /**
     * Builds a filter-over-scan plan evaluating partition summaries for pruning.
     * A partition is read only if every filter may match it; one
     * {@link FilterOperator} per filter is stacked above the scan, in WHERE
     * order.
     *
     * @param snapshot pinned catalog snapshot of the table being scanned
     * @param schema   table schema from the snapshot
     * @param filters  non-empty list of filter predicates on this table
     * @return filter operators wrapping a scan operator
     * @throws IllegalArgumentException if a filter column is unknown
     */
    private Operator planWithPredicates(Catalog snapshot, List<ColumnSpec> schema, List<Predicate> filters) {
        List<Integer> survivingPartitions = intersectSurvivingPartitions(snapshot, filters);
        int totalPartitions = snapshot.partitions().size();
        int readCount = survivingPartitions.size();
        lastScanStatsByTable.put(snapshot.table(),
                new ScanStats(totalPartitions, readCount, totalPartitions - readCount, snapshot.version()));

        Operator root = new ScanOperator(engine, snapshot, survivingPartitions);
        for (Predicate filter : filters) {
            BoundPredicate boundPredicate = new BoundPredicate(
                    resolveColumnIndex(schema, filter.columnName()), filter.comparison(), filter.constant());
            root = new FilterOperator(root, boundPredicate);
        }
        return root;
    }

    /**
     * Intersects the pruning results of all filters: a partition survives only
     * if no filter can rule it out.
     *
     * @param snapshot pinned catalog snapshot of the table
     * @param filters  non-empty list of filter predicates on this table
     * @return surviving partition numbers in ascending order
     * @throws IllegalArgumentException if a filter column is unknown
     */
    private List<Integer> intersectSurvivingPartitions(Catalog snapshot, List<Predicate> filters) {
        List<Integer> survivingPartitions = new ArrayList<>(partitionsThatMayMatch(snapshot, filters.getFirst()));
        for (Predicate filter : filters.subList(1, filters.size())) {
            survivingPartitions.retainAll(partitionsThatMayMatch(snapshot, filter));
        }
        return survivingPartitions;
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
