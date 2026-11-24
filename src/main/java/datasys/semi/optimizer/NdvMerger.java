package datasys.semi.optimizer;

import java.util.List;

/**
 * Merges per-partition NDV (number of distinct values) counts into one table-level
 * estimate for the join optimizer.
 *
 * <p>The rule, documented in {@code docs/optimizer-design.md}, is the sum of the
 * partition NDVs. It is exact when the partitions share no values and an upper
 * bound otherwise. The result is capped at the table's row count; because every
 * partition has at most as many distinct values as rows, the cap only applies to
 * inconsistent input and is kept as a safety check.
 *
 * <p>Stateless and thread-safe; all methods are pure functions.
 */
public final class NdvMerger {

    /**
     * Prevents instantiation of this utility class.
     */
    private NdvMerger() {
    }

    /**
     * Merges per-partition NDVs of one column into a table-level NDV.
     *
     * @param partitionNdvs NDV of the column in each partition, each non-negative;
     *                      missing partition NDVs must already be replaced by the
     *                      partition's row count
     * @param tableRowCount total number of rows in the table, non-negative
     * @return {@code min(sum of partitionNdvs, tableRowCount)}; 0 for a table
     *         without partitions
     * @throws IllegalArgumentException if {@code partitionNdvs} is null or contains
     *                                  a null or negative value, or if
     *                                  {@code tableRowCount} is negative
     */
    public static long tableNdv(List<Long> partitionNdvs, long tableRowCount) {
        if (partitionNdvs == null) {
            throw new IllegalArgumentException("partitionNdvs must not be null");
        }
        if (tableRowCount < 0) {
            throw new IllegalArgumentException("tableRowCount must not be negative: " + tableRowCount);
        }

        long ndvSum = 0;
        for (Long partitionNdv : partitionNdvs) {
            ndvSum += requireValidNdv(partitionNdv);
        }
        return Math.min(ndvSum, tableRowCount);
    }

    /**
     * Checks that one partition NDV is present and non-negative.
     *
     * @param partitionNdv NDV of one partition
     * @return the same value, unboxed
     * @throws IllegalArgumentException if {@code partitionNdv} is null or negative
     */
    private static long requireValidNdv(Long partitionNdv) {
        if (partitionNdv == null || partitionNdv < 0) {
            throw new IllegalArgumentException("partition NDV must be non-negative: " + partitionNdv);
        }
        return partitionNdv;
    }
}
