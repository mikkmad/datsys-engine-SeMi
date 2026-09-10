package datasys.semi;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates AST statements against the schema catalog of a StorageEngine.
 */
public final class Binder {

    // --- Collaborators ---
    private final StorageEngine engine;

    /**
     * Constructs a new Binder.
     *
     * @param engine the storage engine providing schema metadata
     * @throws IllegalArgumentException if engine is null
     */
    public Binder(StorageEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        this.engine = engine;
    }

    /**
     * Validates statement against the catalog; throws IllegalArgumentException on
     * the first violation.
     *
     * @param statement the AST statement to validate
     * @throws IllegalArgumentException if validation fails or statement is null
     */
    public void bind(Statement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("statement must not be null");
        }

        switch (statement) {
            case CreateTableStatement createTable -> bindCreateTable(createTable);
            case CopyStatement copy -> bindCopy(copy);
            case SelectStatement select -> bindSelect(select);
        }
    }

    /**
     * Validates a CREATE TABLE statement against schema rules.
     *
     * @param statement the create table statement
     * @throws IllegalArgumentException if column list is empty or contains
     *                                  duplicate column names
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
     * Validates that the target table of a COPY statement exists in the catalog.
     *
     * @param statement the copy statement
     * @throws IllegalArgumentException if the target table does not exist
     */
    private void bindCopy(CopyStatement statement) {
        engine.schema(statement.tableName());
    }

    /**
     * Validates that the target table and optional predicate in a SELECT statement
     * exist and match types.
     *
     * @param statement the select statement
     * @throws IllegalArgumentException if table or column is unknown, or predicate
     *                                  type mismatches
     */
    private void bindSelect(SelectStatement statement) {
        List<ColumnSpec> schema = engine.schema(statement.tableName());
        if (statement.where().isEmpty()) {
            return;
        }

        Predicate predicate = statement.where().get();
        ColumnSpec matchedColumn = schema.stream()
                .filter(column -> column.name().equals(predicate.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown column: " + predicate.columnName()));

        validatePredicateType(matchedColumn.type(), predicate.constant());
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
