package datasys.semi.models;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AST record representing a DELETE statement.
 *
 * @param tableName name of the table to delete from
 * @param where     optional predicate for filtering rows to delete
 */
public record DeleteStatement(String tableName, Optional<Predicate> where)
        implements Statement {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(DeleteStatement.class);

    /**
     * Constructs a DeleteStatement with validation.
     *
     * @param tableName name of the table to delete from
     * @param where     optional predicate for filtering rows to delete
     * @throws IllegalArgumentException if tableName is null or blank, or if
     *                                  where is null
     */
    public DeleteStatement {
        if (tableName == null || tableName.isBlank()) {
            LOGGER.error("table name must not be null or blank");
            throw new IllegalArgumentException("table name must not be null or blank");
        }
        if (where == null) {
            LOGGER.error("where optional must not be null");
            throw new IllegalArgumentException("where optional must not be null");
        }
    }
}
