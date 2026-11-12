package datasys.semi.parser;

import java.util.Collections;
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
import datasys.semi.schema.ColumnResolver;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;
import datasys.semi.schema.ResolvedColumn;
import datasys.semi.schema.TableSchema;

/**
 * Validates AST statements against pinned {@link Catalog} snapshots or
 * table-creation schema rules.
 *
 * <p>
 * Multi-table {@code SELECT}s are bound against one snapshot per {@code FROM}
 * table; unqualified column names are resolved through a
 * {@link ColumnResolver}. Stateless apart from its engine reference; throw
 * sites do not log (failures are logged by the executor boundary).
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
            case SelectStatement select -> bind(select, select.tableNames().stream().map(engine::snapshot).toList());
            case DeleteStatement delete -> bind(delete, engine.snapshot(delete.tableName()));
        }
    }

    /**
     * Validates a statement against a pre-pinned {@link Catalog} snapshot without
     * re-reading metadata from {@link StorageEngine}.
     *
     * @param statement the AST statement to validate
     * @param snapshot  pinned catalog snapshot for the statement's target table;
     *                  a SELECT bound this way must list exactly that one table
     *                  (use {@link #bind(SelectStatement, List)} for joins)
     * @throws IllegalArgumentException if validation fails or arguments are null
     */
    public void bind(Statement statement, Catalog snapshot) {
        if (statement == null) {
            throw new IllegalArgumentException("statement must not be null");
        }

        switch (statement) {
            case CreateTableStatement createTable -> bindCreateTable(createTable);
            case CopyStatement copy -> bindCopy(copy, snapshot);
            case SelectStatement select -> bind(select, Collections.singletonList(snapshot));
            case DeleteStatement delete -> bindDelete(delete, snapshot);
        }
    }

    /**
     * Validates a {@code SELECT} statement against one pinned snapshot per
     * {@code FROM} table. Every column name is resolved against the schemas of
     * all listed tables: it must occur in exactly one of them. Filters must carry
     * a constant of their column's type; join predicates must connect two
     * different tables through columns of the same type.
     *
     * @param statement the select statement
     * @param snapshots pinned catalog snapshots in {@code FROM}-list order, one
     *                  per table
     * @throws IllegalArgumentException if an argument is null, the snapshots do
     *                                  not match the {@code FROM} list, a table
     *                                  repeats, a column is unknown or ambiguous,
     *                                  a filter constant has the wrong type, or a
     *                                  join is within one table or across types
     */
    public void bind(SelectStatement statement, List<Catalog> snapshots) {
        if (statement == null || snapshots == null) {
            throw new IllegalArgumentException("statement and snapshots must not be null");
        }
        requireMatchingSnapshots(statement.tableNames(), snapshots);
        ColumnResolver resolver = new ColumnResolver(snapshots.stream()
                .map(snapshot -> new TableSchema(snapshot.table(), snapshot.columnSpecs()))
                .toList());

        statement.columns().ifPresent(columns -> columns.forEach(resolver::resolve));
        for (Predicate filter : statement.filters()) {
            validatePredicateType(resolver.resolve(filter.columnName()).type(), filter.constant());
        }
        for (JoinPredicate join : statement.joins()) {
            bindJoin(join, resolver);
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
     * Verifies that the snapshots are exactly the {@code FROM}-list tables, in
     * order.
     *
     * @param tableNames tables in {@code FROM}-list order
     * @param snapshots  pinned catalog snapshots
     * @throws IllegalArgumentException if the counts differ or a snapshot is
     *                                  missing or for a different table
     */
    private static void requireMatchingSnapshots(List<String> tableNames, List<Catalog> snapshots) {
        if (snapshots.size() != tableNames.size()) {
            throw new IllegalArgumentException("SELECT over " + String.join(", ", tableNames)
                    + " needs one pinned snapshot per FROM table but got " + snapshots.size());
        }
        for (int position = 0; position < tableNames.size(); position++) {
            requireMatchingSnapshot(tableNames.get(position), snapshots.get(position));
        }
    }

    /**
     * Validates a join predicate: both sides resolve to existing, unambiguous
     * columns of two different tables with the same type. Keys of different
     * types would never be {@code equals} in the hash join, so such a join would
     * silently return no rows; DuckDB would coerce, this engine rejects.
     *
     * @param join     the join predicate
     * @param resolver column resolver over the {@code FROM}-list schemas
     * @throws IllegalArgumentException if a column is unknown or ambiguous, both
     *                                  sides belong to the same table, or their
     *                                  types differ
     */
    private static void bindJoin(JoinPredicate join, ColumnResolver resolver) {
        ResolvedColumn left = resolver.resolve(join.leftColumn());
        ResolvedColumn right = resolver.resolve(join.rightColumn());
        String joinText = join.leftColumn() + " = " + join.rightColumn();
        if (left.tablePosition() == right.tablePosition()) {
            throw new IllegalArgumentException("join predicate " + joinText
                    + " must connect two different tables but both columns belong to " + left.tableName());
        }
        if (left.type() != right.type()) {
            throw new IllegalArgumentException("join predicate " + joinText + " compares "
                    + left.type() + " with " + right.type() + "; join columns must have the same type");
        }
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
