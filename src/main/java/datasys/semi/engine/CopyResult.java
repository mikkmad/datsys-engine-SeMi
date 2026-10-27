package datasys.semi.engine;

/**
 * Immutable summary of a committed {@code COPY} operation.
 *
 * <p>
 * Thread-safe value object returned by {@link StorageEngine#copyFile} so the
 * executor can log the number of rows loaded, partitions written, and the
 * committed table version.
 *
 * @param rows       number of rows loaded by this {@code COPY} statement
 * @param partitions number of partition files written by this {@code COPY}
 *                   statement
 * @param version    committed catalog version produced by this {@code COPY}
 *                   statement
 */
public record CopyResult(long rows, int partitions, long version) {
}

