package datasys.semi.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.antlr.v4.runtime.Token;

import datasys.semi.models.Statement;
import datasys.semi.sql.SqlBaseVisitor;
import datasys.semi.sql.SqlParser;
import datasys.semi.schema.*;
import datasys.semi.models.*;

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
     * @throws IllegalStateException if an unsupported statement context is encountered
     */
    @Override
    public Statement visitStatement(SqlParser.StatementContext context) {
        if (context.createTable() != null) {
            return (Statement) visit(context.createTable());
        }
        if (context.copy() != null) {
            return (Statement) visit(context.copy());
        }
        if (context.select() != null) {
            return (Statement) visit(context.select());
        }
        if (context.delete() != null) {
            return (Statement) visit(context.delete());
        }
        throw new IllegalStateException("unsupported statement context: " + context.getText());
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
     * Visits a SELECT parse tree node. Each WHERE predicate is sorted by shape:
     * column-to-constant predicates land in {@code filters}, column-to-column
     * predicates in {@code joins}; source order is kept within each list.
     *
     * @param context the select context
     * @return SelectStatement AST record
     */
    @Override
    public SelectStatement visitSelect(SqlParser.SelectContext context) {
        List<String> tableNames = context.IDENTIFIER().stream()
                .map(node -> node.getText())
                .toList();

        Optional<List<String>> columns = Optional.empty();
        if (context.columnList() != null) {
            columns = Optional.of(context.columnList().IDENTIFIER().stream()
                    .map(node -> node.getText())
                    .toList());
        }

        List<Predicate> filters = new ArrayList<>();
        List<JoinPredicate> joins = new ArrayList<>();
        if (context.condition() != null) {
            sortPredicates(context.condition(), filters, joins);
        }
        return new SelectStatement(tableNames, columns, filters, joins);
    }

    /**
     * Sorts the predicates of an {@code AND} chain into filters and joins.
     *
     * @param context the condition context
     * @param filters receives column-to-constant predicates in source order
     * @param joins   receives column-to-column predicates in source order
     * @throws IllegalStateException if a predicate has neither shape
     */
    private void sortPredicates(SqlParser.ConditionContext context, List<Predicate> filters,
            List<JoinPredicate> joins) {
        for (SqlParser.PredicateContext predicateContext : context.predicate()) {
            if (predicateContext.filter() != null) {
                filters.add(visitFilter(predicateContext.filter()));
            } else if (predicateContext.join() != null) {
                joins.add(visitJoin(predicateContext.join()));
            } else {
                throw new IllegalStateException("unsupported predicate context: " + predicateContext.getText());
            }
        }
    }

    /**
     * Visits a DELETE parse tree node. DELETE keeps the single-filter form.
     *
     * @param context the delete context
     * @return DeleteStatement AST record
     */
    @Override
    public DeleteStatement visitDelete(SqlParser.DeleteContext context) {
        String tableName = context.IDENTIFIER().getText();
        Optional<Predicate> where = context.filter() != null
                ? Optional.of(visitFilter(context.filter()))
                : Optional.empty();
        return new DeleteStatement(tableName, where);
    }

    /**
     * Visits a filter parse tree node: a column compared with a constant.
     *
     * @param context the filter context
     * @return Predicate AST record
     * @throws IllegalStateException if the comparison token is unexpected
     */
    @Override
    public Predicate visitFilter(SqlParser.FilterContext context) {
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
     * Visits a join parse tree node: a column compared for equality with another
     * column.
     *
     * @param context the join context
     * @return JoinPredicate AST record
     */
    @Override
    public JoinPredicate visitJoin(SqlParser.JoinContext context) {
        return new JoinPredicate(context.left.getText(), context.right.getText());
    }

    /**
     * Visits a literal parse tree node and returns its typed Java constant value.
     *
     * @param context the literal context
     * @return typed constant: String, Long, or Double
     * @throws SqlParseException     if a numeric literal is outside the range of
     *                               its Java type
     * @throws IllegalStateException if an unsupported literal token is encountered
     */
    @Override
    public Object visitLiteral(SqlParser.LiteralContext context) {
        if (context.STRING_LITERAL() != null) {
            String text = context.STRING_LITERAL().getText();
            return text.substring(1, text.length() - 1);
        }
        if (context.DOUBLE_LITERAL() != null) {
            return parseDoubleLiteral(context.DOUBLE_LITERAL().getSymbol());
        }
        if (context.LONG_LITERAL() != null) {
            return parseLongLiteral(context.LONG_LITERAL().getSymbol());
        }
        throw new IllegalStateException("unsupported literal context: " + context.getText());
    }

    /**
     * Converts a LONG_LITERAL token to a Long.
     *
     * @param token the LONG_LITERAL token
     * @return the parsed Long value
     * @throws SqlParseException at the token's position if the value lies outside
     *                           [Long.MIN_VALUE, Long.MAX_VALUE]
     */
    private static Long parseLongLiteral(Token token) {
        try {
            return Long.parseLong(token.getText());
        } catch (NumberFormatException exception) {
            throw new SqlParseException("LONG literal out of range: " + token.getText(),
                    token.getLine(), token.getCharPositionInLine(), exception);
        }
    }

    /**
     * Converts a DOUBLE_LITERAL token to a Double, rounding to the nearest
     * representable value.
     *
     * @param token the DOUBLE_LITERAL token
     * @return the parsed, finite Double value
     * @throws SqlParseException at the token's position if the magnitude exceeds
     *                           Double.MAX_VALUE and would round to infinity
     */
    private static Double parseDoubleLiteral(Token token) {
        double value = Double.parseDouble(token.getText());
        if (Double.isInfinite(value)) {
            throw new SqlParseException("DOUBLE literal out of range: " + token.getText(),
                    token.getLine(), token.getCharPositionInLine());
        }
        return value;
    }
}
