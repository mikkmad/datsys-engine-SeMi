package datasys.semi;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import datasys.semi.engine.StorageEngine;
import datasys.semi.executor.Executor;
import datasys.semi.optimizer.JoinOrderMode;
import datasys.semi.util.LogSanitizer;

/**
 * Main command-line front door for the analytical storage engine.
 *
 * <p>
 * Dispatches single-statement interactive queries, multi-statement SQL script
 * files via the {@code -f} flag, or usage help when invoked without arguments.
 * The optional {@code -j optimized|syntax} flag chooses the join order mode;
 * {@code optimized} is the default.
 * Query results are emitted to standard output as headerless CSV, while
 * operational
 * logging and error messages are written strictly to standard error.
 */
public final class Engine {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);
    private static final String DEFAULT_STATEMENT_NUMBER = "0";
    private static final String TEAM_NAME = "Team SeMi";

    // --- Command-Line Flags ---
    private static final String SQL_FLAG = "-c";
    private static final String SCRIPT_FLAG = "-f";
    private static final String JOIN_ORDER_FLAG = "-j";
    private static final Set<String> SUPPORTED_FLAGS = Set.of(SQL_FLAG, SCRIPT_FLAG, JOIN_ORDER_FLAG);

    /**
     * Constructs an Engine instance.
     */
    public Engine() {
    }

    /**
     * Entry point executing SQL commands from the command line.
     *
     * @param args command-line arguments: empty for help, {@code -c <SQL>} for a statement or
     *             {@code -f <file>} for a script, optionally with {@code -j optimized|syntax}
     */
    public static void main(String[] args) {
        run(args, defaultDataDirectory(), System.out, System.err);
    }

    /**
     * Executes front door operations with configurable storage path and output
     * streams.
     *
     * @param args          command-line argument array
     * @param dataDirectory base directory for engine catalogs and partition storage
     * @param out           target stream for query result rows
     * @param err           target stream for diagnostic and error messages
     */
    public static void run(String[] args, Path dataDirectory, PrintStream out, PrintStream err) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", DEFAULT_STATEMENT_NUMBER);
        LOGGER.debug("engine started");

        try {
            if (args == null || args.length == 0) {
                printUsage(out);
                return;
            }
            Map<String, String> flags = parseFlags(args);
            JoinOrderMode joinOrderMode = JoinOrderMode.fromFlag(
                    flags.getOrDefault(JOIN_ORDER_FLAG, JoinOrderMode.OPTIMIZED.flagValue()));
            StorageEngine engine = new StorageEngine(dataDirectory);
            Executor executor = new Executor(engine, out, joinOrderMode);
            dispatchStatements(flags, executor);
        } catch (Exception exception) {
            LOGGER.error("Engine execution failed: {}", LogSanitizer.sanitize(exception.getMessage()));
            err.println("Error: " + exception.getMessage());
        } finally {
            MDC.put("statementNumber", DEFAULT_STATEMENT_NUMBER);
            LOGGER.debug("engine stopped");
            MDC.remove("sessionId");
        }
    }

    /**
     * Parses the command line into flag/value pairs.
     *
     * @param args command-line arguments, pairs of a flag and its value
     * @return flag to value, with exactly one of {@code -c} and {@code -f}
     * @throws IllegalArgumentException if an argument has no value, a flag is
     *                                  unknown or repeated, or not exactly one of
     *                                  {@code -c} and {@code -f} is given
     */
    private static Map<String, String> parseFlags(String[] args) {
        if (args.length % 2 != 0) {
            throw invalidArguments();
        }
        Map<String, String> flags = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            String flag = args[index];
            if (!SUPPORTED_FLAGS.contains(flag) || flags.putIfAbsent(flag, args[index + 1]) != null) {
                throw invalidArguments();
            }
        }
        if (flags.containsKey(SQL_FLAG) == flags.containsKey(SCRIPT_FLAG)) {
            throw invalidArguments();
        }
        return flags;
    }

    /**
     * Executes the SQL given by {@code -c} or read from the {@code -f} script.
     *
     * @param flags    parsed flags with exactly one of {@code -c} and {@code -f}
     * @param executor executor configured to process SQL statements
     * @throws IOException if the script file cannot be read
     */
    private static void dispatchStatements(Map<String, String> flags, Executor executor) throws IOException {
        if (flags.containsKey(SQL_FLAG)) {
            executor.execute(flags.get(SQL_FLAG));
            return;
        }
        String sqlScript = Files.readString(Path.of(flags.get(SCRIPT_FLAG)));
        executor.execute(sqlScript);
    }

    /**
     * Builds the error for a command line that matches no supported shape.
     *
     * @return exception describing the supported arguments
     */
    private static IllegalArgumentException invalidArguments() {
        return new IllegalArgumentException("invalid arguments; expected '-c \"<SQL string>\"' or "
                + "'-f <script.sql>', optionally with '-j optimized' or '-j syntax'");
    }

    /**
     * Resolves the configured or default data directory path.
     *
     * @return path to storage directory
     */
    private static Path defaultDataDirectory() {
        return Path.of(System.getProperty("semi.data.dir", "data"));
    }

    /**
     * Prints team name and usage instructions to the designated stream.
     *
     * @param out output stream receiving usage instructions
     */
    private static void printUsage(PrintStream out) {
        out.println(teamName());
        out.println("Usage:");
        out.println("  java -jar engine.jar                   - Print team name and usage");
        out.println("  java -jar engine.jar -c \"<SQL string>\" - Execute one or more SQL statement(s)");
        out.println("  java -jar engine.jar -f <script.sql>   - Execute a SQL script file");
        out.println("  Add -j optimized (default) or -j syntax to choose the join order");
    }

    /**
     * Returns the name of the project team.
     *
     * @return team name string
     */
    public static String teamName() {
        return TEAM_NAME;
    }
}
