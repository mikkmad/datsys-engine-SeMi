package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying the round-trip parse(print(s)) property for all
 * statement shapes and literal types.
 */
class SqlPrinterTest {

        private final SqlParser parser = new SqlParser();
        private final SqlPrinter printer = new SqlPrinter();

        @Test
        void roundTripCreateTableStatements() {
                CreateTableStatement singleCol = new CreateTableStatement("trips", List.of(
                                new ColumnSpec("city", ColumnType.STRING)));
                assertRoundTrip(singleCol);

                CreateTableStatement multiCol = new CreateTableStatement("trips", List.of(
                                new ColumnSpec("city", ColumnType.STRING),
                                new ColumnSpec("distance", ColumnType.LONG),
                                new ColumnSpec("price", ColumnType.DOUBLE)));
                assertRoundTrip(multiCol);
        }

        @Test
        void roundTripCopyStatements() {
                CopyStatement simpleCopy = new CopyStatement("trips", "trips.csv");
                assertRoundTrip(simpleCopy);

                CopyStatement nestedPathCopy = new CopyStatement("trips", "data/sub/trips.csv");
                assertRoundTrip(nestedPathCopy);
        }

        @Test
        void roundTripSelectStatementsWithoutWhere() {
                SelectStatement selectAll = new SelectStatement("trips", Optional.empty());
                assertRoundTrip(selectAll);
        }

        @Test
        void roundTripSelectStatementsWithWhereComparisonsAndTypes() {
                // String EQUALS
                SelectStatement strEquals = new SelectStatement("trips", Optional.of(
                                new Predicate("city", Comparison.EQUALS, "Copenhagen")));
                assertRoundTrip(strEquals);

                // String LESS_THAN
                SelectStatement strLess = new SelectStatement("trips", Optional.of(
                                new Predicate("city", Comparison.LESS_THAN, "Odense")));
                assertRoundTrip(strLess);

                // String GREATER_THAN
                SelectStatement strGreater = new SelectStatement("trips", Optional.of(
                                new Predicate("city", Comparison.GREATER_THAN, "Aalborg")));
                assertRoundTrip(strGreater);

                // Long EQUALS (positive)
                SelectStatement longEquals = new SelectStatement("trips", Optional.of(
                                new Predicate("distance", Comparison.EQUALS, 100L)));
                assertRoundTrip(longEquals);

                // Long LESS_THAN (negative)
                SelectStatement longNeg = new SelectStatement("trips", Optional.of(
                                new Predicate("distance", Comparison.LESS_THAN, -50L)));
                assertRoundTrip(longNeg);

                // Long GREATER_THAN (zero)
                SelectStatement longZero = new SelectStatement("trips", Optional.of(
                                new Predicate("distance", Comparison.GREATER_THAN, 0L)));
                assertRoundTrip(longZero);

                // Double EQUALS (positive)
                SelectStatement doubleEquals = new SelectStatement("trips", Optional.of(
                                new Predicate("price", Comparison.EQUALS, 99.99)));
                assertRoundTrip(doubleEquals);

                // Double LESS_THAN (negative)
                SelectStatement doubleNeg = new SelectStatement("trips", Optional.of(
                                new Predicate("price", Comparison.LESS_THAN, -12.5)));
                assertRoundTrip(doubleNeg);

                // Double GREATER_THAN (whole-value double)
                SelectStatement doubleWhole = new SelectStatement("trips", Optional.of(
                                new Predicate("price", Comparison.GREATER_THAN, 300.0)));
                assertRoundTrip(doubleWhole);
        }

        private void assertRoundTrip(Statement statement) {
                String printedSql = printer.print(statement);
                List<Statement> parsedStatements = parser.parse(printedSql);
                assertEquals(1, parsedStatements.size());
                assertEquals(statement, parsedStatements.getFirst());
        }
}
