package datasys.semi.executor;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import datasys.semi.engine.StorageEngine;
import datasys.semi.models.CopyStatement;
import datasys.semi.models.CreateTableStatement;
import datasys.semi.models.SelectStatement;
import datasys.semi.models.Statement;
import datasys.semi.operators.Operator;
import datasys.semi.parser.Binder;
import datasys.semi.parser.SqlParser;
import datasys.semi.planner.Planner;

/**
 * Orchestrates statement-by-statement execution through parse, bind, plan, and
 * execute.
 *
 * <p>
 * Maintains statement sequencing in the SLF4J MDC ({@code statementNumber}),
 * dispatching DDL and COPY statements directly to the storage engine while
 * running
 * SELECT statements as Volcano operator pipelines. Output from SELECT is
 * emitted
 * as headerless CSV to the configured output stream. Execution halts
 * immediately
 * upon encountering any error.
 */
public final class Executor {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(Executor.class);
    private static final String DEFAULT_STATEMENT_NUMBER = "0";

    // --- Collaborators ---
    private final StorageEngine engine;
    private final SqlParser parser;
    private final Binder binder;
    private final Planner planner;

    // --- Output Configuration ---
    private final PrintStream output;

    /**
     * Constructs an Executor writing SELECT CSV output to standard output.
     *
     * @param engine storage engine backing tables and catalogs
     * @throws IllegalArgumentException if engine is null
     */
    public Executor(StorageEngine engine) {
        this(engine, System.out);
    }

    /**
     * Constructs an Executor writing SELECT CSV output to a designated stream.
     *
     * @param engine storage engine backing tables and catalogs
     * @param output target print stream for query results
     * @throws IllegalArgumentException if engine or output is null
     */
    public Executor(StorageEngine engine, PrintStream output) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }

        this.engine = engine;
        this.output = output;
        this.parser = new SqlParser();
        this.binder = new Binder(engine);
        this.planner = new Planner(engine);
    }

    /**
     * Parses and executes a multi-statement SQL script.
     *
     * @param sql script containing one or more semicolon-separated statements
     * @throws IllegalArgumentException if sql is null or execution fails
     */
    public void execute(String sql) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null");
        }

        List<Statement> statements = parser.parse(sql);
        execute(statements);
    }

    /**
     * Executes a list of parsed statements, tracking statement numbers in the MDC.
     *
     * @param statements statements to bind and execute
     * @throws IllegalArgumentException if statements is null or execution fails
     */
    public void execute(List<Statement> statements) {
        if (statements == null) {
            throw new IllegalArgumentException("statements must not be null");
        }

        int statementNumber = 0;
        try {
            for (Statement statement : statements) {
                statementNumber++;
                MDC.put("statementNumber", String.valueOf(statementNumber));
                executeStatement(statement);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Execution failed at statementNumber={}", statementNumber, exception);
            throw exception;
        } finally {
            MDC.put("statementNumber", DEFAULT_STATEMENT_NUMBER);
        }
    }

    /**
     * Executes a single query and returns the matching rows in memory.
     *
     * @param sql SQL query string containing a single SELECT statement
     * @return list of rows produced by the query
     * @throws IllegalArgumentException if SQL does not yield exactly one SELECT
     *                                  statement
     */
    public List<Object[]> executeQuery(String sql) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null");
        }

        List<Statement> statements = parser.parse(sql);
        if (statements.size() != 1 || !(statements.getFirst() instanceof SelectStatement select)) {
            throw new IllegalArgumentException("expected exactly one SELECT statement");
        }

        MDC.put("statementNumber", "1");
        try {
            binder.bind(select);
            Operator plan = planner.plan(select);
            return drainToList(plan);
        } finally {
            MDC.put("statementNumber", DEFAULT_STATEMENT_NUMBER);
        }
    }

    /**
     * Binds, plans, and executes a single statement according to its type.
     *
     * @param statement the AST statement to execute
     */
    private void executeStatement(Statement statement) {
        long started = System.nanoTime();
        binder.bind(statement);

        switch (statement) {
            case CreateTableStatement createTable -> executeCreateTable(createTable, started);
            case CopyStatement copy -> executeCopy(copy, started);
            case SelectStatement select -> executeSelect(select, started);
        }
    }

    /**
     * Executes a CREATE TABLE statement.
     *
     * @param statement CREATE TABLE statement
     * @param started   nano timestamp when execution started
     */
    private void executeCreateTable(CreateTableStatement statement, long started) {
        engine.createTable(statement.tableName(), statement.columns());
        LOGGER.debug("statement=CREATE_TABLE table={} durationMs={}",
                statement.tableName(), elapsedMillis(started));
    }

    /**
     * Executes a COPY statement.
     *
     * @param statement COPY statement
     * @param started   nano timestamp when execution started
     */
    private void executeCopy(CopyStatement statement, long started) {
        engine.copyFile(statement.tableName(), statement.csvFilePath());
        LOGGER.debug("statement=COPY table={} file={} durationMs={}",
                statement.tableName(), statement.csvFilePath(), elapsedMillis(started));
    }

    /**
     * Executes a SELECT statement by draining its operator pipeline to output.
     *
     * @param statement SELECT statement
     * @param started   nano timestamp when execution started
     */
    private void executeSelect(SelectStatement statement, long started) {
        Operator plan = planner.plan(statement);
        long rowsOut = drainToOutput(plan);
        LOGGER.debug("statement=SELECT table={} rowsOut={} durationMs={}",
                statement.tableName(), rowsOut, elapsedMillis(started));
    }

    /**
     * Drains an operator pipeline, formatting each emitted row as CSV to the output
     * stream.
     *
     * @param operator pipeline root operator
     * @return total count of emitted rows
     */
    private long drainToOutput(Operator operator) {
        operator.open();
        long rowsOut = 0;
        try {
            Object[] row;
            while ((row = operator.next()) != null) {
                output.println(formatCsvRow(row));
                rowsOut++;
            }
            return rowsOut;
        } finally {
            operator.close();
        }
    }

    /**
     * Drains an operator pipeline into an in-memory row list.
     *
     * @param operator pipeline root operator
     * @return collected rows
     */
    private static List<Object[]> drainToList(Operator operator) {
        operator.open();
        try {
            List<Object[]> rows = new ArrayList<>();
            Object[] row;
            while ((row = operator.next()) != null) {
                rows.add(row);
            }
            return rows;
        } finally {
            operator.close();
        }
    }

    /**
     * Formats an array of column values as a single headerless CSV row.
     *
     * @param row column values in schema order
     * @return CSV formatted string
     */
    private static String formatCsvRow(Object[] row) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < row.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            if (row[index] != null) {
                builder.append(row[index]);
            }
        }
        return builder.toString();
    }

    /**
     * Computes elapsed milliseconds from a starting nano timestamp.
     *
     * @param started starting time in nanoseconds
     * @return elapsed milliseconds
     */
    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
