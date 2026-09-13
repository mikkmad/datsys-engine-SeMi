package datasys.semi.models;

/**
 * Common sealed interface for all SQL AST statements.
 */
public sealed interface Statement
        permits CreateTableStatement, CopyStatement, SelectStatement {
}
