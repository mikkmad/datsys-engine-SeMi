package datasys.semi;

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
     * @param csvFilePath path to the CSV file to load
     * @throws IllegalArgumentException if tableName or csvFilePath is null or blank
     */
    public CopyStatement {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table name must not be null or blank");
        }
        if (csvFilePath == null || csvFilePath.isBlank()) {
            throw new IllegalArgumentException("CSV file path must not be null or blank");
        }
    }
}
