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
}
