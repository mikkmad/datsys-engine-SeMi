package datasys.semi.schema;

import java.util.List;

/**
 * The name and column schema of one table in a {@code FROM} list.
 *
 * <p>
 * Input to {@link ColumnResolver}. Decoupled from the storage engine's catalog
 * so the schema package stays free of engine dependencies. Immutable and safe
 * to share between threads.
 *
 * @param tableName table name as written in the {@code FROM} list
 * @param columns   the table's columns in schema order
 */
public record TableSchema(String tableName, List<ColumnSpec> columns) {

    /**
     * Constructs a TableSchema with validation and a defensive copy.
     *
     * @param tableName table name; must not be null or blank
     * @param columns   the table's columns in schema order; must not be null
     * @throws IllegalArgumentException if tableName is null or blank or columns
     *                                  is null
     */
    public TableSchema {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table name must not be null or blank");
        }
        if (columns == null) {
            throw new IllegalArgumentException("columns must not be null");
        }
        columns = List.copyOf(columns);
    }
}
