package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import datasys.semi.models.*;
import datasys.semi.parser.Binder;
import datasys.semi.schema.*;
import datasys.semi.engine.StorageEngine;

/**
 * Integration tests validating the catalog Binder against a temporary
 * StorageEngine.
 */
class BinderIT {

        private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE));

        @Test
        void validStatementsBindCleanly(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                engine.createTable("trips", TRIPS_SCHEMA);
                Binder binder = new Binder(engine);

                // CREATE TABLE with unique columns binds
                CreateTableStatement createTable = new CreateTableStatement("customers", List.of(
                                new ColumnSpec("id", ColumnType.LONG),
                                new ColumnSpec("name", ColumnType.STRING)));
                assertDoesNotThrow(() -> binder.bind(createTable));

                // COPY on existing table binds
                CopyStatement copy = new CopyStatement("trips", "trips.csv");
                assertDoesNotThrow(() -> binder.bind(copy));

                // SELECT without WHERE on existing table binds
                SelectStatement selectNoWhere = new SelectStatement("trips", Optional.empty(), Optional.empty());
                assertDoesNotThrow(() -> binder.bind(selectNoWhere));

                // SELECT with valid WHERE conditions across all types binds
                SelectStatement selectCity = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("city", Comparison.EQUALS, "Copenhagen")));
                assertDoesNotThrow(() -> binder.bind(selectCity));

                SelectStatement selectDistance = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("distance", Comparison.GREATER_THAN, 100L)));
                assertDoesNotThrow(() -> binder.bind(selectDistance));

                SelectStatement selectPrice = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("price", Comparison.LESS_THAN, 50.0)));
                assertDoesNotThrow(() -> binder.bind(selectPrice));

                // DELETE without WHERE on existing table binds
                DeleteStatement deleteNoWhere = new DeleteStatement("trips", Optional.empty());
                assertDoesNotThrow(() -> binder.bind(deleteNoWhere));

                // DELETE with valid WHERE conditions across all types binds
                DeleteStatement deleteCity = new DeleteStatement("trips", Optional.of(
                                new Predicate("city", Comparison.EQUALS, "Copenhagen")));
                assertDoesNotThrow(() -> binder.bind(deleteCity));

                DeleteStatement deleteDistance = new DeleteStatement("trips", Optional.of(
                                new Predicate("distance", Comparison.GREATER_THAN, 100L)));
                assertDoesNotThrow(() -> binder.bind(deleteDistance));

                DeleteStatement deletePrice = new DeleteStatement("trips", Optional.of(
                                new Predicate("price", Comparison.LESS_THAN, 50.0)));
                assertDoesNotThrow(() -> binder.bind(deletePrice));
        }

        @Test
        void unknownTableThrowsIllegalArgumentException(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                Binder binder = new Binder(engine);

                SelectStatement selectUnknownTable = new SelectStatement("non_existent", Optional.empty(), Optional.empty());
                assertThrows(IllegalArgumentException.class, () -> binder.bind(selectUnknownTable));

                CopyStatement copyUnknownTable = new CopyStatement("non_existent", "data.csv");
                assertThrows(IllegalArgumentException.class, () -> binder.bind(copyUnknownTable));

                DeleteStatement deleteUnknownTable = new DeleteStatement("non_existent", Optional.empty());
                assertThrows(IllegalArgumentException.class, () -> binder.bind(deleteUnknownTable));
        }

        @Test
        void deleteUnknownTableThrowsIllegalArgumentException(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                engine.createTable("trips", TRIPS_SCHEMA);
                Binder binder = new Binder(engine);

                DeleteStatement deleteUnknown = new DeleteStatement("missing_table", Optional.empty());
                assertThrows(IllegalArgumentException.class, () -> binder.bind(deleteUnknown));

                DeleteStatement deleteUnknownWithWhere = new DeleteStatement("missing_table", Optional.of(
                                new Predicate("city", Comparison.EQUALS, "Odense")));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(deleteUnknownWithWhere));

                // Mismatched snapshot
                StorageEngine.Catalog tripsSnapshot = engine.snapshot("trips");
                assertThrows(IllegalArgumentException.class, () -> binder.bind(deleteUnknown, tripsSnapshot));

                // Null snapshot
                assertThrows(IllegalArgumentException.class, () -> binder.bind(deleteUnknown, null));
        }

        @Test
        void deleteUnknownColumnInWhereThrowsIllegalArgumentException(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                engine.createTable("trips", TRIPS_SCHEMA);
                Binder binder = new Binder(engine);

                DeleteStatement deleteUnknownCol = new DeleteStatement("trips", Optional.of(
                                new Predicate("missing_col", Comparison.EQUALS, 1L)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(deleteUnknownCol));
        }

        @Test
        void deleteTypeMismatchedConstantInWhereThrowsIllegalArgumentException(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                engine.createTable("trips", TRIPS_SCHEMA);
                Binder binder = new Binder(engine);

                // Long column with String constant
                DeleteStatement distString = new DeleteStatement("trips", Optional.of(
                                new Predicate("distance", Comparison.EQUALS, "x")));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(distString));

                // Long column with Double constant
                DeleteStatement distDouble = new DeleteStatement("trips", Optional.of(
                                new Predicate("distance", Comparison.EQUALS, 100.5)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(distDouble));

                // String column with Long constant
                DeleteStatement cityLong = new DeleteStatement("trips", Optional.of(
                                new Predicate("city", Comparison.EQUALS, 100L)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(cityLong));

                // Double column with String constant
                DeleteStatement priceString = new DeleteStatement("trips", Optional.of(
                                new Predicate("price", Comparison.EQUALS, "50.0")));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(priceString));

                // Double column with Long constant
                DeleteStatement priceLong = new DeleteStatement("trips", Optional.of(
                                new Predicate("price", Comparison.EQUALS, 50L)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(priceLong));

                // String column with Double constant
                DeleteStatement cityDouble = new DeleteStatement("trips", Optional.of(
                                new Predicate("city", Comparison.EQUALS, 50.0)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(cityDouble));
        }

        @Test
        void unknownColumnInWhereThrowsIllegalArgumentException(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                engine.createTable("trips", TRIPS_SCHEMA);
                Binder binder = new Binder(engine);

                SelectStatement selectUnknownColumn = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("non_existent_column", Comparison.EQUALS, "test")));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(selectUnknownColumn));
        }

        @Test
        void typeMismatchedConstantInWhereThrowsIllegalArgumentException(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                engine.createTable("trips", TRIPS_SCHEMA);
                Binder binder = new Binder(engine);

                // Long column with String constant
                SelectStatement distMismatch = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("distance", Comparison.EQUALS, "x")));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(distMismatch));

                // String column with Long constant
                SelectStatement cityMismatch = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("city", Comparison.EQUALS, 100L)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(cityMismatch));

                // Double column with String constant
                SelectStatement priceMismatch = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("price", Comparison.EQUALS, "50.0")));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(priceMismatch));

                // Double column with Long constant
                SelectStatement priceLongMismatch = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("price", Comparison.EQUALS, 50L)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(priceLongMismatch));

                // Long column with Double constant
                SelectStatement distDoubleMismatch = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("distance", Comparison.EQUALS, 100.5)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(distDoubleMismatch));

                // String column with Double constant
                SelectStatement cityDoubleMismatch = new SelectStatement("trips", Optional.empty(), Optional.of(
                                new Predicate("city", Comparison.EQUALS, 50.0)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(cityDoubleMismatch));
        }

        @Test
        void duplicateColumnsInCreateTableThrowsIllegalArgumentException(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                Binder binder = new Binder(engine);

                CreateTableStatement duplicateCols = new CreateTableStatement("invalid_table", List.of(
                                new ColumnSpec("city", ColumnType.STRING),
                                new ColumnSpec("distance", ColumnType.LONG),
                                new ColumnSpec("city", ColumnType.LONG)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(duplicateCols));
        }

        @Test
        void emptyColumnsInCreateTableThrowsIllegalArgumentException(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                Binder binder = new Binder(engine);

                CreateTableStatement emptyCols = new CreateTableStatement("empty_table", List.of());
                assertThrows(IllegalArgumentException.class, () -> binder.bind(emptyCols));
        }

        @Test
        void bindFailureLogsBindFailedAtErrorLevel(@TempDir Path tempDir) throws IOException {
                StorageEngine engine = new StorageEngine(tempDir);
                engine.createTable("trips", TRIPS_SCHEMA);
                Binder binder = new Binder(engine);

                String session = UUID.randomUUID().toString();
                MDC.put("sessionId", session);
                try {
                        DeleteStatement invalidCol = new DeleteStatement("trips", Optional.of(
                                        new Predicate("missing_column", Comparison.EQUALS, 1L)));
                        assertThrows(IllegalArgumentException.class, () -> binder.bind(invalidCol));
                } finally {
                        MDC.remove("sessionId");
                }

                List<String> logLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
                boolean hasBindFailed = logLines.stream()
                                .filter(line -> line.contains("," + session + ",0,"))
                                .anyMatch(line -> line.contains(",ERROR,") && line.contains("bind_failed reason=unknown column: missing_column"));
                assertTrue(hasBindFailed, "expected ERROR line with statementNumber 0 and bind_failed message");
        }
}
