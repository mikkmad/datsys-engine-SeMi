package datasys.semi.operators;

/**
 * Transforms rows from a child operator by extracting a specific subset of
 * columns.
 */
public final class ProjectOperator implements Operator {

    // --- Collaborators & State ---
    private final Operator child;
    private final int[] columnIndices;

    /**
     * Constructs a new ProjectOperator.
     *
     * @param child         the source operator to pull rows from
     * @param columnIndices array mapping output column positions to child column
     *                      indices
     * @throws IllegalArgumentException if child or columnIndices is null
     */
    public ProjectOperator(Operator child, int[] columnIndices) {
        if (child == null || columnIndices == null) {
            throw new IllegalArgumentException("child and columnIndices must not be null");
        }
        this.child = child;
        this.columnIndices = columnIndices.clone();
    }

    /**
     * Opens the operator, delegating to the child.
     */
    @Override
    public void open() {
        child.open();
    }

    /**
     * Returns the next row with only the projected columns, or null if the child is
     * exhausted.
     *
     * @return an array containing only the projected columns, or null
     */
    @Override
    public Object[] next() {
        Object[] row = child.next();
        if (row == null) {
            return null;
        }

        Object[] projected = new Object[columnIndices.length];
        for (int index = 0; index < columnIndices.length; index++) {
            projected[index] = row[columnIndices[index]];
        }
        return projected;
    }

    /**
     * Closes the operator, delegating to the child.
     */
    @Override
    public void close() {
        child.close();
    }
}
