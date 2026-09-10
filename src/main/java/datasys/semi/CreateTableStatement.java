package datasys.semi;

import java.util.List;

/**
 * AST record representing a CREATE TABLE statement.
 *
 * @param tableName name of the table to create
 * @param columns   ordered list of column specifications
 */
public record CreateTableStatement(String tableName, List<ColumnSpec> columns)
        implements Statement {

    /**
     * Constructs a CreateTableStatement with an immutable copy of columns.
     *
     * @param tableName name of the table to create
     * @param columns   ordered list of column specifications
     * @throws IllegalArgumentException if tableName is null or blank, or columns is
     *                                  null
     */
    public CreateTableStatement {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table name must not be null or blank");
        }
        if (columns == null) {
            throw new IllegalArgumentException("column list must not be null");
        }
        columns = List.copyOf(columns);
    }
}
