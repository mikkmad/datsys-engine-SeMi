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
            case SelectStatement select -> bind(select, engine.snapshot(select.tableName()));
            case DeleteStatement delete -> bind(delete, engine.snapshot(delete.tableName()));
        }
    }

    /**
     * Validates a statement against a pre-pinned {@link Catalog} snapshot without
     * re-reading metadata from {@link StorageEngine}.
     *
     * @param statement the AST statement to validate
     * @param snapshot  pinned catalog snapshot for the statement's target table
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
     * Validates that the target table and optional predicate in a {@code SELECT}
     * statement exist in the pinned snapshot and match types.
     *
     * @param statement the select statement
     * @param snapshot  pinned catalog snapshot of the target table
     * @throws IllegalArgumentException if the snapshot is null, a column is
     *                                  unknown, or the predicate type mismatches
     */
    private void bindSelect(SelectStatement statement, Catalog snapshot) {
        bindTableAndWhere(statement.tableName(), statement.where(), snapshot);
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

        Predicate predicate = where.get();
        ColumnSpec matchedColumn = snapshot.columnSpecs().stream()
                .filter(column -> column.name().equals(predicate.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown column: " + predicate.columnName()));

        validatePredicateType(matchedColumn.type(), predicate.constant());
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
