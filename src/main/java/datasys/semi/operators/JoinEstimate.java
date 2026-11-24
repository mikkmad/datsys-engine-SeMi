package datasys.semi.operators;

import java.util.List;

/**
 * The plan-time description of one hash join: its position in the plan, the
 * tables its output covers, and the estimated number of output rows.
 *
 * <p>
 * The planner creates one per {@link HashJoinOperator} so that
 * {@code close()} can log the estimate next to the actual row counts. The
 * estimate is kept as a {@code double} and rounded only when logged, so
 * rounding does not add up along a chain of joins. Immutable and thread-safe.
 *
 * @param joinNumber    one-based position of the join in the plan; join 1 is
 *                      the first (deepest) join
 * @param tableNames    tables of the join's output in join order, at least two
 * @param estimatedRows estimated output rows, finite and non-negative
 */
public record JoinEstimate(int joinNumber, List<String> tableNames, double estimatedRows) {

    // --- Constants ---
    private static final String LOG_TABLE_SEPARATOR = "+";

    /**
     * Validates the estimate and copies the table names.
     *
     * @throws IllegalArgumentException if the join number is not positive, fewer
     *                                  than two table names are given, a name is
     *                                  null or blank, or the estimate is negative
     *                                  or not finite
     */
    public JoinEstimate {
        if (joinNumber < 1) {
            throw new IllegalArgumentException("join number must be positive: " + joinNumber);
        }
        if (tableNames == null || tableNames.size() < 2
                || tableNames.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("a join covers at least two named tables: " + tableNames);
        }
        if (!Double.isFinite(estimatedRows) || estimatedRows < 0) {
            throw new IllegalArgumentException("estimated rows must be finite and non-negative: " + estimatedRows);
        }
        tableNames = List.copyOf(tableNames);
    }

    /**
     * Formats the table names for the CSV log, which forbids commas.
     *
     * @return table names in join order separated by {@code +}
     */
    public String describeTables() {
        return String.join(LOG_TABLE_SEPARATOR, tableNames);
    }

    /**
     * Rounds the estimate for the log.
     *
     * @return the estimated rows rounded to the nearest whole row
     */
    public long roundedRows() {
        return Math.round(estimatedRows);
    }
}
