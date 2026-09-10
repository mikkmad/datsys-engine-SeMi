package datasys.semi;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Main command-line entry point demonstrating the SQL front-end parsing and
 * pretty-printing.
 */
public final class Engine {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);
    private static final String DEMO_SQL = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'trips.csv';
            SELECT * FROM trips WHERE distance > 100;
            SELECT * FROM trips;
            """;

    /**
     * Entry point executing the Task 1 demonstration script.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");

        SqlParser parser = new SqlParser();
        List<Statement> statements = parser.parse(DEMO_SQL);
        SqlPrinter printer = new SqlPrinter();

        for (Statement statement : statements) {
            System.out.println(printer.print(statement));
        }

        LOGGER.debug("engine stopped");
    }

    /**
     * Returns the name of the project team.
     *
     * @return team name string
     */
    String teamName() {
        return "Team SeMi";
    }
}
