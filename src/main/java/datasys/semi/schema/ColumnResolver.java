package datasys.semi.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves unqualified column names against the schemas of all tables in a
 * {@code FROM} list.
 *
 * <p>
 * Answers, once for binder and planner, the question "which table owns this
 * column, and at which position does it sit in the joined row?". The joined
 * row is the concatenation of the table schemas in {@code FROM}-list order. A
 * name must occur in exactly one listed table: a name found nowhere is unknown,
 * a name found in several tables is ambiguous.
 *
 * <p>
 * All lookups are precomputed in the constructor; instances are immutable and
 * safe to share between threads.
 */
public final class ColumnResolver {

    // --- Resolution Index ---
    private final Map<String, List<ResolvedColumn>> candidatesByName;

    // --- Joined Row Shape ---
    private final int joinedWidth;

    /**
     * Builds the resolver from the {@code FROM}-list schemas.
     *
     * @param tables table schemas in {@code FROM}-list order; non-empty, without
     *               repeated table names
     * @throws IllegalArgumentException if tables is null or empty, contains a
     *                                  null element, or names a table twice
     */
    public ColumnResolver(List<TableSchema> tables) {
        if (tables == null || tables.isEmpty()) {
            throw new IllegalArgumentException("FROM list must name at least one table");
        }
        requireDistinctTables(tables);

        Map<String, List<ResolvedColumn>> candidates = new HashMap<>();
        int tableOffset = 0;
        for (int tablePosition = 0; tablePosition < tables.size(); tablePosition++) {
            TableSchema table = tables.get(tablePosition);
            indexTable(table, tablePosition, tableOffset, candidates);
            tableOffset += table.columns().size();
        }
        candidates.replaceAll((columnName, owners) -> List.copyOf(owners));
        this.candidatesByName = Map.copyOf(candidates);
        this.joinedWidth = tableOffset;
    }

    /**
     * Resolves an unqualified column name to its single owning table and its
     * position in the joined row.
     *
     * @param columnName column name as written in the query
     * @return the column's owner and positions
     * @throws IllegalArgumentException if no listed table has the column, or more
     *                                  than one does
     */
    public ResolvedColumn resolve(String columnName) {
        List<ResolvedColumn> candidates = candidatesByName.getOrDefault(columnName, List.of());
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("unknown column: " + columnName);
        }
        if (candidates.size() > 1) {
            String owners = candidates.stream().map(ResolvedColumn::tableName).collect(Collectors.joining(", "));
            throw new IllegalArgumentException("ambiguous column: " + columnName + " (in " + owners + ")");
        }
        return candidates.getFirst();
    }

    /**
     * Returns the number of columns in the joined row.
     *
     * @return sum of the widths of all {@code FROM}-list tables
     */
    public int joinedWidth() {
        return joinedWidth;
    }

    /**
     * Rejects a {@code FROM} list naming the same table twice. Without aliases a
     * repeated table makes every one of its columns ambiguous; DuckDB rejects it
     * as a duplicate alias.
     *
     * @param tables table schemas in {@code FROM}-list order
     * @throws IllegalArgumentException if an element is null or a table name
     *                                  occurs more than once
     */
    private static void requireDistinctTables(List<TableSchema> tables) {
        Set<String> seenTableNames = new HashSet<>();
        for (TableSchema table : tables) {
            if (table == null) {
                throw new IllegalArgumentException("table schema must not be null");
            }
            if (!seenTableNames.add(table.tableName())) {
                throw new IllegalArgumentException("duplicate table in FROM list: " + table.tableName());
            }
        }
    }

    /**
     * Adds every column of one table to the candidate index.
     *
     * @param table         the table schema
     * @param tablePosition the table's position in the {@code FROM} list
     * @param tableOffset   joined-row index of the table's first column
     * @param candidates    index from column name to owners; updated in place
     */
    private static void indexTable(TableSchema table, int tablePosition, int tableOffset,
            Map<String, List<ResolvedColumn>> candidates) {
        List<ColumnSpec> columns = table.columns();
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            ColumnSpec column = columns.get(columnIndex);
            ResolvedColumn resolved = new ResolvedColumn(table.tableName(), tablePosition, columnIndex,
                    tableOffset + columnIndex, column);
            candidates.computeIfAbsent(column.name(), name -> new ArrayList<>()).add(resolved);
        }
    }
}
