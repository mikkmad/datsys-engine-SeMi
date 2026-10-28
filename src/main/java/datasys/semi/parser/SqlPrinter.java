package datasys.semi.parser;

import java.math.BigDecimal;
import java.util.stream.Collectors;

import datasys.semi.models.*;

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
     * @throws IllegalArgumentException if statement is null or contains a literal
     *                                  that cannot be represented in SQL
     */
    public String print(Statement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("statement must not be null");
        }

        return switch (statement) {
            case CreateTableStatement createTable -> printCreateTable(createTable);
            case CopyStatement copy -> printCopy(copy);
            case SelectStatement select -> printSelect(select);
            case DeleteStatement delete -> printDelete(delete);
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
     * @throws IllegalArgumentException if the CSV path contains unrepresentable
     *                                  characters
     */
    private String printCopy(CopyStatement statement) {
        return "COPY " + statement.tableName() + " FROM " + formatString(statement.csvFilePath()) + ";";
    }

    /**
     * Formats a SELECT statement as SQL text.
     *
     * @param statement the select statement
     * @return SQL text string
     * @throws IllegalArgumentException if the predicate literal cannot be
     *                                  represented in SQL
     */
    private String printSelect(SelectStatement statement) {
        String columnsPart = "*";
        if (statement.columns().isPresent() && !statement.columns().get().isEmpty()) {
            columnsPart = String.join(", ", statement.columns().get());
        }

        if (statement.where().isEmpty()) {
            return "SELECT " + columnsPart + " FROM " + statement.tableName() + ";";
        }

        Predicate predicate = statement.where().get();
        return "SELECT " + columnsPart + " FROM " + statement.tableName() + " WHERE " + printPredicate(predicate) + ";";
    }

    /**
     * Formats a DELETE statement as SQL text.
     *
     * @param statement the delete statement
     * @return SQL text string
     * @throws IllegalArgumentException if the predicate literal cannot be
     *                                  represented in SQL
     */
    private String printDelete(DeleteStatement statement) {
        if (statement.where().isEmpty()) {
            return "DELETE FROM " + statement.tableName() + ";";
        }

        Predicate predicate = statement.where().get();
        return "DELETE FROM " + statement.tableName() + " WHERE " + printPredicate(predicate) + ";";
    }

    /**
     * Formats a Predicate as SQL text.
     *
     * @param predicate the predicate condition
     * @return predicate SQL text
     * @throws IllegalArgumentException if the predicate constant cannot be
     *                                  represented in SQL
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
     * @param constant constant value: String, Long, or Double
     * @return formatted literal string accepted by the grammar
     * @throws IllegalArgumentException if the constant is of an unsupported type,
     *                                  contains unrepresentable characters,
     *                                  or is a non-finite Double
     */
    private String formatLiteral(Object constant) {
        return switch (constant) {
            case String stringValue -> formatString(stringValue);
            case Long longValue -> Long.toString(longValue);
            case Double doubleValue -> formatDouble(doubleValue);
            default -> throw new IllegalArgumentException("unsupported constant type: " + constant);
        };
    }

    /**
     * Formats a string as a single-quoted SQL literal ({@code '...'}).
     *
     * @param value raw string value
     * @return single-quoted literal string
     * @throws IllegalArgumentException if value contains single-quotes or newlines
     */
    private static String formatString(String value) {
        if (value.contains("'") || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException(
                    "string contains characters that cannot be represented in SQL literal: " + value);
        }
        return "'" + value + "'";
    }

    /**
     * Formats a Double as a plain decimal literal ({@code -?[0-9]+.[0-9]+}).
     * {@link Double#toString(double)} switches to exponent notation outside
     * [1e-3, 1e7), which the grammar cannot read; such values are expanded
     * without an exponent. The shortest round-trip digits are kept, so the
     * output reparses to the identical double, and the sign of zero survives.
     *
     * @param value finite double value
     * @return plain decimal text with at least one digit on each side of the point
     * @throws IllegalArgumentException if value is NaN or infinite
     */
    private String formatDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite double has no SQL literal: " + value);
        }

        String shortestText = Double.toString(value);
        if (!shortestText.contains("E")) {
            return shortestText;
        }

        String plainText = new BigDecimal(shortestText).toPlainString();
        return plainText.contains(".") ? plainText : plainText + ".0";
    }
}
