package datasys.semi.operators;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datasys.semi.engine.StorageEngine;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.engine.StorageEngine.Partition;

/**
 * Leaf operator that reads the partitions it is handed from a pinned
 * {@link Catalog} snapshot, in order.
 *
 * <p>
 * The scan never re-reads metadata from {@link StorageEngine}: it resolves
 * partition entries directly from the immutable {@link Catalog} snapshot handed
 * to it at plan time. Rows are buffered one partition at a time rather than all
 * at once, so the pipeline never holds more than a single partition in memory.
 */
public final class ScanOperator implements Operator {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanOperator.class);

    // --- Scan Target ---
    private final StorageEngine engine;
    private final Catalog snapshot;
    private final List<Integer> partitionNumbers;

    // --- Cursor State ---
    private boolean opened;
    private int nextPartitionIndex;
    private Iterator<Object[]> rows = Collections.emptyIterator();

    // --- Observable Metrics ---
    private int partitionsOpened;
    private long rowsOut;

    /**
     * Constructs a scan over an explicit list of partition indices within a pinned
     * catalog snapshot.
     *
     * @param engine           storage engine owning the table's data files
     * @param snapshot         pinned catalog snapshot of the table being scanned
     * @param partitionNumbers zero-based partition indices in
     *                         {@code snapshot.partitions()} to read,
     *                         in read order; may be empty but must not be null
     * @throws IllegalArgumentException if {@code engine}, {@code snapshot}, or
     *                                  {@code partitionNumbers} is null
     */
    public ScanOperator(StorageEngine engine, Catalog snapshot, List<Integer> partitionNumbers) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (partitionNumbers == null) {
            throw new IllegalArgumentException("partition number list must not be null");
        }

        this.engine = engine;
        this.snapshot = snapshot;
        this.partitionNumbers = List.copyOf(partitionNumbers);
    }

    /**
     * Positions the cursor before the first row of the first partition.
     *
     * @throws IllegalStateException if this scan has already been opened
     */
    @Override
    public void open() {
        if (opened) {
            throw new IllegalStateException("scan operator is already open");
        }

        opened = true;
        nextPartitionIndex = 0;
        rows = Collections.emptyIterator();

        LOGGER.debug("operator=Scan table={} version={} partitions={}",
                snapshot.table(), snapshot.version(), partitionNumbers.size());
    }

    /**
     * Returns the next row of the handed partitions, reading the following
     * partition once the current buffer is drained.
     *
     * @return the next row in schema column order, or null once every handed
     *         partition has been fully returned
     * @throws IllegalStateException if this scan has not been opened
     */
    @Override
    public Object[] next() {
        requireOpen();

        while (!rows.hasNext()) {
            if (nextPartitionIndex >= partitionNumbers.size()) {
                return null;
            }
            bufferNextPartition();
        }

        rowsOut++;
        return rows.next();
    }

    /**
     * Releases the buffered partition and records how much this scan read.
     *
     * @throws IllegalStateException if this scan has not been opened
     */
    @Override
    public void close() {
        requireOpen();

        rows = Collections.emptyIterator();

        LOGGER.debug("operator=Scan table={} partitionsRead={} rowsOut={}",
                snapshot.table(), partitionsOpened, rowsOut);
    }

    /**
     * Reads the next partition from the pinned snapshot into the row buffer and
     * resets the row cursor.
     */
    private void bufferNextPartition() {
        int partitionNumber = partitionNumbers.get(nextPartitionIndex++);
        Partition partition = snapshot.partitions().get(partitionNumber);

        rows = engine.readPartition(snapshot, partition).iterator();
        partitionsOpened++;
    }

    /**
     * Guards against use of this operator outside its open/close lifecycle.
     *
     * @throws IllegalStateException if this scan has not been opened
     */
    private void requireOpen() {
        if (!opened) {
            throw new IllegalStateException("scan operator has not been opened");
        }
    }

    /**
     * Returns the partition numbers assigned to this scan operator.
     *
     * @return unmodifiable list of partition numbers
     */
    public List<Integer> partitionNumbers() {
        return partitionNumbers;
    }

    /**
     * Returns the name of the table being scanned.
     *
     * @return table name
     */
    public String tableName() {
        return snapshot.table();
    }
}
