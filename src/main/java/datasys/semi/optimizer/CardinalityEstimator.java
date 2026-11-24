package datasys.semi.optimizer;

import java.util.List;

import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Partition;
import datasys.semi.engine.StorageEngine.Statistics;
import datasys.semi.models.Predicate;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;
import datasys.semi.schema.Comparison;

/**
 * Estimates the cardinality of base tables, filters and equi-joins from catalog
 * statistics, for the greedy join planner.
 *
 * <p>The formulas and their sources are documented in
 * {@code docs/optimizer-design.md}:
 * <ul>
 * <li>base table: the sum of the row counts of the partitions that survive
 * pruning;</li>
 * <li>equality filter: {@code |R| / ndv(col)};</li>
 * <li>range filter: linear interpolation between each partition's min and max,
 * or Selinger's {@code 1/3} when the statistics cannot answer;</li>
 * <li>equi-join: {@code |R| * |S| / max(ndv(R.a), ndv(S.b))}.</li>
 * </ul>
 * Both {@code |R|} and {@code ndv} are taken over the same list of surviving
 * partitions. Estimates are {@code double} so that a chain of joins is not
 * distorted by rounding each step.
 *
 * <p>Stateless and thread-safe; all methods are pure functions that read no
 * data files and write no log lines.
 */
public final class CardinalityEstimator {

    // --- Constants ---
    /** Selectivity of a range predicate the statistics cannot answer (Selinger et al., 1979). */
    public static final double SELINGER_RANGE_SELECTIVITY = 1.0 / 3.0;

    /**
     * Prevents instantiation of this utility class.
     */
    private CardinalityEstimator() {
    }

    /**
     * Estimates the size of a base table scan: the only exact number the planner
     * has.
     *
     * @param survivingPartitions partitions left after min/max pruning, not null
     * @return the sum of their row counts; 0 when no partition survives
     * @throws IllegalArgumentException if {@code survivingPartitions} is null
     */
    public static long baseTableRows(List<Partition> survivingPartitions) {
        requireNonNull(survivingPartitions, "survivingPartitions");
        return survivingPartitions.stream()
                .mapToLong(Partition::rowCount)
                .sum();
    }

    /**
     * Estimates the number of distinct values of one column over the given
     * partitions, merged by {@link NdvMerger#tableNdv(List, long)} (sum, an upper
     * bound). A partition without an NDV for the column counts with its row count.
     *
     * @param partitions partitions to merge, typically those that survive pruning
     * @param columnName column whose NDV is requested, not null
     * @return merged NDV, at most {@link #baseTableRows(List)}; 0 for no partitions
     * @throws IllegalArgumentException if an argument is null
     */
    public static long columnNdv(List<Partition> partitions, String columnName) {
        requireNonNull(partitions, "partitions");
        requireNonNull(columnName, "columnName");
        List<Long> partitionNdvs = partitions.stream()
                .map(partition -> partitionNdv(partition, columnName))
                .toList();
        return NdvMerger.tableNdv(partitionNdvs, baseTableRows(partitions));
    }

    /**
     * Estimates the rows a table keeps after all its filters, assuming the
     * filters are independent: {@code |R| * product of selectivities}.
     *
     * @param survivingPartitions partitions left after pruning by all filters
     * @param schema              the table's schema, used to resolve filter column
     *                            types
     * @param filters             filters on this table; empty means no filter
     * @return estimated number of rows after filtering, in {@code [0, |R|]}
     * @throws IllegalArgumentException if an argument is null, a filter column is
     *                                  not in the schema, or a numeric filter has a
     *                                  non-numeric constant
     */
    public static double filteredTableRows(List<Partition> survivingPartitions, List<ColumnSpec> schema,
                                           List<Predicate> filters) {
        requireNonNull(schema, "schema");
        requireNonNull(filters, "filters");
        long baseRows = baseTableRows(survivingPartitions);
        double combinedSelectivity = filters.stream()
                .mapToDouble(filter -> selectivityOf(survivingPartitions, schema, filter))
                .reduce(1.0, (product, selectivity) -> product * selectivity);
        return baseRows * combinedSelectivity;
    }

