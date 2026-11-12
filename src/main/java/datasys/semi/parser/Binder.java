package datasys.semi.parser;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.models.CopyStatement;
import datasys.semi.models.CreateTableStatement;
import datasys.semi.models.DeleteStatement;
import datasys.semi.models.JoinPredicate;
import datasys.semi.models.Predicate;
import datasys.semi.models.SelectStatement;
import datasys.semi.models.Statement;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;

/**
 * Validates AST statements against a pinned {@link Catalog} snapshot or
 * table-creation schema rules.
 */
public final class Binder {

    // --- Collaborators ---
    private final StorageEngine engine;

    /**
     * Constructs a new Binder.
     *
     * @param engine the storage engine providing catalog snapshots when none is
     *               supplied
     * @throws IllegalArgumentException if {@code engine} is null
     */
    public Binder(StorageEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        this.engine = engine;
    }

    /**
     * Validates a statement that does not yet have a pinned catalog snapshot (such
     * as {@code CREATE TABLE}), or pins one from the engine if needed.
     *
     * @param statement the AST statement to validate
     * @throws IllegalArgumentException if validation fails or {@code statement} is
     *                                  null
     */
    public void bind(Statement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("statement must not be null");
        }

        switch (statement) {
            case CreateTableStatement createTable -> bindCreateTable(createTable);
            case CopyStatement copy -> bind(copy, engine.snapshot(copy.tableName()));
            case SelectStatement select -> bind(select, engine.snapshot(select.tableNames().getFirst()));
            case DeleteStatement delete -> bind(delete, engine.snapshot(delete.tableName()));
        }
    }

    /**
     * Validates a statement against a pre-pinned {@link Catalog} snapshot without
     * re-reading metadata from {@link StorageEngine}.
     *
     * @param statement the AST statement to validate
     * @param snapshot  pinned catalog snapshot for the statement's target table
     *                  (for SELECT: the first table of the {@code FROM} list)
     * @throws IllegalArgumentException if validation fails or arguments are null
     */
    public void bind(Statement statement, Catalog snapshot) {
        if (statement == null) {
            throw new IllegalArgumentException("statement must not be null");
        }

        switch (statement) {
            case CreateTableStatement createTable -> bindCreateTable(createTable);
            case CopyStatement copy -> bindCopy(copy, snapshot);
            case SelectStatement select -> bindSelect(select, snapshot);
            case DeleteStatement delete -> bindDelete(delete, snapshot);
        }
    }

    /**
     * Validates a {@code CREATE TABLE} statement against schema rules.
     *
     * @param statement the create table statement
     * @throws IllegalArgumentException if column list is empty or contains
     *                                  invalid/duplicate column names
     */
    private void bindCreateTable(CreateTableStatement statement) {
        List<ColumnSpec> columns = statement.columns();
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("column list must not be empty");
        }

        Set<String> seenColumnNames = new HashSet<>();
        for (ColumnSpec column : columns) {
            if (column == null || column.name() == null || column.name().isBlank()) {
                throw new IllegalArgumentException("invalid column specification");
            }
            if (!seenColumnNames.add(column.name())) {
                throw new IllegalArgumentException("duplicate column name: " + column.name());
            }
        }
    }

    /**
     * Validates a {@code COPY} statement against the pinned catalog snapshot.
     *
     * @param statement the copy statement
     * @param snapshot  pinned catalog snapshot of the target table
     * @throws IllegalArgumentException if {@code snapshot} is null or does not
     *                                  match the statement's table
     */
    private void bindCopy(CopyStatement statement, Catalog snapshot) {
        requireMatchingSnapshot(statement.tableName(), snapshot);
    }

    /**
     * Validates a {@code SELECT} statement against the pinned snapshot.
     *
     * <p>
     * Interim rules until multi-table binding (Exercise 10 section 2): the
     * {@code FROM} list must not repeat a table and must name exactly one table;
     * every filter is validated like a single-table WHERE predicate; a join
     * predicate is rejected because, with one table, both sides necessarily land
     * in the same table.
     *
     * @param statement the select statement
     * @param snapshot  pinned catalog snapshot of the first {@code FROM} table
     * @throws IllegalArgumentException if the snapshot is null or mismatched, a
     *                                  table repeats, more than one table is
     *                                  listed, a column is unknown, a filter type
     *                                  mismatches, or a join predicate is present
     */
    private void bindSelect(SelectStatement statement, Catalog snapshot) {
        requireMatchingSnapshot(statement.tableNames().getFirst(), snapshot);
        requireDistinctTables(statement.tableNames());
        if (statement.tableNames().size() > 1) {
            throw new IllegalArgumentException("multi-table SELECT is not supported yet: "
                    + String.join(", ", statement.tableNames()));
        }
        for (Predicate filter : statement.filters()) {
            bindFilter(filter, snapshot);
        }
        for (JoinPredicate join : statement.joins()) {
            bindSingleTableJoin(join, snapshot);
        }
    }

    /**
     * Rejects a {@code FROM} list naming the same table twice. Without aliases a
     * repeated table makes every one of its columns ambiguous; DuckDB rejects it
     * as a duplicate alias.
     *
     * @param tableNames tables in {@code FROM}-list order
     * @throws IllegalArgumentException if a table name occurs more than once
     */
    private static void requireDistinctTables(List<String> tableNames) {
        Set<String> seenTableNames = new HashSet<>();
        for (String tableName : tableNames) {
            if (!seenTableNames.add(tableName)) {
                throw new IllegalArgumentException("duplicate table in FROM list: " + tableName);
            }
        }
    }

    /**
     * Validates a join predicate over a single-table {@code FROM} list: both
     * columns must exist, and the join is then rejected because its two sides
     * resolve to the same table.
     *
     * @param join     the join predicate
     * @param snapshot pinned catalog snapshot of the only table
     * @throws IllegalArgumentException always: for an unknown column, or because
     *                                  both sides land in the same table
     */
    private static void bindSingleTableJoin(JoinPredicate join, Catalog snapshot) {
        findColumn(join.leftColumn(), snapshot);
        findColumn(join.rightColumn(), snapshot);
        throw new IllegalArgumentException("join predicate " + join.leftColumn() + " = " + join.rightColumn()
                + " must connect two different tables but both columns belong to " + snapshot.table());
    }

    /**
     * Validates that the target table and optional predicate in a {@code DELETE}
     * statement exist in the pinned snapshot and match types.
     *
     * @param statement the delete statement
     * @param snapshot  pinned catalog snapshot of the target table
     * @throws IllegalArgumentException if the snapshot is null, a column is
     *                                  unknown, or the predicate type mismatches
     */
    private void bindDelete(DeleteStatement statement, Catalog snapshot) {
        bindTableAndWhere(statement.tableName(), statement.where(), snapshot);
    }

    /**
     * Validates that the target table exists and any optional WHERE predicate
     * references an existing column with a matching constant type.
     *
     * @param tableName statement's target table name
     * @param where     optional predicate for filtering rows
     * @param snapshot  pinned catalog snapshot
     * @throws IllegalArgumentException if snapshot is invalid, column is unknown,
     *                                  or constant type does not match column type
     */
    private void bindTableAndWhere(String tableName, Optional<Predicate> where, Catalog snapshot) {
        requireMatchingSnapshot(tableName, snapshot);
        if (where.isEmpty()) {
            return;
        }

        bindFilter(where.get(), snapshot);
    }

    /**
     * Validates that a filter references an existing column and carries a
     * constant of that column's type.
     *
     * @param filter   the column-to-constant predicate
     * @param snapshot pinned catalog snapshot of the filtered table
     * @throws IllegalArgumentException if the column is unknown or the constant
     *                                  type does not match the column type
     */
    private void bindFilter(Predicate filter, Catalog snapshot) {
        ColumnSpec matchedColumn = findColumn(filter.columnName(), snapshot);
        validatePredicateType(matchedColumn.type(), filter.constant());
    }

    /**
     * Looks up a column by name in the snapshot's schema.
     *
     * @param columnName column name to resolve
     * @param snapshot   pinned catalog snapshot
     * @return the matching column specification
     * @throws IllegalArgumentException if no column has that name
     */
    private static ColumnSpec findColumn(String columnName, Catalog snapshot) {
        return snapshot.columnSpecs().stream()
                .filter(column -> column.name().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown column: " + columnName));
    }

    /**
     * Verifies that a non-null snapshot is supplied for the statement's table.
     *
     * @param tableName statement's target table name
     * @param snapshot  pinned catalog snapshot
     * @throws IllegalArgumentException if {@code snapshot} is null or for a
     *                                  different table
     */
    private static void requireMatchingSnapshot(String tableName, Catalog snapshot) {
        if (snapshot == null || !snapshot.table().equals(tableName)) {
            throw new IllegalArgumentException("unknown table: " + tableName);
        }
    }

    /**
     * Validates that the predicate constant value matches the expected Java type
     * for the column.
     *
     * @param columnType expected column type
     * @param constant   constant value to validate
     * @throws IllegalArgumentException if constant type does not match the column
     *                                  type
     */
    private void validatePredicateType(ColumnType columnType, Object constant) {
        boolean valid = switch (columnType) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };

        if (!valid) {
            throw new IllegalArgumentException("constant type does not match column type: " + columnType);
        }
    }
}
