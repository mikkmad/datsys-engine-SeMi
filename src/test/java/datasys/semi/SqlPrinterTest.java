package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import datasys.semi.models.*;
import datasys.semi.parser.SqlParser;
import datasys.semi.parser.SqlPrinter;
import datasys.semi.schema.*;

/**
 * Unit tests verifying the round-trip parse(print(s)) property for all
 * statement shapes and literal types.
 */
class SqlPrinterTest {

        /** Grammar shape of DOUBLE_LITERAL: no exponent, digits on both sides of the point. */
        private static final String PLAIN_DECIMAL_PATTERN = "-?[0-9]+\\.[0-9]+";

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
        void roundTripCopyStatementWithEmptyPath() {
                CopyStatement emptyPathCopy = new CopyStatement("trips", "");
                assertEquals("COPY trips FROM '';", printer.print(emptyPathCopy));
                assertRoundTrip(emptyPathCopy);
        }

        @Test
        void roundTripSelectStatementsWithoutWhere() {
                SelectStatement selectAll = new SelectStatement("trips", Optional.empty(), Optional.empty());
                assertRoundTrip(selectAll);
        }

        @Test
        void roundTripSelectStatementsWithWhereComparisonsAndTypes() {
                // String EQUALS
                SelectStatement strEquals = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("city", Comparison.EQUALS, "Copenhagen")));
                assertRoundTrip(strEquals);

                // String LESS_THAN
                SelectStatement strLess = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("city", Comparison.LESS_THAN, "Odense")));
                assertRoundTrip(strLess);

                // String GREATER_THAN
                SelectStatement strGreater = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("city", Comparison.GREATER_THAN, "Aalborg")));
                assertRoundTrip(strGreater);

                // Long EQUALS (positive)
                SelectStatement longEquals = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("distance", Comparison.EQUALS, 100L)));
                assertRoundTrip(longEquals);

                // Long LESS_THAN (negative)
                SelectStatement longNeg = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("distance", Comparison.LESS_THAN, -50L)));
                assertRoundTrip(longNeg);

                // Long GREATER_THAN (zero)
                SelectStatement longZero = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("distance", Comparison.GREATER_THAN, 0L)));
                assertRoundTrip(longZero);

                // Double EQUALS (positive)
                SelectStatement doubleEquals = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("price", Comparison.EQUALS, 99.99)));
                assertRoundTrip(doubleEquals);

                // Double LESS_THAN (negative)
                SelectStatement doubleNeg = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("price", Comparison.LESS_THAN, -12.5)));
                assertRoundTrip(doubleNeg);

                // Double GREATER_THAN (whole-value double)
                SelectStatement doubleWhole = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("price", Comparison.GREATER_THAN, 300.0)));
                assertRoundTrip(doubleWhole);
        }

        @Test
        void extremeDoublesPrintAsPlainDecimalsAndRoundTripExactly() {
                List<Double> boundaryValues = List.of(
                                Double.MIN_VALUE, -Double.MIN_VALUE,
                                Double.MIN_NORMAL, -Double.MIN_NORMAL,
                                Double.MAX_VALUE, -Double.MAX_VALUE,
                                Math.nextDown(Double.MIN_NORMAL),
                                1.0E-3, Math.nextDown(1.0E-3), 1.0E-4, 1.0E-5,
                                1.0E7, Math.nextDown(1.0E7), 1.0E8, 1.0E20,
                                1.234567890123456789, 0.1, -12.5, 9007199254740993.0);

                for (double value : boundaryValues) {
                        String literal = printDoubleLiteral(value);
                        assertTrue(literal.matches(PLAIN_DECIMAL_PATTERN),
                                        () -> "not a plain decimal for " + value + ": " + literal);
                        assertEquals(0, Double.compare(value, Double.parseDouble(literal)),
                                        () -> "precision lost for " + value + ": " + literal);
                        assertRoundTrip(selectWithPrice(value));
                }
        }

        @Test
        void doubleMinValuePrintsWithoutExponent() {
                String literal = printDoubleLiteral(Double.MIN_VALUE);
                assertTrue(literal.startsWith("0.000"));
                assertTrue(literal.endsWith("49"));
                assertEquals(-1, literal.indexOf('E'));
        }

        @Test
        void doubleMaxValuePrintsWithTrailingFractionDigit() {
                String literal = printDoubleLiteral(Double.MAX_VALUE);
                assertTrue(literal.startsWith("17976931348623157"));
                assertTrue(literal.endsWith(".0"));
                assertEquals(309 + ".0".length(), literal.length());
        }

        @Test
        void signedZeroIsPreservedThroughRoundTrip() {
                assertEquals("-0.0", printDoubleLiteral(-0.0));
                assertEquals("0.0", printDoubleLiteral(0.0));

                Statement reparsedNegativeZero = parser.parse(printer.print(selectWithPrice(-0.0))).getFirst();
                Object constant = ((SelectStatement) reparsedNegativeZero).where().orElseThrow().constant();
                assertEquals(0, Double.compare(-0.0, (Double) constant));
                assertRoundTrip(selectWithPrice(-0.0));
                assertRoundTrip(selectWithPrice(0.0));
        }

        @Test
        void longExtremesRoundTrip() {
                for (long value : List.of(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L)) {
                        SelectStatement select = new SelectStatement("trips", Optional.empty(), Optional.of(
                                        new Predicate("distance", Comparison.EQUALS, value)));
                        assertRoundTrip(select);
                }
        }

        @Test
        void nonFiniteDoublesAreRejectedByPrinter() {
                for (double value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                        assertThrows(IllegalArgumentException.class, () -> printer.print(selectWithPrice(value)));
                }
        }

        private SelectStatement selectWithPrice(double price) {
                return new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("price", Comparison.EQUALS, price)));
        }

        private String printDoubleLiteral(double value) {
                String printedSql = printer.print(selectWithPrice(value));
                String prefix = "SELECT * FROM trips WHERE price = ";
                assertTrue(printedSql.startsWith(prefix) && printedSql.endsWith(";"), printedSql);
                return printedSql.substring(prefix.length(), printedSql.length() - 1);
        }

        private void assertRoundTrip(Statement statement) {
                String printedSql = printer.print(statement);
                List<Statement> parsedStatements = parser.parse(printedSql);
                assertEquals(1, parsedStatements.size());
                assertEquals(statement, parsedStatements.getFirst());
        }
}
