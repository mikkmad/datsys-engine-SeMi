package datasys.semi;

import java.util.Optional;

/**
 * AST record representing a SELECT statement.
 *
 * @param tableName name of the table to select from
 * @param where     optional predicate for filtering rows
 */
public record SelectStatement(String tableName, Optional<Predicate> where)
        implements Statement {

    /**
     * Constructs a SelectStatement with validation.
     *
     * @param tableName name of the table to select from
     * @param where     optional predicate for filtering rows
     * @throws IllegalArgumentException if tableName is null or blank, or where is
     *                                  null
     */
    public SelectStatement {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table name must not be null or blank");
        }
        if (where == null) {
            throw new IllegalArgumentException("where optional must not be null");
        }
    }
}
