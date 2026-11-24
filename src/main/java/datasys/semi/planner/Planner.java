package datasys.semi.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import datasys.semi.operators.HashJoinOperator;
import datasys.semi.operators.JoinEstimate;
import datasys.semi.operators.Operator;
import datasys.semi.operators.ProjectOperator;
import datasys.semi.operators.ScanOperator;
import datasys.semi.optimizer.CardinalityEstimator;
import datasys.semi.optimizer.JoinEdge;
import datasys.semi.optimizer.JoinOrder;
import datasys.semi.optimizer.JoinOrderMode;
import datasys.semi.optimizer.JoinOrderer;
import datasys.semi.optimizer.JoinSide;
import datasys.semi.optimizer.JoinStep;
import datasys.semi.optimizer.TableEstimate;
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
 * passed into that table's scan. Multi-table queries are joined left-deep with
 * {@link HashJoinOperator}s; filters stay below the joins. The join order
 * depends on the {@link JoinOrderMode}: greedy smallest estimated intermediate
 * result first ({@code OPTIMIZED}, the default), or week 10's
 * {@code FROM}-list order ({@code SYNTAX}). Either way the output columns are
 * in {@code FROM}-list order. The chosen order is logged once per multi-table
 * statement, and every hash join receives its {@link JoinEstimate} so that it
 * can log the estimate next to its actual output size. Not thread-safe: the per-table scan statistics
 * of the last plan are kept as mutable state, so each executor owns its
 * planner.
 */
