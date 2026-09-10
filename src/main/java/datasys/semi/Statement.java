package datasys.semi;

/**
 * Common sealed interface for all SQL AST statements.
 */
public sealed interface Statement
                permits CreateTableStatement, CopyStatement, SelectStatement {
}
