package datasys.semi.optimizer;

/**
 * One side of an equi-join predicate: the column, the {@code FROM} table that
 * owns it, and the column's NDV over that table's surviving partitions.
 *
 * <p>
 * The NDV of a base table column also stands in for the NDV of that column in
 * every intermediate result the table contributes to (design document,
 * "Intermediate results"). Immutable and thread-safe.
 *
 * @param tablePosition {@code FROM}-list position of the owning table,
 *                      non-negative
 * @param columnName    the join column, not blank
 * @param ndv           number of distinct values of the column, non-negative
 */
public record JoinSide(int tablePosition, String columnName, long ndv) {

    /**
     * Validates the position, column name and NDV.
     *
     * @throws IllegalArgumentException if {@code tablePosition} or {@code ndv} is
     *                                  negative, or {@code columnName} is null or
     *                                  blank
     */
    public JoinSide {
        if (tablePosition < 0) {
            throw new IllegalArgumentException("table position must not be negative: " + tablePosition);
        }
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("join column must not be null or blank");
        }
        if (ndv < 0) {
            throw new IllegalArgumentException("ndv of " + columnName + " must not be negative: " + ndv);
        }
    }
}
