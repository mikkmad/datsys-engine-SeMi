package datasys.semi.models;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * AST record representing a SELECT statement over one or more tables.
 *
 * <p>
 * The WHERE clause is a conjunction of predicates, split by shape: filters
 * compare a column with a constant, joins compare a column with a column. Both
 * lists keep their source order. All lists are defensively copied, so the
 * record is immutable and safe to share between threads.
 *
 * @param tableNames tables in {@code FROM}-list order; never empty
 * @param columns    optional list of columns to project; empty means '*'
 * @param filters    column-to-constant predicates of the WHERE clause
 * @param joins      column-to-column equi-join predicates of the WHERE clause
 */
public record SelectStatement(List<String> tableNames, Optional<List<String>> columns,
        List<Predicate> filters, List<JoinPredicate> joins) implements Statement {

    /**
     * Constructs a SelectStatement with validation and defensive copies.
     *
     * @param tableNames tables in {@code FROM}-list order; non-empty, no blank
     *                   names
     * @param columns    optional list of columns to project; empty means '*'
     * @param filters    column-to-constant predicates; may be empty
     * @param joins      column-to-column predicates; may be empty
     * @throws IllegalArgumentException if any argument is null, tableNames is
     *                                  empty or contains a blank name, or a list
     *                                  contains null elements
     */
    public SelectStatement {
        tableNames = requireTableNames(tableNames);
        if (columns == null) {
            throw new IllegalArgumentException("columns optional must not be null");
        }
        if (filters == null || joins == null) {
            throw new IllegalArgumentException("filters and joins must not be null");
        }
        columns = columns.map(List::copyOf);
        filters = copyWithoutNulls(filters, "filters");
        joins = copyWithoutNulls(joins, "joins");
    }

    /**
     * Validates and copies the {@code FROM} list.
     *
     * @param tableNames candidate table names
     * @return immutable copy of the table names
     * @throws IllegalArgumentException if the list is null or empty or holds a
     *                                  null or blank name
     */
    private static List<String> requireTableNames(List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            throw new IllegalArgumentException("table names must not be null or empty");
        }
        for (String tableName : tableNames) {
            if (tableName == null || tableName.isBlank()) {
                throw new IllegalArgumentException("table name must not be null or blank");
            }
        }
        return List.copyOf(tableNames);
    }

    /**
     * Copies a list into an immutable list, rejecting null elements with a
     * descriptive message.
     *
     * @param <T>      element type
     * @param elements list to copy; must not be null
     * @param listName name used in the error message
     * @return immutable copy of the list
     * @throws IllegalArgumentException if the list contains a null element
     */
    private static <T> List<T> copyWithoutNulls(List<T> elements, String listName) {
        // List.contains(null) throws on List.of(...) lists, so scan explicitly
        if (elements.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(listName + " must not contain null elements");
        }
        return List.copyOf(elements);
    }
}
