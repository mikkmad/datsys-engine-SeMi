package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the SqlParser facade validating AST construction, typing, and
 * syntax error coordinates.
 */
class SqlParserTest {

        private final SqlParser parser = new SqlParser();

        // Test 1: Each statement shape parses to the expected AST
        @Test
        void parsesEachStatementShape() {
                // 1.1 CREATE TABLE
                List<Statement> createResults = parser
                                .parse("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);");
                assertEquals(1, createResults.size());
                CreateTableStatement expectedCreate = new CreateTableStatement("trips", List.of(
                                new ColumnSpec("city", ColumnType.STRING),
                                new ColumnSpec("distance", ColumnType.LONG),
                                new ColumnSpec("price", ColumnType.DOUBLE)));
                assertEquals(expectedCreate, createResults.getFirst());

                // 1.2 COPY
                List<Statement> copyResults = parser.parse("COPY trips FROM 'trips.csv';");
                assertEquals(1, copyResults.size());
                CopyStatement expectedCopy = new CopyStatement("trips", "trips.csv");
                assertEquals(expectedCopy, copyResults.getFirst());

                // 1.3 SELECT with WHERE
                List<Statement> selectWithWhereResults = parser.parse("SELECT * FROM trips WHERE distance > 100;");
                assertEquals(1, selectWithWhereResults.size());
                SelectStatement expectedSelectWithWhere = new SelectStatement(
                                "trips",
                                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 100L)));
                assertEquals(expectedSelectWithWhere, selectWithWhereResults.getFirst());

                // 1.4 SELECT without WHERE
                List<Statement> selectNoWhereResults = parser.parse("SELECT * FROM trips;");
                assertEquals(1, selectNoWhereResults.size());
                SelectStatement expectedSelectNoWhere = new SelectStatement("trips", Optional.empty());
                assertEquals(expectedSelectNoWhere, selectNoWhereResults.getFirst());

                // 1.5 Multi-statement script
                String script = """
                                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                                COPY trips FROM 'trips.csv';
                                SELECT * FROM trips WHERE distance > 100;
                                SELECT * FROM trips;
                                """;
                List<Statement> scriptResults = parser.parse(script);
                assertEquals(4, scriptResults.size());
                assertEquals(expectedCreate, scriptResults.get(0));
                assertEquals(expectedCopy, scriptResults.get(1));
                assertEquals(expectedSelectWithWhere, scriptResults.get(2));
                assertEquals(expectedSelectNoWhere, scriptResults.get(3));
        }

        // Test 2: Literal typing - 12 -> Long, 12.0 -> Double, '12' -> String, -1 ->
        // Long, -1.5 -> Double
        @Test
        void parsesTypedLiteralsCorrectly() {
                // Positive Long: 12
                SelectStatement selectLong = (SelectStatement) parser.parse("SELECT * FROM trips WHERE distance = 12;")
                                .getFirst();
                Object longVal = selectLong.where().orElseThrow().constant();
                assertInstanceOf(Long.class, longVal);
                assertEquals(12L, longVal);

                // Positive Double: 12.0
                SelectStatement selectDouble = (SelectStatement) parser.parse("SELECT * FROM trips WHERE price = 12.0;")
                                .getFirst();
                Object doubleVal = selectDouble.where().orElseThrow().constant();
                assertInstanceOf(Double.class, doubleVal);
                assertEquals(12.0, doubleVal);

                // String: '12'
                SelectStatement selectString = (SelectStatement) parser.parse("SELECT * FROM trips WHERE city = '12';")
                                .getFirst();
                Object stringVal = selectString.where().orElseThrow().constant();
                assertInstanceOf(String.class, stringVal);
                assertEquals("12", stringVal);

                // Negative Long: -1
                SelectStatement selectNegLong = (SelectStatement) parser
                                .parse("SELECT * FROM trips WHERE distance = -1;").getFirst();
                Object negLongVal = selectNegLong.where().orElseThrow().constant();
                assertInstanceOf(Long.class, negLongVal);
                assertEquals(-1L, negLongVal);

                // Negative Double: -1.5
                SelectStatement selectNegDouble = (SelectStatement) parser
                                .parse("SELECT * FROM trips WHERE price = -1.5;").getFirst();
                Object negDoubleVal = selectNegDouble.where().orElseThrow().constant();
                assertInstanceOf(Double.class, negDoubleVal);
                assertEquals(-1.5, negDoubleVal);
        }

        // Test 3: Keyword case-insensitivity while preserving identifier casing
        @Test
        void keywordCaseInsensitivePreservingIdentifierCasing() {
                List<Statement> lowerStatements = parser.parse("select * from TriPs;");
                assertEquals(1, lowerStatements.size());
                SelectStatement lowerSelect = (SelectStatement) lowerStatements.getFirst();
                assertEquals("TriPs", lowerSelect.tableName());

                List<Statement> mixedStatements = parser
                                .parse("cReAtE tAbLe MyTable (CityName sTrInG, MaxDist lOnG, AvgCost dOuBlE);");
                assertEquals(1, mixedStatements.size());
                CreateTableStatement create = (CreateTableStatement) mixedStatements.getFirst();
                assertEquals("MyTable", create.tableName());
                assertEquals("CityName", create.columns().get(0).name());
                assertEquals(ColumnType.STRING, create.columns().get(0).type());
                assertEquals("MaxDist", create.columns().get(1).name());
                assertEquals(ColumnType.LONG, create.columns().get(1).type());
                assertEquals("AvgCost", create.columns().get(2).name());
                assertEquals(ColumnType.DOUBLE, create.columns().get(2).type());
        }

        // Test 4: At least five malformed inputs, each asserting line and column
        @Test
        void malformedInputsAssertLineAndColumn() {
                // 4.1 Missing semicolon
                SqlParseException missingSemicolon = assertThrows(SqlParseException.class,
                                () -> parser.parse("SELECT * FROM trips"));
                assertEquals(1, missingSemicolon.line());
                assertEquals(19, missingSemicolon.column());

                // 4.2 Unbalanced parentheses in CREATE TABLE
                SqlParseException unbalancedParens = assertThrows(SqlParseException.class,
                                () -> parser.parse("CREATE TABLE trips (city STRING;"));
                assertEquals(1, unbalancedParens.line());
                assertEquals(31, unbalancedParens.column());

                // 4.3 Unknown type name (city TEXT)
                SqlParseException unknownType = assertThrows(SqlParseException.class,
                                () -> parser.parse("CREATE TABLE trips (city TEXT);"));
                assertEquals(1, unknownType.line());
                assertEquals(25, unknownType.column());

                // 4.4 Unterminated string literal
                SqlParseException unterminatedString = assertThrows(SqlParseException.class,
                                () -> parser.parse("COPY trips FROM 'trips.csv;"));
                assertEquals(1, unterminatedString.line());
                assertEquals(16, unterminatedString.column());

                // 4.5 Missing FROM keyword
                SqlParseException missingFrom = assertThrows(SqlParseException.class,
                                () -> parser.parse("SELECT * trips;"));
                assertEquals(1, missingFrom.line());
                assertEquals(9, missingFrom.column());
        }

        // Test 5: Comments and whitespace are skipped
        @Test
        void commentsAndWhitespaceAreSkipped() {
                String sql = """
                                -- Initial setup comment
                                   CREATE TABLE   trips (
                                       city   STRING ,
                                       distance   LONG
                                   ) ; -- End of create table

                                -- Copy section
                                COPY trips FROM   'trips.csv'  ;

                                -- Query section
                                SELECT   *   FROM   trips   WHERE   distance   >   100 ;
                                """;

                List<Statement> statements = parser.parse(sql);
                assertEquals(3, statements.size());
                assertInstanceOf(CreateTableStatement.class, statements.get(0));
                assertInstanceOf(CopyStatement.class, statements.get(1));
                assertInstanceOf(SelectStatement.class, statements.get(2));
        }
}
