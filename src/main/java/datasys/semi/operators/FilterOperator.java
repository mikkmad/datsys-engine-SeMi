package datasys.semi.operators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datasys.semi.models.BoundPredicate;

/**
 * Operator that pulls rows from a child and emits the ones passing a predicate.
 *
 * The filter is a plain row test and nothing more: it sees one row at a time,
 * compares a single column against a constant with the week 2 comparison
 * semantics, and forwards or drops the row. It knows nothing about partitions,
 * files or pruning. The row counts it observes are the honest measure of how
 * much work the scan beneath it did, so {@code close()} reports both.
 */
public final class FilterOperator implements Operator {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);

    // --- Pipeline Wiring ---
    private final Operator child;
    private final BoundPredicate predicate;

    // --- Cursor State ---
    private boolean opened;

    // --- Observable Metrics ---
    private long rowsIn;
    private long rowsOut;

    /**
     * Constructs a filter over a child operator.
     *
     * @param child     operator supplying the rows to test
     * @param predicate column, comparison and constant to test each row against
     * @throws IllegalArgumentException if child or predicate is null
     */
    public FilterOperator(Operator child, BoundPredicate predicate) {
        if (child == null) {
            throw new IllegalArgumentException("child operator must not be null");
        }
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must not be null");
        }

        this.child = child;
        this.predicate = predicate;
    }

    /**
     * Opens the child operator and resets the row counters.
     *
     * @throws IllegalStateException if this filter has already been opened
     */
    @Override
    public void open() {
        if (opened) {
            throw new IllegalStateException("filter operator is already open");
        }

        opened = true;
        rowsIn = 0;
        rowsOut = 0;
        child.open();
    }

    /**
     * Pulls rows from the child until one passes the predicate.
     *
     * @return the next passing row in schema column order, or null once the child
     *         is exhausted
     * @throws IllegalStateException if this filter has not been opened
     */
    @Override
    public Object[] next() {
        requireOpen();

        Object[] row;
        while ((row = child.next()) != null) {
            rowsIn++;
            if (matches(row)) {
                rowsOut++;
                return row;
            }
        }

        return null;
    }

    /**
     * Records the rows seen and emitted, then closes the child operator.
     *
     * @throws IllegalStateException if this filter has not been opened
     */
    @Override
    public void close() {
        requireOpen();

        LOGGER.debug("operator=Filter column={} comparison={} const={} rowsIn={} rowsOut={}",
                predicate.columnIndex(), predicate.comparison(), predicate.constant(), rowsIn, rowsOut);

        child.close();
    }

    /**
     * Tests one row's predicate column against the constant.
     *
     * @param row the candidate row in schema column order
     * @return true if the row satisfies the predicate
     * @throws IllegalArgumentException if the row is too short to hold the
     *                                  predicate column
     */
    private boolean matches(Object[] row) {
        if (row.length <= predicate.columnIndex()) {
            throw new IllegalArgumentException("row has no column at index " + predicate.columnIndex());
        }

        int result = compare(row[predicate.columnIndex()], predicate.constant());
        return switch (predicate.comparison()) {
            case EQUALS -> result == 0;
            case LESS_THAN -> result < 0;
            case GREATER_THAN -> result > 0;
        };
    }

    /**
     * Compares a column value against a constant of the same column type, using the
     * natural ordering the storage engine writes its min/max summaries with.
     *
     * @param value    the column value taken from the row
     * @param constant the predicate constant
     * @return negative, zero or positive as value is less than, equal to or greater
     *         than constant
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int compare(Object value, Object constant) {
        return ((Comparable) value).compareTo(constant);
    }

    /**
     * Guards against use of this operator outside its open/close lifecycle.
     *
     * @throws IllegalStateException if this filter has not been opened
     */
    private void requireOpen() {
        if (!opened) {
            throw new IllegalStateException("filter operator has not been opened");
        }
    }
}
