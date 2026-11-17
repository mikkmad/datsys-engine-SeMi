package datasys.semi.schema;

/**
 * The unique owner and position of an unqualified column name in a multi-table
 * query.
 *
 * <p>
 * The joined row is the concatenation of the {@code FROM}-list table schemas
 * in {@code FROM}-list order, so {@code joinedIndex} is the column's offset in
 * the table's own schema plus the widths of all tables listed before it.
 * Immutable and safe to share between threads.
 *
 * @param tableName          name of the table that owns the column
 * @param tablePosition      zero-based position of that table in the
 *                           {@code FROM} list
 * @param columnIndexInTable zero-based position of the column in its table's
 *                           schema
 * @param joinedIndex        zero-based position of the column in the
 *                           concatenated joined row
 * @param column             the column's name and type
 */
public record ResolvedColumn(String tableName, int tablePosition, int columnIndexInTable, int joinedIndex,
        ColumnSpec column) {

    /**
     * Constructs a ResolvedColumn with validation.
     *
     * @param tableName          owning table; must not be null
     * @param tablePosition      position in the FROM list; must not be negative
     * @param columnIndexInTable position in the table schema; must not be
     *                           negative
     * @param joinedIndex        position in the joined row; at least
     *                           {@code columnIndexInTable}
     * @param column             column specification; must not be null
     * @throws IllegalArgumentException if any argument violates its bounds
     */
    public ResolvedColumn {
        if (tableName == null || column == null) {
            throw new IllegalArgumentException("table name and column must not be null");
        }
        if (tablePosition < 0 || columnIndexInTable < 0 || joinedIndex < columnIndexInTable) {
            throw new IllegalArgumentException("invalid column position: table=" + tablePosition
                    + " column=" + columnIndexInTable + " joined=" + joinedIndex);
        }
    }

    /**
     * Returns the column's type.
     *
     * @return the column type
     */
    public ColumnType type() {
        return column.type();
    }
}
