package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import datasys.semi.engine.StorageEngine;
import datasys.semi.models.CreateTableStatement;
import datasys.semi.models.Statement;
import datasys.semi.parser.Binder;
import datasys.semi.parser.SqlParseException;
import datasys.semi.parser.SqlParser;
import datasys.semi.parser.SqlPrinter;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;

/**
 * Replays the Exercise 3 evaluation corpus (77 good, 87 bad cases) through the
 * SQL front end, mirroring the professor's harness: parse, bind, print, and
 * reparse the printed text to an equal AST; bad cases must be rejected at the
 * stage their label names. Cases run sequentially because later good cases
 * bind against tables created by earlier ones.
 */
class SqlCorpusIT {

    // --- Corpus Resources ---
    private static final String GOOD_CORPUS_RESOURCE = "/sql-corpus/good_sql_queries.txt";
    private static final String BAD_CORPUS_RESOURCE = "/sql-corpus/bad_sql_queries.txt";
    private static final Pattern CASE_PATTERN = Pattern.compile(
            "^-- BEGIN CASE (\\w+) \\| ([^\\n]*)\\n(.*?)^-- END CASE$", Pattern.DOTALL | Pattern.MULTILINE);

    // --- Expected Corpus Sizes ---
    private static final int GOOD_CASE_COUNT = 77;
    private static final int BAD_CASE_COUNT = 87;

    // --- Cases Superseded by Later Exercises ---
    /**
     * Bad cases written against the Exercise 3 grammar that later, intentional
     * language extensions now accept. These must parse and bind successfully.
     */
    private static final Map<String, String> SUPERSEDED_BAD_CASES = Map.of(
            "B0029", "Exercise 4 section 5: column lists (SELECT city FROM trips) are part of the grammar");

    // --- Binder Fixture ---
    private static final String TRIPS_TABLE = "trips";
    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // --- Front End ---
    private final SqlParser parser = new SqlParser();
    private final SqlPrinter printer = new SqlPrinter();

    /**
     * One corpus case extracted from a BEGIN/END CASE block.
     *
     * @param caseId      case identifier such as {@code G0036}
     * @param description header text after the case identifier
     * @param sqlText     exact SQL payload between the markers
     */
    private record CorpusCase(String caseId, String description, String sqlText) {
    }

    /**
     * Builds one dynamic test per good case: each must parse, bind, and
     * round-trip through the printer to an equal AST.
     *
     * @param dataDirectory temporary engine directory shared by all good cases
     * @return dynamic tests in corpus order
     * @throws IOException if the corpus resource cannot be read
     */
    @TestFactory
    Stream<DynamicTest> goodCasesParseBindAndRoundTrip(@TempDir Path dataDirectory) throws IOException {
        List<CorpusCase> cases = readCorpusCases(GOOD_CORPUS_RESOURCE);
        assertEquals(GOOD_CASE_COUNT, cases.size());

        StorageEngine engine = new StorageEngine(dataDirectory);
        Binder binder = new Binder(engine);
        Set<String> createdTables = new HashSet<>();
        return cases.stream().map(corpusCase -> DynamicTest.dynamicTest(displayName(corpusCase),
                () -> verifyGoodCase(corpusCase, engine, binder, createdTables)));
    }

    /**
     * Builds one dynamic test per bad case: lexer and parser cases must throw
     * SqlParseException with a position; binder cases must parse and then fail
     * binding with IllegalArgumentException. Cases listed in
     * {@link #SUPERSEDED_BAD_CASES} must instead be accepted.
     *
     * @param dataDirectory temporary engine directory holding the trips table
     * @return dynamic tests in corpus order
     * @throws IOException if the corpus resource cannot be read
     */
    @TestFactory
    Stream<DynamicTest> badCasesAreRejectedAtTheirLabelledStage(@TempDir Path dataDirectory) throws IOException {
        List<CorpusCase> cases = readCorpusCases(BAD_CORPUS_RESOURCE);
        assertEquals(BAD_CASE_COUNT, cases.size());

        StorageEngine engine = new StorageEngine(dataDirectory);
        engine.createTable(TRIPS_TABLE, TRIPS_SCHEMA);
        Binder binder = new Binder(engine);
        return cases.stream().map(corpusCase -> DynamicTest.dynamicTest(displayName(corpusCase),
                () -> verifyBadCase(corpusCase, binder)));
    }

