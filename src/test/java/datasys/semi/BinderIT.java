package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                SelectStatement selectNoWhere = new SelectStatement("trips", Optional.empty());
                assertDoesNotThrow(() -> binder.bind(selectNoWhere));

                // SELECT with valid WHERE conditions across all types binds
                SelectStatement selectCity = new SelectStatement("trips", Optional.of(
                                new Predicate("city", Comparison.EQUALS, "Copenhagen")));
                assertDoesNotThrow(() -> binder.bind(selectCity));

                SelectStatement selectDistance = new SelectStatement("trips", Optional.of(
                                new Predicate("distance", Comparison.GREATER_THAN, 100L)));
                assertDoesNotThrow(() -> binder.bind(selectDistance));

                SelectStatement selectPrice = new SelectStatement("trips", Optional.of(
                                new Predicate("price", Comparison.LESS_THAN, 50.0)));
                assertDoesNotThrow(() -> binder.bind(selectPrice));
        }

        @Test
        void unknownTableThrowsIllegalArgumentException(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                Binder binder = new Binder(engine);

                SelectStatement selectUnknownTable = new SelectStatement("non_existent", Optional.empty());
                assertThrows(IllegalArgumentException.class, () -> binder.bind(selectUnknownTable));

                CopyStatement copyUnknownTable = new CopyStatement("non_existent", "data.csv");
                assertThrows(IllegalArgumentException.class, () -> binder.bind(copyUnknownTable));
        }

        @Test
        void unknownColumnInWhereThrowsIllegalArgumentException(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                engine.createTable("trips", TRIPS_SCHEMA);
                Binder binder = new Binder(engine);

                SelectStatement selectUnknownColumn = new SelectStatement("trips", Optional.of(
                                new Predicate("non_existent_column", Comparison.EQUALS, "test")));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(selectUnknownColumn));
        }

        @Test
        void typeMismatchedConstantInWhereThrowsIllegalArgumentException(@TempDir Path tempDir) {
                StorageEngine engine = new StorageEngine(tempDir);
                engine.createTable("trips", TRIPS_SCHEMA);
                Binder binder = new Binder(engine);

                // Long column with String constant
                SelectStatement distMismatch = new SelectStatement("trips", Optional.of(
                                new Predicate("distance", Comparison.EQUALS, "x")));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(distMismatch));

                // String column with Long constant
                SelectStatement cityMismatch = new SelectStatement("trips", Optional.of(
                                new Predicate("city", Comparison.EQUALS, 100L)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(cityMismatch));

                // Double column with String constant
                SelectStatement priceMismatch = new SelectStatement("trips", Optional.of(
                                new Predicate("price", Comparison.EQUALS, "50.0")));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(priceMismatch));

                // Long column with Double constant
                SelectStatement distDoubleMismatch = new SelectStatement("trips", Optional.of(
                                new Predicate("distance", Comparison.EQUALS, 100.5)));
                assertThrows(IllegalArgumentException.class, () -> binder.bind(distDoubleMismatch));
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
}
