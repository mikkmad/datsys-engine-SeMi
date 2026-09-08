package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageEngineIT {
    @Test
    void copiesPartitionsAndReadsThemAfterRestart(@TempDir Path directory) throws Exception {
        Path csv = directory.resolve("trips.csv");
        Files.writeString(csv, "Aalborg,1,1.5\nOdense,10,10.5\nEsbjerg,20,20.5\nCopenhagen,30,30.5\n");

        StorageEngine first = new StorageEngine(directory, 2);
        first.createTable("trips", List.of(new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE)));
        first.copyFile("trips", csv.toString());

        StorageEngine restarted = new StorageEngine(directory, 2);
        List<Object[]> rows = restarted.select("trips", "distance", Comparison.GREATER_THAN, 9L);

        assertEquals(3, rows.size());
        assertEquals("Odense", rows.get(0)[0]);
        assertEquals(20L, rows.get(1)[1]);
        assertEquals(30.5, rows.get(2)[2]);
        assertEquals(new ScanStats(2, 2, 0), restarted.getLastScanStats());
        restarted.select("trips", "distance", Comparison.GREATER_THAN, 25L);
        assertEquals(new ScanStats(2, 1, 1), restarted.getLastScanStats());
        assertTrue(Files.exists(directory.resolve("catalogs/trips.json")));
    }

    @Test
    void supportsAllComparisonsAndRejectsInvalidRequests(@TempDir Path directory) throws Exception {
        Path csv = directory.resolve("values.csv");
        Files.writeString(csv, "A,1,1.5\nB,2,2.5\nC,3,3.5\n");
        StorageEngine engine = new StorageEngine(directory, 2);
        engine.createTable("values", List.of(new ColumnSpec("text", ColumnType.STRING),
                new ColumnSpec("number", ColumnType.LONG), new ColumnSpec("decimal", ColumnType.DOUBLE)));
        engine.copyFile("values", csv.toString());

        assertEquals(1, engine.select("values", "text", Comparison.EQUALS, "B").size());
        assertEquals(2, engine.select("values", "text", Comparison.LESS_THAN, "C").size());
        assertEquals(1, engine.select("values", "text", Comparison.GREATER_THAN, "B").size());
        assertEquals(1, engine.select("values", "number", Comparison.EQUALS, 2L).size());
        assertEquals(2, engine.select("values", "number", Comparison.LESS_THAN, 3L).size());
        assertEquals(1, engine.select("values", "number", Comparison.GREATER_THAN, 2L).size());
        assertEquals(1, engine.select("values", "decimal", Comparison.EQUALS, 2.5).size());
        assertEquals(2, engine.select("values", "decimal", Comparison.LESS_THAN, 3.5).size());
        assertEquals(1, engine.select("values", "decimal", Comparison.GREATER_THAN, 2.5).size());
        assertThrows(IllegalArgumentException.class, () -> engine.select("missing", "number", Comparison.EQUALS, 1L));
        assertThrows(IllegalArgumentException.class, () -> engine.select("values", "missing", Comparison.EQUALS, 1L));
        assertThrows(IllegalArgumentException.class, () -> engine.select("values", "number", Comparison.EQUALS, 1));
        assertThrows(IllegalArgumentException.class, () -> engine.createTable("values", List.of(
                new ColumnSpec("other", ColumnType.STRING))));
    }
}