    /**
     * Resolves a filter's column in the schema and estimates the fraction of rows
     * the filter keeps.
     *
     * @param survivingPartitions partitions left after pruning
     * @param schema              the table's schema
     * @param filter              one filter on this table
     * @return selectivity in {@code [0, 1]}
     * @throws IllegalArgumentException if the filter column is not in the schema,
     *                                  or a numeric range filter has a non-numeric
     *                                  constant
     */
    private static double selectivityOf(List<Partition> survivingPartitions, List<ColumnSpec> schema,
            Predicate filter) {
        ColumnSpec column = findColumn(schema, filter.columnName());
        return filterSelectivity(survivingPartitions, column, filter.comparison(), filter.constant());
    }

    /**
     * Estimates the fraction of rows one filter keeps.
     *
     * @param survivingPartitions partitions left after pruning, not null
     * @param column              filtered column with its type, not null
     * @param comparison          the predicate's comparison, not null
     * @param constant            the predicate's constant; a {@link Number} for
     *                            {@code LONG} and {@code DOUBLE} columns
     * @return selectivity in {@code [0, 1]}; 0 when no partition survives
     * @throws IllegalArgumentException if an argument is null, or a numeric range
     *                                  filter has a non-numeric constant
     */
    public static double filterSelectivity(List<Partition> survivingPartitions, ColumnSpec column,
                                           Comparison comparison, Object constant) {
        requireNonNull(survivingPartitions, "survivingPartitions");
        requireNonNull(column, "column");
        requireNonNull(comparison, "comparison");
        requireNonNull(constant, "constant");
        return switch (comparison) {
            case EQUALS -> equalitySelectivity(survivingPartitions, column.name());
            case LESS_THAN, GREATER_THAN -> rangeSelectivity(survivingPartitions, column, comparison, constant);
        };
    }

    /**
     * Estimates the output of an equi-join {@code R.a = S.b} (lecture 10; join
     * selectivity {@code 1 / max(ndv)} from Selinger et al., 1979). For an
     * intermediate result, pass the NDV of the base table that contributed the
     * join column.
     *
     * @param leftRows  estimated rows of the left input, non-negative
     * @param rightRows estimated rows of the right input, non-negative
     * @param leftNdv   NDV of the left join column, non-negative
     * @param rightNdv  NDV of the right join column, non-negative
     * @return {@code leftRows * rightRows / max(leftNdv, rightNdv)}; 0 when both
     *         NDVs are 0, which only happens for empty inputs
     * @throws IllegalArgumentException if any argument is negative or not finite
     */
    public static double equiJoinRows(double leftRows, double rightRows, long leftNdv, long rightNdv) {
        requireNonNegative(leftRows, "leftRows");
        requireNonNegative(rightRows, "rightRows");
        requireNonNegative(leftNdv, "leftNdv");
        requireNonNegative(rightNdv, "rightNdv");
        long largerNdv = Math.max(leftNdv, rightNdv);
        if (largerNdv == 0) {
            return 0.0;
        }
        return leftRows * rightRows / largerNdv;
    }

    /**
     * Computes {@code 1 / ndv(col)}, assuming every value is equally frequent.
     *
     * @param survivingPartitions partitions left after pruning
     * @param columnName          filtered column
     * @return selectivity in {@code [0, 1]}; 0 when the partitions hold no rows
     */
    private static double equalitySelectivity(List<Partition> survivingPartitions, String columnName) {
        long ndv = columnNdv(survivingPartitions, columnName);
        if (ndv == 0) {
            return 0.0;
        }
        return 1.0 / ndv;
    }

    /**
     * Computes the row-weighted average of the per-partition range fractions.
     *
     * @param survivingPartitions partitions left after pruning
     * @param column              filtered column with its type
     * @param comparison          {@code LESS_THAN} or {@code GREATER_THAN}
     * @param constant            the predicate's constant
     * @return selectivity in {@code [0, 1]}; 0 when the partitions hold no rows
     * @throws IllegalArgumentException if a numeric column has a non-numeric
     *                                  constant
     */
    private static double rangeSelectivity(List<Partition> survivingPartitions, ColumnSpec column,
                                           Comparison comparison, Object constant) {
        long totalRows = baseTableRows(survivingPartitions);
        if (totalRows == 0) {
            return 0.0;
        }
        double matchingRows = 0.0;
        for (Partition partition : survivingPartitions) {
            matchingRows += partition.rowCount() * partitionRangeFraction(partition, column, comparison, constant);
        }
        return matchingRows / totalRows;
    }

