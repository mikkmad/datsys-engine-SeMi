package datasys.semi.models;

/**
 * AST record representing a COPY statement.
 *
 * @param tableName   name of the table to copy data into
 * @param csvFilePath path to the CSV file to load
 */
public record CopyStatement(String tableName, String csvFilePath)
        implements Statement {

    /**
     * Constructs a CopyStatement with validation.
     *
     * @param tableName   name of the table to copy data into
     * @param csvFilePath path to the CSV file to load; may be empty, since the
     *                    grammar accepts {@code ''} and whether the file is
     *                    readable is checked at execution time
     * @throws IllegalArgumentException if tableName is null or blank, or
     *                                  csvFilePath is null
     */
    public CopyStatement {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table name must not be null or blank");
        }
        if (csvFilePath == null) {
            throw new IllegalArgumentException("CSV file path must not be null");
        }
    }
}
