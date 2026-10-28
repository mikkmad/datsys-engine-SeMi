package datasys.semi.executor;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import datasys.semi.engine.CopyResult;
import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.models.CopyStatement;
import datasys.semi.models.CreateTableStatement;
import datasys.semi.models.DeleteStatement;
import datasys.semi.models.ScanStats;
import datasys.semi.models.SelectStatement;
import datasys.semi.models.Statement;
import datasys.semi.operators.Operator;
import datasys.semi.parser.Binder;
import datasys.semi.parser.SqlParser;
import datasys.semi.planner.Planner;
import datasys.semi.util.LogSanitizer;

/**
 * Orchestrates statement-by-statement execution through snapshot pinning,
 * parse, bind, plan, and execute.
 *
 * <p>
 * Maintains statement sequencing in the SLF4J MDC ({@code statementNumber}),
 * pins a {@link Catalog} snapshot at statement start before binding, dispatches
 * DDL and COPY statements to the storage engine, and runs SELECT statements as
 * Volcano operator pipelines. Output from SELECT is emitted as headerless CSV
 * to the configured output stream. Execution halts immediately upon
 * encountering any error.
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
     * @throws IllegalArgumentException if {@code engine} is null
     */
    public Executor(StorageEngine engine) {
        this(engine, System.out);
    }

    /**
     * Constructs an Executor writing SELECT CSV output to a designated stream.
     *
     * @param engine storage engine backing tables and catalogs
     * @param output target print stream for query results
     * @throws IllegalArgumentException if {@code engine} or {@code output} is null
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
     * @throws IllegalArgumentException if {@code sql} is null or execution fails
     */
    public void execute(String sql) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null");
        }
        if (MDC.get("statementNumber") == null) {
            MDC.put("statementNumber", DEFAULT_STATEMENT_NUMBER);
        }

        List<Statement> statements = parser.parse(sql);
        execute(statements);
    }

    /**
     * Executes a list of parsed statements, tracking statement numbers in the MDC.
     *
     * @param statements statements to bind and execute
     * @throws IllegalArgumentException if {@code statements} is null or execution
     *                                  fails
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
                try {
                    executeStatement(statement);
                } catch (RuntimeException exception) {
                    LOGGER.error("statement_failed operation={} reason={}",
                            resolveOperation(statement), LogSanitizer.sanitize(exception.getMessage()));
                    throw exception;
                }
            }
        } finally {
            MDC.put("statementNumber", DEFAULT_STATEMENT_NUMBER);
        }
    }

    /**
     * Executes a single query against a pinned catalog snapshot and returns the
     * matching rows in memory.
     *
     * @param sql SQL query string containing a single SELECT statement
     * @return list of rows produced by the query
     * @throws IllegalArgumentException if SQL does not yield exactly one SELECT
     *                                  statement
     */
    public List<Object[]> executeQuery(String sql) {
        MDC.put("statementNumber", "1");
        try {
            SelectStatement select = parseSingleSelect(sql);
            long started = System.nanoTime();
            Catalog snapshot = engine.snapshot(select.tableName());
            binder.bind(select, snapshot);
            Operator plan = planner.plan(select, snapshot);
            ScanStats stats = planner.lastScanStats();
            List<Object[]> rows = drainToList(plan);
            logSelectSummary(select.tableName(), rows.size(), stats, started);
            return rows;
        } catch (RuntimeException exception) {
            LOGGER.error("statement_failed operation=SELECT reason={}",
                    LogSanitizer.sanitize(exception.getMessage()));
            throw exception;
        } finally {
            MDC.put("statementNumber", DEFAULT_STATEMENT_NUMBER);
        }
    }

    /**
     * Parses a SQL string and verifies that it yields exactly one SELECT statement.
     *
     * @param sql SQL query string to parse
     * @return the single parsed SelectStatement
     * @throws IllegalArgumentException if {@code sql} is null or does not yield
     *                                  exactly one SELECT statement
     */
    private SelectStatement parseSingleSelect(String sql) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null");
        }

        List<Statement> statements = parser.parse(sql);
        if (statements.size() != 1 || !(statements.getFirst() instanceof SelectStatement select)) {
            throw new IllegalArgumentException("expected exactly one SELECT statement");
        }
        return select;
    }

    /**
     * Pins a catalog snapshot (if applicable), binds, plans, and executes a single
     * statement according to its type.
     *
     * @param statement the AST statement to execute
     * @throws IllegalArgumentException if {@code statement} is null or fails
     *                                  binding or execution
     */
    private void executeStatement(Statement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("statement must not be null");
        }
        long started = System.nanoTime();

        switch (statement) {
            case CreateTableStatement createTable -> {
                binder.bind(createTable);
                executeCreateTable(createTable, started);
            }
            case CopyStatement copy -> {
                Catalog snapshot = engine.snapshot(copy.tableName());
                binder.bind(copy, snapshot);
                executeCopy(copy, snapshot, started);
            }
            case SelectStatement select -> {
                Catalog snapshot = engine.snapshot(select.tableName());
                binder.bind(select, snapshot);
                executeSelect(select, snapshot, started);
            }
            case DeleteStatement delete -> {
                Catalog snapshot = engine.snapshot(delete.tableName());
                binder.bind(delete, snapshot);
                executeDelete(delete, snapshot, started);
            }
        }
    }

    /**
     * Resolves the operation name for a statement for logging purposes.
     *
     * @param statement statement to resolve
     * @return operation name string
     */
    private static String resolveOperation(Statement statement) {
        if (statement == null) {
            return "UNKNOWN";
        }
        return switch (statement) {
            case CreateTableStatement _ -> "CREATE_TABLE";
            case CopyStatement _ -> "COPY";
            case SelectStatement _ -> "SELECT";
            case DeleteStatement _ -> "DELETE";
        };
    }

    /**
     * Executes a {@code DELETE} statement against the pinned snapshot.
     * Stubbed with {@link UnsupportedOperationException} until Exercise 7 §4.
     *
     * @param statement DELETE statement
     * @param snapshot  pinned catalog snapshot at statement start
     * @param started   nano timestamp when execution started
     * @throws UnsupportedOperationException always thrown in Exercise 7 §3
     */
    private void executeDelete(DeleteStatement statement, Catalog snapshot, long started) {
        throw new UnsupportedOperationException("DELETE execution is not implemented yet");
    }

    /**
     * Executes a {@code CREATE TABLE} statement and logs version 0.
     *
     * @param statement CREATE TABLE statement
     * @param started   nano timestamp when execution started
     */
    private void executeCreateTable(CreateTableStatement statement, long started) {
        engine.createTable(statement.tableName(), statement.columns());
        LOGGER.debug("statement=CREATE_TABLE table={} version=0 durationMs={}",
                statement.tableName(), elapsedMillis(started));
    }

    /**
     * Executes a {@code COPY} statement against the pinned snapshot and logs the
     * committed version and partition counts.
     *
     * @param statement COPY statement
     * @param snapshot  pinned catalog snapshot at statement start
     * @param started   nano timestamp when execution started
     */
    private void executeCopy(CopyStatement statement, Catalog snapshot, long started) {
        CopyResult result = engine.copyFile(snapshot, statement.csvFilePath());
        LOGGER.debug("statement=COPY table={} file={} rows={} partitions={} version={} durationMs={}",
                statement.tableName(), LogSanitizer.sanitize(statement.csvFilePath()), result.rows(),
                result.partitions(), result.version(), elapsedMillis(started));
    }

    /**
     * Executes a {@code SELECT} statement against the pinned snapshot by draining
     * its operator pipeline to output.
     *
     * @param statement SELECT statement
     * @param snapshot  pinned catalog snapshot at statement start
     * @param started   nano timestamp when execution started
     */
    private void executeSelect(SelectStatement statement, Catalog snapshot, long started) {
        Operator plan = planner.plan(statement, snapshot);
        ScanStats stats = planner.lastScanStats();
        long rowsOut = drainToOutput(plan);
        logSelectSummary(statement.tableName(), rowsOut, stats, started);
    }

    /**
     * Logs the summary line of a completed {@code SELECT} statement.
     *
     * @param tableName target table name
     * @param rowsOut   number of rows emitted by the query
     * @param stats     scan statistics from the planner
     * @param started   nano timestamp when execution started
     */
    private static void logSelectSummary(String tableName, long rowsOut, ScanStats stats, long started) {
        LOGGER.debug(
                "statement=SELECT table={} rowsOut={} partitionsRead={} partitionsPruned={} version={} durationMs={}",
                tableName, rowsOut, stats.partitionsRead(), stats.partitionsPruned(),
                stats.version(), elapsedMillis(started));
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
