package datasys.semi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import datasys.semi.sql.SqlBaseVisitor;
import datasys.semi.sql.SqlParser;

/**
 * Visitor implementation translating ANTLR parse tree nodes into strongly-typed
 * AST statements.
 */
public final class SqlAstBuilder extends SqlBaseVisitor<Object> {

    /**
     * Constructs a new SqlAstBuilder.
     */
    public SqlAstBuilder() {
    }

    /**
     * Visits a script node containing one or more semicolon-terminated statements.
     *
     * @param context the script context
     * @return list of parsed AST statements
     */
    @Override
    public List<Statement> visitScript(SqlParser.ScriptContext context) {
        List<Statement> statements = new ArrayList<>();
        for (SqlParser.StatementContext statementContext : context.statement()) {
            statements.add((Statement) visit(statementContext));
        }
        return statements;
    }

    /**
     * Visits a statement node delegating to its specific statement alternative.
     *
     * @param context the statement context
     * @return parsed AST statement
     */
    @Override
    public Statement visitStatement(SqlParser.StatementContext context) {
        if (context.createTable() != null) {
            return (Statement) visit(context.createTable());
        }
        if (context.copy() != null) {
            return (Statement) visit(context.copy());
        }
        return (Statement) visit(context.select());
    }

    /**
     * Visits a CREATE TABLE parse tree node.
     *
     * @param context the create table context
     * @return CreateTableStatement AST record
     */
    @Override
    public CreateTableStatement visitCreateTable(SqlParser.CreateTableContext context) {
        String tableName = context.IDENTIFIER().getText();
        List<ColumnSpec> columns = context.columnDef().stream()
                .map(colDef -> (ColumnSpec) visit(colDef))
                .toList();
        return new CreateTableStatement(tableName, columns);
    }

    /**
     * Visits a column definition parse tree node.
     *
     * @param context the column definition context
     * @return ColumnSpec record
     */
    @Override
    public ColumnSpec visitColumnDef(SqlParser.ColumnDefContext context) {
        String columnName = context.IDENTIFIER().getText();
        ColumnType columnType = (ColumnType) visit(context.columnType());
        return new ColumnSpec(columnName, columnType);
    }

    /**
     * Visits a column type parse tree node.
     *
     * @param context the column type context
     * @return ColumnType enum
     * @throws IllegalStateException if an unsupported column type token is
     *                               encountered
     */
    @Override
    public ColumnType visitColumnType(SqlParser.ColumnTypeContext context) {
        if (context.STRING() != null) {
            return ColumnType.STRING;
        }
        if (context.LONG() != null) {
            return ColumnType.LONG;
        }
        if (context.DOUBLE() != null) {
            return ColumnType.DOUBLE;
        }
        throw new IllegalStateException("unsupported column type in context: " + context.getText());
    }

    /**
     * Visits a COPY parse tree node.
     *
     * @param context the copy context
     * @return CopyStatement AST record
     */
    @Override
    public CopyStatement visitCopy(SqlParser.CopyContext context) {
        String tableName = context.IDENTIFIER().getText();
        String literal = context.STRING_LITERAL().getText();
        String csvFilePath = literal.substring(1, literal.length() - 1);
        return new CopyStatement(tableName, csvFilePath);
    }

    /**
     * Visits a SELECT parse tree node.
     *
     * @param context the select context
     * @return SelectStatement AST record
     */
    @Override
    public SelectStatement visitSelect(SqlParser.SelectContext context) {
        String tableName = context.IDENTIFIER().getText();
        Optional<Predicate> where = context.predicate() != null
                ? Optional.of((Predicate) visit(context.predicate()))
                : Optional.empty();
        return new SelectStatement(tableName, where);
    }

    /**
     * Visits a predicate parse tree node in a WHERE clause.
     *
     * @param context the predicate context
     * @return Predicate AST record
     */
    @Override
    public Predicate visitPredicate(SqlParser.PredicateContext context) {
        String columnName = context.IDENTIFIER().getText();
        Comparison comparison = switch (context.comparison.getText()) {
            case "=" -> Comparison.EQUALS;
            case "<" -> Comparison.LESS_THAN;
            case ">" -> Comparison.GREATER_THAN;
            default -> throw new IllegalStateException("unexpected comparison: " + context.comparison.getText());
        };
        Object constant = visit(context.literal());
        return new Predicate(columnName, comparison, constant);
    }

    /**
     * Visits a literal parse tree node and returns its typed Java constant value.
     *
     * @param context the literal context
     * @return typed constant: String, Long, or Double
     * @throws IllegalStateException if an unsupported literal token is encountered
     */
    @Override
    public Object visitLiteral(SqlParser.LiteralContext context) {
        if (context.STRING_LITERAL() != null) {
            String text = context.STRING_LITERAL().getText();
            return text.substring(1, text.length() - 1);
        }
        if (context.DOUBLE_LITERAL() != null) {
            return Double.parseDouble(context.DOUBLE_LITERAL().getText());
        }
        if (context.LONG_LITERAL() != null) {
            return Long.parseLong(context.LONG_LITERAL().getText());
        }
        throw new IllegalStateException("unsupported literal context: " + context.getText());
    }
}