    /**
     * Parses, binds, prints, and reparses every statement in a good case.
     * Bound CREATE TABLE statements are applied to the engine so that later
     * cases can bind against them.
     *
     * @param corpusCase    the good case under test
     * @param engine        engine whose catalog accumulates created tables
     * @param binder        binder over that engine
     * @param createdTables names of tables already created by earlier cases
     */
    private void verifyGoodCase(CorpusCase corpusCase, StorageEngine engine, Binder binder,
            Set<String> createdTables) {
        List<Statement> statements = parser.parse(corpusCase.sqlText());
        assertFalse(statements.isEmpty());

        for (Statement statement : statements) {
            binder.bind(statement);
            createTableIfAbsent(statement, engine, createdTables);

            String printedSql = printer.print(statement);
            List<Statement> reparsed = parser.parse(printedSql);
            assertEquals(List.of(statement), reparsed, () -> "round-trip mismatch for: " + printedSql);
        }
    }

    /**
     * Asserts that a bad case is rejected at the stage named in its label, or
     * accepted if a later exercise superseded it.
     *
     * @param corpusCase the bad case under test
     * @param binder     binder over an engine holding the trips table
     */
    private void verifyBadCase(CorpusCase corpusCase, Binder binder) {
        if (SUPERSEDED_BAD_CASES.containsKey(corpusCase.caseId())) {
            assertDoesNotThrow(() -> parser.parse(corpusCase.sqlText()).forEach(binder::bind),
                    SUPERSEDED_BAD_CASES.get(corpusCase.caseId()));
            return;
        }

        String stage = corpusCase.description().substring(0, corpusCase.description().indexOf(' '));
        switch (stage) {
            case "LEXER", "PARSER" -> {
                SqlParseException exception = assertThrows(SqlParseException.class,
                        () -> parser.parse(corpusCase.sqlText()));
                assertTrue(exception.line() >= 1, "line must be 1-based");
                assertTrue(exception.column() >= 0, "column must be 0-based");
            }
            case "BINDER" -> {
                List<Statement> statements = assertDoesNotThrow(() -> parser.parse(corpusCase.sqlText()));
                assertThrows(IllegalArgumentException.class,
                        () -> statements.forEach(binder::bind));
            }
            default -> throw new IllegalStateException("unknown stage label: " + stage);
        }
    }

    /**
     * Applies a bound CREATE TABLE statement to the engine unless an earlier
     * case already created that table.
     *
     * @param statement     a statement that has passed binding
     * @param engine        target engine
     * @param createdTables names of tables already created; updated in place
     */
    private static void createTableIfAbsent(Statement statement, StorageEngine engine, Set<String> createdTables) {
        if (!(statement instanceof CreateTableStatement createTable)) {
            return;
        }
        if (createdTables.add(createTable.tableName())) {
            engine.createTable(createTable.tableName(), createTable.columns());
        }
    }

    /**
     * Reads a corpus resource and splits it into its BEGIN/END CASE blocks,
     * preserving the exact payload bytes (including CR and U+000B).
     *
     * @param resourceName classpath resource path of the corpus file
     * @return cases in file order
     * @throws IOException if the resource cannot be read
     */
    private static List<CorpusCase> readCorpusCases(String resourceName) throws IOException {
        String corpusText;
        try (InputStream inputStream = SqlCorpusIT.class.getResourceAsStream(resourceName)) {
            assertTrue(inputStream != null, "missing corpus resource " + resourceName);
            corpusText = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        Matcher matcher = CASE_PATTERN.matcher(corpusText);
        List<CorpusCase> cases = new ArrayList<>();
        while (matcher.find()) {
            cases.add(new CorpusCase(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        return cases;
    }

    /**
     * Builds a short, readable dynamic-test display name.
     *
     * @param corpusCase the corpus case
     * @return case identifier followed by its description
     */
    private static String displayName(CorpusCase corpusCase) {
        return corpusCase.caseId() + " " + corpusCase.description();
    }
}