    /**
     * Estimates the fraction of one partition's rows a range filter keeps.
     * {@code STRING} columns and partitions without statistics fall back to
     * {@link #SELINGER_RANGE_SELECTIVITY}.
     *
     * @param partition  one surviving partition
     * @param column     filtered column with its type
     * @param comparison {@code LESS_THAN} or {@code GREATER_THAN}
     * @param constant   the predicate's constant
     * @return fraction in {@code [0, 1]}
     * @throws IllegalArgumentException if a numeric column has a non-numeric
     *                                  constant
     */
    private static double partitionRangeFraction(Partition partition, ColumnSpec column, Comparison comparison,
                                                 Object constant) {
        Statistics statistics = partition.statistics().get(column.name());
        if (column.type() == ColumnType.STRING || statistics == null) {
            return SELINGER_RANGE_SELECTIVITY;
        }
        double min = toDouble(StorageEngine.parseStatistic(statistics.min(), column.type()));
        double max = toDouble(StorageEngine.parseStatistic(statistics.max(), column.type()));
        double value = toDouble(constant);
        return interpolateRangeFraction(min, max, comparison, value);
    }

    /**
     * Interpolates linearly between min and max, assuming values are spread
     * uniformly. When {@code min == max}, the single value either satisfies the
     * predicate (1) or not (0).
     *
     * @param min        smallest value in the partition
     * @param max        largest value in the partition, {@code >= min}
     * @param comparison {@code LESS_THAN} or {@code GREATER_THAN}
     * @param value      the predicate's constant
     * @return fraction in {@code [0, 1]}
     */
    private static double interpolateRangeFraction(double min, double max, Comparison comparison, double value) {
        boolean isLessThan = comparison == Comparison.LESS_THAN;
        if (max == min) {
            boolean singleValueMatches = isLessThan ? min < value : min > value;
            return singleValueMatches ? 1.0 : 0.0;
        }
        double fraction = isLessThan ? (value - min) / (max - min) : (max - value) / (max - min);
        return Math.clamp(fraction, 0.0, 1.0);
    }

    /**
     * Returns one partition's NDV for a column, or its row count when the NDV or
     * the column's statistics are missing. The row count is an upper bound. The
     * engine fills a missing NDV in at startup (design document, "Roll
     * forward"), so this fallback is only reached when that roll forward failed
     * for the partition.
     *
     * @param partition  the partition
     * @param columnName the column
     * @return NDV of the column in the partition, non-negative
     */
    private static long partitionNdv(Partition partition, String columnName) {
        if (!partition.statistics().containsKey(columnName)) {
            return partition.rowCount();
        }
        return partition.columnNdv(columnName);
    }

    /**
     * Finds a column of the schema by name.
     *
     * @param schema     the table's schema
     * @param columnName the column to find
     * @return the matching column
     * @throws IllegalArgumentException if no column has that name
     */
    private static ColumnSpec findColumn(List<ColumnSpec> schema, String columnName) {
        return schema.stream()
                .filter(column -> column.name().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown filter column: " + columnName));
    }

    /**
     * Converts a numeric statistic or constant to {@code double} for
     * interpolation.
     *
     * @param value a {@code Long} or {@code Double}
     * @return the value as {@code double}
     * @throws IllegalArgumentException if {@code value} is not a {@link Number}
     */
    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("range filter on a numeric column needs a numeric constant: " + value);
    }

    /**
     * Checks that a reference argument is present.
     *
     * @param value         the argument
     * @param parameterName name used in the exception message
     * @throws IllegalArgumentException if {@code value} is null
     */
    private static void requireNonNull(Object value, String parameterName) {
        if (value == null) {
            throw new IllegalArgumentException(parameterName + " must not be null");
        }
    }

    /**
     * Checks that a numeric argument is finite and non-negative.
     *
     * @param value         the argument
     * @param parameterName name used in the exception message
     * @throws IllegalArgumentException if {@code value} is negative, NaN or
     *                                  infinite
     */
    private static void requireNonNegative(double value, String parameterName) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(parameterName + " must be finite and non-negative: " + value);
        }
    }
}
