package datasys.semi.models;

import datasys.semi.schema.Comparison;

/**
 * A {@link Predicate} whose column name has already been resolved to a position
 * in the table's schema.
 *
 * <p>
 * Binding the column once, before execution starts, keeps the filter operator
 * free of schema lookups: at runtime it indexes straight into the row array.
 *
 * @param columnIndex zero-based position of the predicate column in schema
 *                    column order
 * @param comparison  comparison operator to apply
 * @param constant    typed literal the column value is compared against
 */
public record BoundPredicate(int columnIndex, Comparison comparison, Object constant) {

    /**
     * Constructs a BoundPredicate with validation.
     *
     * @param columnIndex zero-based position of the predicate column; must not be
     *                    negative
     * @param comparison  comparison operator to apply
     * @param constant    typed literal the column value is compared against
     * @throws IllegalArgumentException if columnIndex is negative, or comparison or
     *                                  constant is null
     */
    public BoundPredicate {
        if (columnIndex < 0) {
            throw new IllegalArgumentException("column index must not be negative");
        }
        if (comparison == null) {
            throw new IllegalArgumentException("comparison must not be null");
        }
        if (constant == null) {
            throw new IllegalArgumentException("constant must not be null");
        }
    }

    /**
     * Tests the predicate column in a row against the bound constant.
     *
     * @param row candidate row in schema column order
     * @return true if the row satisfies this predicate
     * @throws IllegalArgumentException if the row has no predicate column or the
     *                                  values have an unsupported or mismatched
     *                                  type
     */
    public boolean matches(Object[] row) {
        if (row == null || row.length <= columnIndex) {
            throw new IllegalArgumentException("row has no column at index " + columnIndex);
        }

        int result = compare(row[columnIndex], constant);
        return switch (comparison) {
            case EQUALS -> result == 0;
            case LESS_THAN -> result < 0;
            case GREATER_THAN -> result > 0;
        };
    }

    /**
     * Compares two values of one of the engine's supported column types.
     *
     * @param value row value to compare
     * @param other bound predicate constant
     * @return negative, zero or positive according to the values' natural order
     * @throws IllegalArgumentException if the values do not share a supported type
     */
    private static int compare(Object value, Object other) {
        if (value instanceof String left && other instanceof String right) {
            return left.compareTo(right);
        }
        if (value instanceof Long left && other instanceof Long right) {
            return Long.compare(left, right);
        }
        if (value instanceof Double left && other instanceof Double right) {
            return Double.compare(left, right);
        }
        throw new IllegalArgumentException("predicate values must have the same supported type");
    }
}
