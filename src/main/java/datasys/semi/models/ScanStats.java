package datasys.semi.models;

/**
 * Immutable summary of partition pruning and catalog version for a planned
 * {@code SELECT} query.
 *
 * @param partitionsTotal  total number of partitions in the pinned catalog
 *                         snapshot
 * @param partitionsRead   number of partitions retained for reading after
 *                         pruning
 * @param partitionsPruned number of partitions skipped by min/max pruning
 * @param version          catalog version read by the planned query
 */
public record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned, long version) {
}
