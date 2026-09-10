package datasys.semi;

/**
 * Exception thrown when SQL parsing encounters a syntax or lexical error.
 * Captures 1-based line and 0-based character position in line per ANTLR
 * conventions.
 */
public final class SqlParseException extends RuntimeException {

    // --- Position Information ---
    private final int line;
    private final int column;

    /**
     * Constructs a SqlParseException with a message and error location coordinates.
     *
     * @param message description of the syntax error
     * @param line    1-based line number where the error occurred
     * @param column  0-based column offset where the error occurred
     */
    public SqlParseException(String message, int line, int column) {
        super(message);
        this.line = line;
        this.column = column;
    }

    /**
     * Constructs a SqlParseException with a message, error location coordinates,
     * and cause.
     *
     * @param message description of the syntax error
     * @param line    1-based line number where the error occurred
     * @param column  0-based column offset where the error occurred
     * @param cause   underlying cause of the failure
     */
    public SqlParseException(String message, int line, int column, Throwable cause) {
        super(message, cause);
        this.line = line;
        this.column = column;
    }

    /**
     * Returns the 1-based line number of the syntax error.
     *
     * @return 1-based line number
     */
    public int line() {
        return line;
    }

    /**
     * Returns the 0-based column index of the syntax error.
     *
     * @return 0-based column offset
     */
    public int column() {
        return column;
    }
}
