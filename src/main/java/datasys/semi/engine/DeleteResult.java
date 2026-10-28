package datasys.semi.engine;

/**
 * Immutable summary of a committed copy-on-write {@code DELETE} operation.
 *
 * <p>
 * Thread-safe value object returned by {@link StorageEngine#deleteRows} so the
 * executor can log how many rows were removed, how many partitions were
 * rewritten or carried over by reference, and the committed table version.
 *
 * @param rowsDeleted         number of rows removed by this {@code DELETE}
 * @param partitionsRewritten number of partitions replaced by a new partition
 *                            file or dropped because every row matched
 * @param partitionsCarried   number of partitions carried over unchanged into
 *                            the new version
 * @param version             committed catalog version produced by this
 *                            {@code DELETE}
 */
public record DeleteResult(long rowsDeleted, int partitionsRewritten, int partitionsCarried, long version) {
}
