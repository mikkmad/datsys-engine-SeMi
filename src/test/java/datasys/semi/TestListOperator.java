package datasys.semi;

import java.util.List;

import datasys.semi.operators.Operator;

/**
 * Test helper serving a fixed list of rows as an {@link Operator}, so a parent
 * operator can be exercised without any storage involved.
 */
final class TestListOperator implements Operator {

    // --- Rows Served ---
    private final List<Object[]> rows;

    // --- Cursor State ---
    private int nextIndex;

    /**
     * Constructs a helper serving the given rows, in list order.
     *
     * @param rows the rows this operator emits; must not be null
     */
    TestListOperator(List<Object[]> rows) {
        this.rows = List.copyOf(rows);
    }

    /**
     * Rewinds the cursor to the first row.
     */
    @Override
    public void open() {
        nextIndex = 0;
    }

    /**
     * Serves the next row from the list.
     *
     * @return the next row, or null once the list is exhausted
     */
    @Override
    public Object[] next() {
        if (nextIndex >= rows.size()) {
            return null;
        }
        return rows.get(nextIndex++);
    }

    /**
     * Releases nothing: this helper holds no resources.
     */
    @Override
    public void close() {
        // No resources to release.
    }
}
