package datasys.semi.operators;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datasys.semi.engine.StorageEngine;

/**
 * Leaf operator that reads the partitions it is handed, in order.
 *
 * <p>
 * The scan is deliberately dumb: it never sees the query's predicate and makes
 * no decision about which partitions are worth reading. That decision needs
 * only the min/max summaries in the catalog, never the column data, so it
 * belongs to the planner and has already been made by the time this operator
 * runs. Handed an empty partition list — a fully pruned query — the scan opens
 * no data file and returns no rows.
 *
 * <p>
 * Rows are buffered one partition at a time rather than all at once, so the
 * pipeline never holds more than a single partition in memory.
 */
public final class ScanOperator implements Operator {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanOperator.class);

    // --- Scan Target ---
    private final StorageEngine engine;
    private final String tableName;
    private final List<StorageEngine.Partition> partitions;

    // --- Cursor State ---
    private boolean opened;
    private int nextPartitionIndex;
    private List<Object[]> bufferedRows = List.of();
    private int nextRowIndex;

    // --- Observable Metrics ---
    private int partitionsOpened;
    private long rowsOut;

    /**
     * Constructs a scan over an explicit list of partitions.
     *
     * @param engine     storage engine owning the table's data files
     * @param tableName  name of the table being scanned
     * @param partitions the partitions to read, in read order; may be empty but
     *                   must not be null
     * @throws IllegalArgumentException if engine or partitions is null, or
     *                                  tableName is null or blank
     */
    public ScanOperator(StorageEngine engine, String tableName, List<StorageEngine.Partition> partitions) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table name must not be null or blank");
        }
        if (partitions == null) {
            throw new IllegalArgumentException("partition list must not be null");
        }

        this.engine = engine;
        this.tableName = tableName;
        this.partitions = List.copyOf(partitions);
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
        bufferedRows = List.of();
        nextRowIndex = 0;

        LOGGER.debug("operator=Scan table={} partitions={}", tableName, partitions.size());
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

        while (nextRowIndex >= bufferedRows.size()) {
            if (nextPartitionIndex >= partitions.size()) {
                return null;
            }
            bufferNextPartition();
        }

        rowsOut++;
        return bufferedRows.get(nextRowIndex++);
    }

    /**
     * Releases the buffered partition and records how much this scan read.
     *
     * @throws IllegalStateException if this scan has not been opened
     */
    @Override
    public void close() {
        requireOpen();

        bufferedRows = List.of();
        nextRowIndex = 0;

        LOGGER.debug("operator=Scan table={} partitionsRead={} rowsOut={}",
                tableName, partitionsOpened, rowsOut);
    }

    /**
     * Reads the next partition into the row buffer and resets the row cursor.
     */
    private void bufferNextPartition() {
        StorageEngine.Partition partition = partitions.get(nextPartitionIndex++);

        bufferedRows = engine.readPartition(tableName, partition);
        nextRowIndex = 0;
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
}
