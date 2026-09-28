package datasys.semi.models;

import java.util.List;
import java.util.Optional;

/**
 * AST record representing a SELECT statement.
 *
 * @param tableName name of the table to select from
 * @param columns   optional list of columns to project; empty means '*'
 * @param where     optional predicate for filtering rows
 */
public record SelectStatement(String tableName, Optional<List<String>> columns, Optional<Predicate> where)
        implements Statement {

    /**
     * Constructs a SelectStatement with validation.
     *
     * @param tableName name of the table to select from
     * @param columns   optional list of columns to project; empty means '*'
     * @param where     optional predicate for filtering rows
     * @throws IllegalArgumentException if tableName is null or blank, or if
     *                                  columns or where is null
     */
    public SelectStatement {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table name must not be null or blank");
        }
        if (columns == null) {
            throw new IllegalArgumentException("columns optional must not be null");
        }
        if (where == null) {
            throw new IllegalArgumentException("where optional must not be null");
        }
    }
}
