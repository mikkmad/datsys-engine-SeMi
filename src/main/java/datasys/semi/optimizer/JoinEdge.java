package datasys.semi.optimizer;

/**
 * An equi-join predicate {@code left = right} seen as an edge of the join
 * graph between two distinct {@code FROM} tables.
 *
 * <p>
 * The sides keep the order in which the predicate was written, so error
 * messages quote the query. Immutable and thread-safe.
 *
 * @param left  the side written left of {@code =}, not null
 * @param right the side written right of {@code =}, not null
 */
public record JoinEdge(JoinSide left, JoinSide right) {

    /**
     * Validates that both sides exist and belong to different tables.
     *
     * @throws IllegalArgumentException if a side is null or both sides belong to
     *                                  the same table
     */
    public JoinEdge {
        if (left == null || right == null) {
            throw new IllegalArgumentException("join sides must not be null");
        }
        if (left.tablePosition() == right.tablePosition()) {
            throw new IllegalArgumentException("join predicate " + left.columnName() + " = " + right.columnName()
                    + " must connect two different tables");
        }
    }

    /**
     * Tells whether one side of this edge belongs to the given table.
     *
     * @param tablePosition {@code FROM}-list position of a table
     * @return true if the left or right side belongs to the table
     */
    public boolean touches(int tablePosition) {
        return left.tablePosition() == tablePosition || right.tablePosition() == tablePosition;
    }

    /**
     * Returns the side of this edge that belongs to the given table.
     *
     * @param tablePosition {@code FROM}-list position of one of the edge's tables
     * @return that table's side
     * @throws IllegalArgumentException if the edge does not touch the table
     */
    public JoinSide sideOf(int tablePosition) {
        if (left.tablePosition() == tablePosition) {
            return left;
        }
        if (right.tablePosition() == tablePosition) {
            return right;
        }
        throw new IllegalArgumentException("join predicate " + describe() + " does not touch table position "
                + tablePosition);
    }

    /**
     * Returns the side of this edge opposite the given table.
     *
     * @param tablePosition {@code FROM}-list position of one of the edge's tables
     * @return the other table's side
     * @throws IllegalArgumentException if the edge does not touch the table
     */
    public JoinSide otherSideOf(int tablePosition) {
        return sideOf(tablePosition) == left ? right : left;
    }

    /**
     * Returns the later of the two tables in {@code FROM}-list order: the table
     * this edge connects to the tables listed before it.
     *
     * @return the larger table position
     */
    public int laterTablePosition() {
        return Math.max(left.tablePosition(), right.tablePosition());
    }

    /**
     * Formats the predicate as written, for messages.
     *
     * @return {@code leftColumn = rightColumn}
     */
    public String describe() {
        return left.columnName() + " = " + right.columnName();
    }
}
