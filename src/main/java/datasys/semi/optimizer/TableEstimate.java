package datasys.semi.optimizer;

/**
 * The estimated size of one {@code FROM} table after pruning and filters: the
 * input the join orderer ranks tables by.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * @param tableName     name of the table, not blank
 * @param estimatedRows estimated rows after pruning and filters, non-negative
 *                      and finite
 */
public record TableEstimate(String tableName, double estimatedRows) {

    /**
     * Validates the table name and the estimate.
     *
     * @throws IllegalArgumentException if {@code tableName} is null or blank, or
     *                                  {@code estimatedRows} is negative or not
     *                                  finite
     */
    public TableEstimate {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table name must not be null or blank");
        }
        if (!Double.isFinite(estimatedRows) || estimatedRows < 0) {
            throw new IllegalArgumentException("estimated rows of " + tableName
                    + " must be finite and non-negative: " + estimatedRows);
        }
    }
}
