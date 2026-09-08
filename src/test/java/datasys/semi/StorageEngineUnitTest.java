package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import org.junit.jupiter.api.Test;

class StorageEngineUnitTest {
    private final StorageEngine engine = new StorageEngine(java.nio.file.Path.of("target/unit-storage"));

    @Test
    void valuesRoundTrip() {
        assertEquals("Odense", engine.decodeValue(ColumnType.STRING,
                engine.encodeValue(ColumnType.STRING, "Odense")));
        assertEquals(-42L, engine.decodeValue(ColumnType.LONG,
                engine.encodeValue(ColumnType.LONG, -42L)));
        assertEquals(12.5, engine.decodeValue(ColumnType.DOUBLE,
                engine.encodeValue(ColumnType.DOUBLE, 12.5)));
    }

    @Test
    void computesMinAndMax() {
        StorageEngine.MinMax statistics = engine.minMax(List.of(-4L, -10L, 3L));
        assertEquals(-10L, statistics.min());
        assertEquals(3L, statistics.max());
        StorageEngine.MinMax single = engine.minMax(List.of("only"));
        assertEquals("only", single.min());
        assertEquals("only", single.max());
    }

    @Test
    void makesCorrectPruningDecisions() {
        assertTrue(engine.shouldPrune(ColumnType.LONG, Comparison.EQUALS, 20L, 1L, 10L));
        assertFalse(engine.shouldPrune(ColumnType.LONG, Comparison.EQUALS, 10L, 1L, 10L));
        assertTrue(engine.shouldPrune(ColumnType.DOUBLE, Comparison.LESS_THAN, 1.0, 1.0, 4.0));
        assertFalse(engine.shouldPrune(ColumnType.DOUBLE, Comparison.LESS_THAN, 2.0, 1.0, 4.0));
        assertTrue(engine.shouldPrune(ColumnType.STRING, Comparison.GREATER_THAN, "Z", "A", "M"));
        assertFalse(engine.shouldPrune(ColumnType.STRING, Comparison.GREATER_THAN, "B", "A", "M"));
    }

    @Test
    void parsesTypedCsvAndReportsLineErrors() {
        List<StorageEngine.Column> schema = List.of(
                new StorageEngine.Column("city", ColumnType.STRING.name()),
                new StorageEngine.Column("distance", ColumnType.LONG.name()),
                new StorageEngine.Column("price", ColumnType.DOUBLE.name()));
        assertArrayEquals(new Object[] { "Odense", 12L, 2.5 },
                engine.parseCsvLine("Odense,12,2.5", "trips.csv", 3, schema));
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> engine.parseCsvLine("Odense,nope,2.5", "trips.csv", 4, schema));
        assertEquals(true, malformed.getMessage().contains("trips.csv: line 4"));
        IllegalArgumentException wrongCount = assertThrows(IllegalArgumentException.class,
                () -> engine.parseCsvLine("Odense,12", "trips.csv", 5, schema));
        assertEquals(true, wrongCount.getMessage().contains("trips.csv: line 5"));
    }

    private static void assertTrue(boolean value) {
        assertEquals(true, value);
    }

    private static void assertFalse(boolean value) {
        assertEquals(false, value);
    }
}