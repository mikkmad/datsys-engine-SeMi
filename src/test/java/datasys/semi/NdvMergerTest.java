package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import datasys.semi.optimizer.NdvMerger;

/**
 * Tests the table-level NDV merge rule from {@code docs/optimizer-design.md}: the
 * sum of the partition NDVs, capped at the table's row count (Exercise 11 §2,
 * required test §8.1).
 */
class NdvMergerTest {

    /**
     * Verifies that disjoint partitions add up exactly, which is the case the sum
     * rule is exact for.
     */
    @Test
    void tableNdvIsSumOfPartitionNdvs() {
        assertEquals(12L, NdvMerger.tableNdv(List.of(3L, 4L, 5L), 30L));
    }

    /**
     * Verifies that partitions sharing values still sum, giving an upper bound:
     * two partitions with the same 3 values merge to 6, not 3.
     */
    @Test
    void sharedValuesAcrossPartitionsGiveUpperBound() {
        assertEquals(6L, NdvMerger.tableNdv(List.of(3L, 3L), 20L));
    }

    /**
     * Verifies that a sum above the table's row count is capped at the row count.
     */
    @Test
    void sumAboveRowCountIsCappedAtRowCount() {
        assertEquals(5L, NdvMerger.tableNdv(List.of(4L, 4L), 5L));
    }

    /**
     * Verifies that a table without partitions has NDV 0.
     */
    @Test
    void tableWithoutPartitionsHasNdvZero() {
        assertEquals(0L, NdvMerger.tableNdv(List.of(), 0L));
    }

    /**
     * Verifies that a null list, a null partition NDV, a negative partition NDV and
     * a negative row count throw IllegalArgumentException.
     */
    @Test
    void invalidInputThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> NdvMerger.tableNdv(null, 10L));
        assertThrows(IllegalArgumentException.class, () -> NdvMerger.tableNdv(Arrays.asList(1L, null), 10L));
        assertThrows(IllegalArgumentException.class, () -> NdvMerger.tableNdv(List.of(-1L), 10L));
        assertThrows(IllegalArgumentException.class, () -> NdvMerger.tableNdv(List.of(1L), -1L));
    }
}