public final class Planner {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);
    private static final String DEFAULT_STATEMENT_NUMBER = "0";
    private static final ScanStats EMPTY_SCAN_STATS = new ScanStats(0, 0, 0, 0);

    // --- Collaborators ---
    private final StorageEngine engine;

    // --- Configuration ---
    private final JoinOrderMode joinOrderMode;

    // --- Observable Metrics ---
    private Map<String, ScanStats> lastScanStatsByTable = new LinkedHashMap<>();

    /**
     * Constructs a planner that orders joins greedily
     * ({@link JoinOrderMode#OPTIMIZED}, the default).
     *
     * @param engine storage engine managing catalogs and data files
     * @throws IllegalArgumentException if {@code engine} is null
     */
    public Planner(StorageEngine engine) {
        this(engine, JoinOrderMode.OPTIMIZED);
    }

    /**
     * Constructs a planner with an explicit join order mode.
     *
     * @param engine        storage engine managing catalogs and data files
     * @param joinOrderMode {@link JoinOrderMode#OPTIMIZED} for greedy ordering,
     *                      {@link JoinOrderMode#SYNTAX} for week 10's
     *                      {@code FROM}-list order
     * @throws IllegalArgumentException if an argument is null
     */
    public Planner(StorageEngine engine, JoinOrderMode joinOrderMode) {
        if (engine == null || joinOrderMode == null) {
            throw new IllegalArgumentException("engine and joinOrderMode must not be null");
        }
        if (MDC.get("statementNumber") == null) {
            MDC.put("statementNumber", DEFAULT_STATEMENT_NUMBER);
        }
        this.engine = engine;
        this.joinOrderMode = joinOrderMode;
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
     * or more tables, joined left-deep in the order chosen by the join order
     * mode.
     *
     * <p>
     * Each table gets its own pipeline: a scan of the partitions that survive
     * pruning, under one filter per WHERE filter on that table's columns, plus
     * an estimate of its size. {@link JoinOrderer} orders the joins from these
     * estimates, and the pipelines are folded into hash joins in that order.
     * Output rows are always the table schemas concatenated in {@code FROM}-list
     * order, optionally projected onto a column list, whatever the join order.
     *
     * @param statement the bound select statement to plan
     * @param snapshots pinned catalog snapshots in {@code FROM}-list order, one
     *                  per table
     * @return root operator of the planned pipeline
     * @throws IllegalArgumentException if an argument is null, the snapshots do
     *                                  not match the {@code FROM} list, a column
     *                                  is unknown, the tables cannot be joined
     *                                  without a cross product, or a table is
     *                                  connected by more than one join predicate
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
        List<TablePlan> tablePlans = new ArrayList<>(snapshots.size());
        for (int tablePosition = 0; tablePosition < snapshots.size(); tablePosition++) {
            tablePlans.add(planTable(snapshots.get(tablePosition), filtersByTable.get(tablePosition)));
        }

        JoinOrder joinOrder = chooseJoinOrder(statement, snapshots, tablePlans, resolver);
        logJoinOrder(joinOrder, snapshots);
        JoinedPipeline joined = planJoins(joinOrder, tablePlans, snapshots, resolver);
        return planOutputColumns(joined, statement, snapshots, resolver);
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
     * Turns the join predicates into join graph edges and orders them according
     * to the join order mode.
     *
     * @param statement  the select statement, for its join predicates
     * @param snapshots  pinned catalog snapshots in {@code FROM}-list order
     * @param tablePlans table pipelines with estimates, in {@code FROM}-list order
     * @param resolver   column resolver over the {@code FROM}-list schemas
     * @return the join order to build
     * @throws IllegalArgumentException if the tables cannot be joined without a
     *                                  cross product, or a table is connected by
     *                                  more than one join predicate
     */
    private JoinOrder chooseJoinOrder(SelectStatement statement, List<Catalog> snapshots, List<TablePlan> tablePlans,
            ColumnResolver resolver) {
        List<TableEstimate> tableEstimates = new ArrayList<>(snapshots.size());
        for (int tablePosition = 0; tablePosition < snapshots.size(); tablePosition++) {
            tableEstimates.add(new TableEstimate(snapshots.get(tablePosition).table(),
                    tablePlans.get(tablePosition).estimatedRows()));
        }
        List<JoinEdge> edges = statement.joins().stream()
                .map(join -> new JoinEdge(toJoinSide(join.leftColumn(), tablePlans, resolver),
                        toJoinSide(join.rightColumn(), tablePlans, resolver)))
                .toList();
        return switch (joinOrderMode) {
            case SYNTAX -> JoinOrderer.orderBySyntax(tableEstimates, edges);
            case OPTIMIZED -> JoinOrderer.orderGreedily(tableEstimates, edges);
        };
    }

    /**
     * Resolves one side of a join predicate and estimates its column's NDV over
     * the owning table's surviving partitions.
     *
     * @param columnName join column as written in the query
     * @param tablePlans table pipelines with their surviving partitions
     * @param resolver   column resolver over the {@code FROM}-list schemas
     * @return the join side
     * @throws IllegalArgumentException if the column is unknown or ambiguous
     */
    private static JoinSide toJoinSide(String columnName, List<TablePlan> tablePlans, ColumnResolver resolver) {
        ResolvedColumn column = resolver.resolve(columnName);
        List<Partition> survivingPartitions = tablePlans.get(column.tablePosition()).survivingPartitions();
        long ndv = CardinalityEstimator.columnNdv(survivingPartitions, column.column().name());
        return new JoinSide(column.tablePosition(), columnName, ndv);
    }

    /**
     * Logs the chosen join order of a multi-table statement once, with the mode
     * and the final join's estimate. A single table has no join order, so its
     * log stays unchanged.
     *
     * @param joinOrder the chosen join order
     * @param snapshots pinned catalog snapshots in {@code FROM}-list order
     */
    private void logJoinOrder(JoinOrder joinOrder, List<Catalog> snapshots) {
        if (joinOrder.steps().isEmpty()) {
            return;
        }
        String joinOrderText = LogSanitizer.joinList(tableNamesInJoinOrder(joinOrder, snapshots));
        LOGGER.debug("statement=SELECT joinOrder={} mode={} estRows={}", joinOrderText, joinOrderMode.flagValue(),
                Math.round(joinOrder.steps().getLast().estimatedRows()));
    }

    /**
     * Lists the table names in the chosen join order.
     *
     * @param joinOrder the chosen join order
     * @param snapshots pinned catalog snapshots in {@code FROM}-list order
     * @return table names, first table first
     */
    private static List<String> tableNamesInJoinOrder(JoinOrder joinOrder, List<Catalog> snapshots) {
        return joinOrder.tableOrder().stream()
                .map(tablePosition -> snapshots.get(tablePosition).table())
                .toList();
    }

    /**
     * Folds the table pipelines into hash joins in the chosen order, tracking
     * which table's columns sit where in the joined row. Join {@code n} (one-based)
     * covers the first {@code n + 1} tables of the join order and carries the
     * step's estimate.
     *
     * @param joinOrder  the chosen join order
     * @param tablePlans table pipelines in {@code FROM}-list order
     * @param snapshots  pinned catalog snapshots in {@code FROM}-list order
     * @param resolver   column resolver over the {@code FROM}-list schemas
     * @return the root join, or the only table's pipeline, with its column layout
     */
    private static JoinedPipeline planJoins(JoinOrder joinOrder, List<TablePlan> tablePlans,
            List<Catalog> snapshots, ColumnResolver resolver) {
        Operator root = tablePlans.get(joinOrder.firstTablePosition()).pipeline();
        List<Integer> tableLayout = new ArrayList<>(List.of(joinOrder.firstTablePosition()));
        List<String> tableNames = tableNamesInJoinOrder(joinOrder, snapshots);
        for (int stepIndex = 0; stepIndex < joinOrder.steps().size(); stepIndex++) {
            JoinStep step = joinOrder.steps().get(stepIndex);
            JoinEstimate estimate = new JoinEstimate(stepIndex + 1, tableNames.subList(0, stepIndex + 2),
                    step.estimatedRows());
            Operator newTablePipeline = tablePlans.get(step.newTablePosition()).pipeline();
            root = planJoin(new JoinInputs(root, tableLayout, newTablePipeline), step, estimate, snapshots,
                    resolver);
            if (step.newTableBuilds()) {
                tableLayout.addFirst(step.newTablePosition());
            } else {
                tableLayout.addLast(step.newTablePosition());
            }
        }
        return new JoinedPipeline(root, List.copyOf(tableLayout));
    }

    /**
     * Joins one new table onto the tables joined so far. The step decides which
     * side builds; the output row is always the build columns followed by the
     * probe columns.
     *
     * @param inputs    the tables joined so far, their column layout, and the
     *                  new table's pipeline
     * @param step      the join step with its predicate and build side
     * @param estimate  the step's estimate, logged by the join on close
     * @param snapshots pinned catalog snapshots, for table widths
     * @param resolver  column resolver over the {@code FROM}-list schemas
     * @return hash join of the two pipelines
     */
    private static Operator planJoin(JoinInputs inputs, JoinStep step, JoinEstimate estimate,
            List<Catalog> snapshots, ColumnResolver resolver) {
        int newTable = step.newTablePosition();
        ResolvedColumn joinedColumn = resolver.resolve(step.edge().otherSideOf(newTable).columnName());
        ResolvedColumn newTableColumn = resolver.resolve(step.edge().sideOf(newTable).columnName());
        int joinedKey = columnOffset(inputs.tableLayout(), joinedColumn.tablePosition(), snapshots)
                + joinedColumn.columnIndexInTable();
        int newTableKey = newTableColumn.columnIndexInTable();
        return step.newTableBuilds()
                ? new HashJoinOperator(inputs.newTablePipeline(), inputs.joinedSoFar(), newTableKey, joinedKey,
                        estimate)
                : new HashJoinOperator(inputs.joinedSoFar(), inputs.newTablePipeline(), joinedKey, newTableKey,
                        estimate);
    }

    /**
     * Returns where a table's first column sits in a joined row.
     *
     * @param tableLayout   {@code FROM}-list positions of the joined tables, in
     *                      column order
     * @param tablePosition {@code FROM}-list position of a table in the layout
     * @param snapshots     pinned catalog snapshots, for table widths
     * @return the sum of the widths of the tables before it in the layout
     */
    private static int columnOffset(List<Integer> tableLayout, int tablePosition, List<Catalog> snapshots) {
        int offset = 0;
        for (int layoutTable : tableLayout) {
            if (layoutTable == tablePosition) {
                return offset;
            }
            offset += snapshots.get(layoutTable).columnSpecs().size();
        }
        throw new IllegalStateException("table position " + tablePosition + " is not in the joined row");
    }

    /**
     * Restores the {@code FROM}-list column order: projects onto the column list
     * if there is one, otherwise permutes the joined row back to {@code FROM}
     * order. A plan whose layout already is in {@code FROM} order gets no extra
     * operator, so {@code -j syntax} plans match week 10 exactly.
     *
     * @param joined    the joined pipeline with its column layout
     * @param statement the select statement, for its column list
     * @param snapshots pinned catalog snapshots in {@code FROM}-list order
     * @param resolver  column resolver over the {@code FROM}-list schemas
     * @return the plan's root operator
     * @throws IllegalArgumentException if a projected column is unknown or
     *                                  ambiguous
     */
    private static Operator planOutputColumns(JoinedPipeline joined, SelectStatement statement,
            List<Catalog> snapshots, ColumnResolver resolver) {
        if (statement.columns().isPresent()) {
            List<Integer> projected = statement.columns().get().stream()
                    .map(resolver::resolve)
                    .map(column -> columnOffset(joined.tableLayout(), column.tablePosition(), snapshots)
                            + column.columnIndexInTable())
                    .toList();
            return new ProjectOperator(joined.root(), toIntArray(projected));
        }
        List<Integer> fromListOrder = fromListColumnOrder(joined.tableLayout(), snapshots);
        boolean alreadyInFromOrder = IntStream.range(0, fromListOrder.size())
                .allMatch(index -> fromListOrder.get(index) == index);
        return alreadyInFromOrder ? joined.root() : new ProjectOperator(joined.root(), toIntArray(fromListOrder));
    }

    /**
     * Lists, for every output column in {@code FROM}-list order, its index in the
     * joined row.
     *
     * @param tableLayout {@code FROM}-list positions of the joined tables, in
     *                    column order
     * @param snapshots   pinned catalog snapshots in {@code FROM}-list order
     * @return joined-row indices of all columns in {@code FROM}-list order
     */
    private static List<Integer> fromListColumnOrder(List<Integer> tableLayout, List<Catalog> snapshots) {
        List<Integer> joinedRowIndices = new ArrayList<>();
        for (int tablePosition = 0; tablePosition < snapshots.size(); tablePosition++) {
            int offset = columnOffset(tableLayout, tablePosition, snapshots);
            int width = snapshots.get(tablePosition).columnSpecs().size();
            for (int columnIndex = 0; columnIndex < width; columnIndex++) {
                joinedRowIndices.add(offset + columnIndex);
            }
        }
        return joinedRowIndices;
    }

    /**
     * Converts column indices to the array {@link ProjectOperator} expects.
     *
     * @param columnIndices column indices in output order
     * @return the same indices as an {@code int[]}
     */
    private static int[] toIntArray(List<Integer> columnIndices) {
        return columnIndices.stream().mapToInt(Integer::intValue).toArray();
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
     * Builds one table's pipeline, a scan over the partitions that survive
     * pruning with the table's filters stacked above it in WHERE order, and
     * estimates its output size from the same surviving partitions.
     *
     * @param snapshot pinned catalog snapshot of the table
     * @param filters  filters on this table's columns; may be empty
     * @return the table pipeline with its surviving partitions and estimate
     * @throws IllegalArgumentException if a filter column is unknown
     */
    private TablePlan planTable(Catalog snapshot, List<Predicate> filters) {
        List<Integer> survivingNumbers = filters.isEmpty()
                ? readAllPartitions(snapshot)
                : intersectSurvivingPartitions(snapshot, filters);
        int totalPartitions = snapshot.partitions().size();
        lastScanStatsByTable.put(snapshot.table(), new ScanStats(totalPartitions, survivingNumbers.size(),
                totalPartitions - survivingNumbers.size(), snapshot.version()));

        Operator root = new ScanOperator(engine, snapshot, survivingNumbers);
        for (Predicate filter : filters) {
            BoundPredicate boundPredicate = new BoundPredicate(resolveColumnIndex(snapshot.columnSpecs(),
                    filter.columnName()), filter.comparison(), filter.constant());
            root = new FilterOperator(root, boundPredicate);
        }
        List<Partition> survivingPartitions = survivingNumbers.stream().map(snapshot.partitions()::get).toList();
        double estimatedRows = CardinalityEstimator.filteredTableRows(survivingPartitions, snapshot.columnSpecs(),
                filters);
        return new TablePlan(root, survivingPartitions, estimatedRows);
    }

    /**
     * Selects every partition of a table without a filter, logging each read
     * decision.
     *
     * @param snapshot pinned catalog snapshot of the table being scanned
     * @return all partition numbers in ascending order
     */
    private static List<Integer> readAllPartitions(Catalog snapshot) {
        int totalPartitions = snapshot.partitions().size();
        List<Integer> allPartitions = new ArrayList<>(totalPartitions);
        for (int index = 0; index < totalPartitions; index++) {
            allPartitions.add(index);
            LOGGER.debug("table={} partition={} decision=READ reason=noPredicate", snapshot.table(), index);
        }
        return allPartitions;
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

    /**
     * One table's planned pipeline with the inputs the join orderer needs.
     *
     * @param pipeline            scan with the table's filters above it
     * @param survivingPartitions partitions left after pruning, for NDVs
     * @param estimatedRows       estimated rows after pruning and filters
     */
    private record TablePlan(Operator pipeline, List<Partition> survivingPartitions, double estimatedRows) {
    }

    /**
     * The joined pipeline and the order of the tables' columns in its rows.
     *
     * @param root        root operator of the joins
     * @param tableLayout {@code FROM}-list positions of the tables, in column
     *                    order of the joined row
     */
    private record JoinedPipeline(Operator root, List<Integer> tableLayout) {
    }

    /**
     * The two inputs of the next join while the joins are folded.
     *
     * @param joinedSoFar      pipeline producing the rows of the tables joined so
     *                         far
     * @param tableLayout      {@code FROM}-list positions of the tables in
     *                         {@code joinedSoFar}'s rows, in column order
     * @param newTablePipeline pipeline of the new table
     */
    private record JoinInputs(Operator joinedSoFar, List<Integer> tableLayout, Operator newTablePipeline) {
    }
}
