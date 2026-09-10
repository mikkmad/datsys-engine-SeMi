package datasys.semi;

import java.util.stream.Collectors;

/**
 * Pretty-printer rendering AST Statement records back to canonical SQL text.
 */
public final class SqlPrinter {

    /**
     * Constructs a new SqlPrinter.
     */
    public SqlPrinter() {
    }

    /**
     * Renders an AST statement back to SQL text that parses to an equal statement.
     *
     * @param statement the AST statement to print
     * @return semicolon-terminated SQL text representation
     * @throws IllegalArgumentException if statement is null
     */
    public String print(Statement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("statement must not be null");
        }

        return switch (statement) {
            case CreateTableStatement createTable -> printCreateTable(createTable);
            case CopyStatement copy -> printCopy(copy);
            case SelectStatement select -> printSelect(select);
        };
    }

    /**
     * Formats a CREATE TABLE statement as SQL text.
     *
     * @param statement the create table statement
     * @return SQL text string
     */
    private String printCreateTable(CreateTableStatement statement) {
        String columnDefinitions = statement.columns().stream()
                .map(column -> column.name() + " " + column.type().name())
                .collect(Collectors.joining(", "));
        return "CREATE TABLE " + statement.tableName() + " (" + columnDefinitions + ");";
    }

    /**
     * Formats a COPY statement as SQL text.
     *
     * @param statement the copy statement
     * @return SQL text string
     */
    private String printCopy(CopyStatement statement) {
        return "COPY " + statement.tableName() + " FROM '" + statement.csvFilePath() + "';";
    }

    /**
     * Formats a SELECT statement as SQL text.
     *
     * @param statement the select statement
     * @return SQL text string
     */
    private String printSelect(SelectStatement statement) {
        if (statement.where().isEmpty()) {
            return "SELECT * FROM " + statement.tableName() + ";";
        }

        Predicate predicate = statement.where().get();
        return "SELECT * FROM " + statement.tableName() + " WHERE " + printPredicate(predicate) + ";";
    }

    /**
     * Formats a Predicate as SQL text.
     *
     * @param predicate the predicate condition
     * @return predicate SQL text
     */
    private String printPredicate(Predicate predicate) {
        String operator = switch (predicate.comparison()) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };

        String literal = formatLiteral(predicate.constant());
        return predicate.columnName() + " " + operator + " " + literal;
    }

    /**
     * Formats a constant literal value for SQL output.
     *
     * @param constant constant value
     * @return formatted literal string
     */
    private String formatLiteral(Object constant) {
        if (constant instanceof String stringValue) {
            return "'" + stringValue + "'";
        }
        return String.valueOf(constant);
    }
}
