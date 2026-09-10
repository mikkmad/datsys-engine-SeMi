package datasys.semi;

/**
 * AST record representing a binary predicate condition in a WHERE clause.
 *
 * @param columnName name of the column being filtered
 * @param comparison comparison operator (=, &lt;, &gt;)
 * @param constant   typed literal constant value to compare against
 */
public record Predicate(String columnName, Comparison comparison, Object constant) {

    /**
     * Constructs a Predicate with validation.
     *
     * @param columnName name of the column being filtered
     * @param comparison comparison operator (=, &lt;, &gt;)
     * @param constant   typed literal constant value to compare against
     * @throws IllegalArgumentException if any argument is null or columnName is
     *                                  blank
     */
    public Predicate {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("column name must not be null or blank");
        }
        if (comparison == null) {
            throw new IllegalArgumentException("comparison must not be null");
        }
        if (constant == null) {
            throw new IllegalArgumentException("constant must not be null");
        }
    }
}
