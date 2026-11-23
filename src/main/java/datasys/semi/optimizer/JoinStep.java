package datasys.semi.optimizer;

/**
 * One join of a left-deep plan: the tables joined so far are joined with one
 * new table through one predicate.
 *
 * <p>
 * The estimate is carried to the plan so it can be logged next to the actual
 * output size. Immutable and thread-safe.
 *
 * @param newTablePosition {@code FROM}-list position of the table this step
 *                         adds, non-negative
 * @param edge             the predicate connecting it to the tables joined so
 *                         far, not null; it touches the new table
 * @param newTableBuilds   true if the new table builds the hash table and the
 *                         tables joined so far probe; false for the reverse
 * @param estimatedRows    estimated output rows of this join, non-negative and
 *                         finite
 */
public record JoinStep(int newTablePosition, JoinEdge edge, boolean newTableBuilds, double estimatedRows) {

    /**
     * Validates the step.
     *
     * @throws IllegalArgumentException if the position is negative, the edge is
     *                                  null or does not touch the new table, or
     *                                  the estimate is negative or not finite
     */
    public JoinStep {
        if (edge == null) {
            throw new IllegalArgumentException("join edge must not be null");
        }
        if (newTablePosition < 0 || !edge.touches(newTablePosition)) {
            throw new IllegalArgumentException("join predicate " + edge.describe()
                    + " does not connect new table position " + newTablePosition);
        }
        if (!Double.isFinite(estimatedRows) || estimatedRows < 0) {
            throw new IllegalArgumentException("estimated rows must be finite and non-negative: " + estimatedRows);
        }
    }
}
