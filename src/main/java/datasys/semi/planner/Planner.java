package datasys.semi.planner;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datasys.semi.engine.StorageEngine;
import datasys.semi.models.BoundPredicate;
import datasys.semi.models.Predicate;
import datasys.semi.models.ScanStats;
import datasys.semi.models.SelectStatement;
import datasys.semi.operators.FilterOperator;
import datasys.semi.operators.Operator;
import datasys.semi.operators.ScanOperator;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;

/**
 * Transforms bound SQL statements into executable Volcano operator pipelines.
 *
 * <p>
 * Partition pruning occurs here before any data file is opened. For queries
 * with
 * a WHERE predicate, min/max partition statistics from the catalog are
 * evaluated,
 * pruning decisions are logged, and surviving partition numbers are passed into
 * the operator tree.
 */
public final class Planner {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    // --- Collaborators ---
    private final StorageEngine engine;

    // --- Observable Metrics ---
    private ScanStats lastScanStats = new ScanStats(0, 0, 0);

    /**
     * Constructs a new Planner backed by the specified storage engine.
     *
     * @param engine storage engine managing catalogs and data files
     * @throws IllegalArgumentException if engine is null
     */
    public Planner(StorageEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        this.engine = engine;
    }

    /**
     * Creates an operator execution plan for a SELECT statement.
     *
     * @param statement the bound select statement to plan
     * @return root operator of the planned pipeline
     * @throws IllegalArgumentException if statement is null or targets an unknown
     *                                  table/column
     */
    public Operator plan(SelectStatement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("statement must not be null");
        }

        String tableName = statement.tableName();
        List<ColumnSpec> schema = engine.schema(tableName);
        List<StorageEngine.Partition> partitions = engine.partitions(tableName);

        if (statement.where().isEmpty()) {
            return planWithoutPredicate(tableName, partitions);
        }

        return planWithPredicate(tableName, schema, partitions, statement.where().get());
    }

    /**
     * Returns the scan statistics calculated during the most recent plan call.
     *
     * @return the last scan statistics
     */
    public ScanStats lastScanStats() {
        return lastScanStats;
    }

    /**
     * Alias for {@link #lastScanStats()} for bean compatibility.
     *
     * @return the last scan statistics
     */
    public ScanStats getLastScanStats() {
        return lastScanStats();
    }

    /**
     * Builds a bare scan plan when no predicate is present.
     *
     * @param tableName  name of the table being scanned
     * @param partitions partition metadata list from catalog
     * @return scan operator reading all partitions
     */
    private Operator planWithoutPredicate(String tableName, List<StorageEngine.Partition> partitions) {
        int totalPartitions = partitions.size();
        List<Integer> allPartitions = new ArrayList<>(totalPartitions);
        for (int index = 0; index < totalPartitions; index++) {
            allPartitions.add(index);
        }

        lastScanStats = new ScanStats(totalPartitions, totalPartitions, 0);
        return new ScanOperator(engine, tableName, allPartitions);
    }

    /**
     * Builds a filter-over-scan plan evaluating partition summaries for pruning.
     *
     * @param tableName  name of the table being scanned
     * @param schema     table schema
     * @param partitions partition metadata list from catalog
     * @param predicate  filter predicate to evaluate
     * @return filter operator wrapping scan operator
     */
    private Operator planWithPredicate(String tableName, List<ColumnSpec> schema,
            List<StorageEngine.Partition> partitions, Predicate predicate) {
        int columnIndex = resolveColumnIndex(schema, predicate.columnName());
        ColumnSpec column = schema.get(columnIndex);

        List<Integer> survivingPartitions = prunePartitions(tableName, partitions, column, predicate);
        int totalPartitions = partitions.size();
        int readCount = survivingPartitions.size();
        int prunedCount = totalPartitions - readCount;

        lastScanStats = new ScanStats(totalPartitions, readCount, prunedCount);

        BoundPredicate boundPredicate = new BoundPredicate(
                columnIndex, predicate.comparison(), predicate.constant());
        ScanOperator scanOperator = new ScanOperator(engine, tableName, survivingPartitions);
        return new FilterOperator(scanOperator, boundPredicate);
    }

    /**
     * Evaluates partition min/max summaries against the predicate to prune
     * irrelevant partitions.
     *
     * @param tableName  table name for logging
     * @param partitions partition metadata list
     * @param column     predicate column specification
     * @param predicate  the filter predicate
     * @return surviving partition numbers in ascending order
     */
    private List<Integer> prunePartitions(String tableName, List<StorageEngine.Partition> partitions,
            ColumnSpec column, Predicate predicate) {
        List<Integer> survivingPartitions = new ArrayList<>();

        for (int partitionNumber = 0; partitionNumber < partitions.size(); partitionNumber++) {
            StorageEngine.Partition partition = partitions.get(partitionNumber);
            StorageEngine.Statistics statistics = partition.statistics.get(column.name());

            if (statistics == null) {
                survivingPartitions.add(partitionNumber);
                continue;
            }

            Object min = StorageEngine.parseStatistic(statistics.min, column.type());
            Object max = StorageEngine.parseStatistic(statistics.max, column.type());
            boolean shouldPrune = engine.shouldPrune(
                    column.type(), predicate.comparison(), predicate.constant(), min, max);

            LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    tableName, predicate.columnName(), predicate.comparison(), predicate.constant(),
                    partitionNumber, statistics.min, statistics.max, shouldPrune ? "PRUNED" : "READ");

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
