package datasys.semi;

import java.util.List;
import java.util.UUID;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import datasys.semi.sql.SqlLexer;

/**
 * Facade providing SQL script parsing into strongly-typed AST statements.
 */
public final class SqlParser {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(SqlParser.class);

    /**
     * Constructs a new SqlParser.
     */
    public SqlParser() {
    }

    /**
     * Parses a whole script of semicolon-terminated statements into AST statements.
     *
     * @param sqlText the SQL input text containing one or more statements
     * @return list of parsed AST statements in execution order
     * @throws IllegalArgumentException if sqlText is null
     * @throws SqlParseException        if a lexical or grammatical syntax error
     *                                  occurs
     */
    @SuppressWarnings("unchecked")
    public List<Statement> parse(String sqlText) {
        if (sqlText == null) {
            throw new IllegalArgumentException("SQL text must not be null");
        }

        ensureMdcContext();
        long startNanoTime = System.nanoTime();

        try {
            datasys.semi.sql.SqlParser antlrParser = createAntlrParser(sqlText);
            datasys.semi.sql.SqlParser.ScriptContext scriptContext = antlrParser.script();
            List<Statement> statements = (List<Statement>) new SqlAstBuilder().visit(scriptContext);

            long durationInMs = calculateElapsedMillis(startNanoTime);
            LOGGER.debug("statements={} durationMs={}", statements.size(), durationInMs);
            return statements;

        } catch (SqlParseException exception) {
            long durationInMs = calculateElapsedMillis(startNanoTime);
            LOGGER.error("failed line={} col={} durationMs={}", exception.line(), exception.column(), durationInMs);
            throw exception;

        } catch (RuntimeException exception) {
            long durationInMs = calculateElapsedMillis(startNanoTime);
            LOGGER.error("failed line=0 col=0 durationMs={}", durationInMs);
            throw exception;
        }
    }

    /**
     * Creates and configures the ANTLR parser with fail-fast error listeners.
     *
     * @param sqlText input SQL string
     * @return configured ANTLR SqlParser instance
     */
    private static datasys.semi.sql.SqlParser createAntlrParser(String sqlText) {
        SqlLexer lexer = new SqlLexer(CharStreams.fromString(sqlText));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SqlErrorListener.INSTANCE);

        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        datasys.semi.sql.SqlParser parser = new datasys.semi.sql.SqlParser(tokenStream);
        parser.removeErrorListeners();
        parser.addErrorListener(SqlErrorListener.INSTANCE);

        return parser;
    }

    /**
     * Initializes logging MDC context with sessionId and statementNumber if absent.
     */
    private static void ensureMdcContext() {
        if (MDC.get("sessionId") == null) {
            MDC.put("sessionId", UUID.randomUUID().toString());
        }
        if (MDC.get("statementNumber") == null) {
            MDC.put("statementNumber", "0");
        }
    }

    /**
     * Computes elapsed wall-clock time in milliseconds since the start timestamp.
     *
     * @param startNanoTime starting time from System.nanoTime()
     * @return elapsed duration in milliseconds
     */
    private static long calculateElapsedMillis(long startNanoTime) {
        return (System.nanoTime() - startNanoTime) / 1_000_000L;
    }
}
