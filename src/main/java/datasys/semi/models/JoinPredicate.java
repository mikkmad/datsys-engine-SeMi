package datasys.semi.models;

/**
 * AST record representing an equi-join predicate {@code left = right} in a
 * WHERE clause, where both sides are unqualified column names.
 *
 * <p>
 * The record only carries names; which table owns each column is decided by
 * the binder against the {@code FROM}-list schemas. Instances are immutable and
 * safe to share between threads.
 *
 * @param leftColumn  column name on the left of {@code =}
 * @param rightColumn column name on the right of {@code =}
 */
public record JoinPredicate(String leftColumn, String rightColumn) {

    /**
     * Constructs a JoinPredicate with validation.
     *
     * @param leftColumn  column name on the left of {@code =}; must not be blank
     * @param rightColumn column name on the right of {@code =}; must not be blank
     * @throws IllegalArgumentException if either column name is null or blank
     */
    public JoinPredicate {
        if (leftColumn == null || leftColumn.isBlank()) {
            throw new IllegalArgumentException("left join column must not be null or blank");
        }
        if (rightColumn == null || rightColumn.isBlank()) {
            throw new IllegalArgumentException("right join column must not be null or blank");
        }
    }
}
