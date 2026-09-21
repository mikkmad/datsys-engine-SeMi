package datasys.semi;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import datasys.semi.engine.StorageEngine;
import datasys.semi.executor.Executor;

/**
 * Main command-line front door for the analytical storage engine.
 *
 * <p>
 * Dispatches single-statement interactive queries, multi-statement SQL script
 * files via the {@code -f} flag, or usage help when invoked without arguments.
 * Query results are emitted to standard output as headerless CSV, while
 * operational
 * logging and error messages are written strictly to standard error.
 */
public final class Engine {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);
    private static final String DEFAULT_STATEMENT_NUMBER = "0";
    private static final String TEAM_NAME = "Team SeMi";

    /**
     * Constructs an Engine instance.
     */
    public Engine() {
    }

    /**
     * Entry point executing SQL commands from the command line.
     *
     * @param args command-line arguments: empty for help, 1 arg for SQL, or 2 args
     *             (-f &lt;file&gt;) for a script
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
            StorageEngine engine = new StorageEngine(dataDirectory);
            Executor executor = new Executor(engine, out);
            dispatchArguments(args, executor);
        } catch (Exception exception) {
            LOGGER.error("Engine execution failed: {}", exception.getMessage(), exception);
            err.println("Error: " + exception.getMessage());
        } finally {
            MDC.put("statementNumber", DEFAULT_STATEMENT_NUMBER);
            LOGGER.debug("engine stopped");
            MDC.clear();
        }
    }

    /**
     * Dispatches command-line arguments to the appropriate executor routine.
     *
     * @param args     command-line arguments
     * @param executor executor configured to process SQL statements
     * @throws IOException              if a script file cannot be read
     * @throws IllegalArgumentException if arguments do not match supported CLI
     *                                  shapes
     */
    private static void dispatchArguments(String[] args, Executor executor) throws IOException {
        if (args.length == 1) {
            executor.execute(args[0]);
            return;
        }

        if (args.length == 2 && "-f".equals(args[0])) {
            Path scriptPath = Path.of(args[1]);
            String sqlScript = Files.readString(scriptPath);
            executor.execute(sqlScript);
            return;
        }

        throw new IllegalArgumentException("invalid arguments; expected single SQL string or '-f <script.sql>'");
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
        out.println(TEAM_NAME);
        out.println("Usage:");
        out.println("  java -jar engine.jar                  - Print team name and usage");
        out.println("  java -jar engine.jar \"<sql>\"          - Execute a single SQL statement");
        out.println("  java -jar engine.jar -f <script.sql>  - Execute a SQL script file");
    }

    /**
     * Returns the name of the project team.
     *
     * @return team name string
     */
    public String teamName() {
        return TEAM_NAME;
    }
}
