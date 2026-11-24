package datasys.semi.operators;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inner equi-join: builds a hash table from the build child, streams the probe
 * child.
 *
 * <p>
 * {@code open()} drains the build child into a map from join key to the build
 * rows carrying that key (a list, because keys repeat). {@code next()} pulls
 * probe rows and looks up their key; a probe row matching {@code k} build rows
 * yields {@code k} output rows, so a cursor into the current match list
 * survives between calls. Each output row is the build row's columns followed
 * by the probe row's columns. Rows without a partner are dropped, and a
 * {@code null} key never matches, as in SQL.
 *
 * <p>
 * Keys are compared with {@code equals}; the binder guarantees both key
 * columns have the same type. {@code close()} logs one line per join with the
 * planner's {@link JoinEstimate} next to the build, probe and output row
 * counts: {@code estRows} against {@code rowsOut} is the estimate error of the
 * intermediate result that join ordering optimizes. Not thread-safe, like
 * every operator.
 */
public final class HashJoinOperator implements Operator {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(HashJoinOperator.class);

    // --- Pipeline Wiring ---
    private final Operator build;
    private final Operator probe;
    private final int buildKey;
    private final int probeKey;

    // --- Plan-Time Estimate ---
    private final JoinEstimate estimate;

    // --- Hash Table ---
    private Map<Object, List<Object[]>> buildRowsByKey = Map.of();

    // --- Cursor State ---
    private boolean opened;
    private Object[] currentProbeRow;
    private List<Object[]> currentMatches = List.of();
    private int nextMatchIndex;

    // --- Observable Metrics ---
    private long rowsBuild;
    private long rowsProbe;
    private long rowsOut;

    /**
     * Constructs a hash join over a build and a probe child.
     *
     * @param build    operator whose rows fill the hash table
     * @param probe    operator whose rows are streamed and looked up
     * @param buildKey zero-based index of the join key in build rows; must not be
     *                 negative
     * @param probeKey zero-based index of the join key in probe rows; must not be
     *                 negative
     * @param estimate the planner's estimate for this join, logged on
     *                 {@code close()}
     * @throws IllegalArgumentException if a child or the estimate is null, or a
     *                                  key index is negative
     */
    public HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey, JoinEstimate estimate) {
        if (build == null || probe == null) {
            throw new IllegalArgumentException("build and probe operators must not be null");
        }
        if (estimate == null) {
            throw new IllegalArgumentException("join estimate must not be null");
        }
        if (buildKey < 0 || probeKey < 0) {
            throw new IllegalArgumentException("join key indices must not be negative: buildKey=" + buildKey
                    + " probeKey=" + probeKey);
        }

        this.build = build;
        this.probe = probe;
        this.buildKey = buildKey;
        this.probeKey = probeKey;
        this.estimate = estimate;
    }

    /**
     * Opens both children, drains the build child into the hash table, and
     * resets the counters and the cursor.
     *
     * @throws IllegalStateException if this join has already been opened, or a
     *                               build row has no column at the build key
     */
    @Override
    public void open() {
        if (opened) {
            throw new IllegalStateException("hash join operator is already open");
        }

        opened = true;
        rowsBuild = 0;
        rowsProbe = 0;
        rowsOut = 0;
        resetCursor();

        build.open();
        probe.open();
        buildRowsByKey = drainBuildSide();
    }

    /**
     * Emits the next joined row: the remaining matches of the current probe row
     * first, then the matches of later probe rows.
     *
     * @return build columns followed by probe columns, or {@code null} once the
     *         probe child is exhausted
     * @throws IllegalStateException if this join has not been opened, or a probe
     *                               row has no column at the probe key
     */
    @Override
    public Object[] next() {
        requireOpen();

        while (nextMatchIndex >= currentMatches.size()) {
            if (!advanceProbe()) {
                return null;
            }
        }

        Object[] buildRow = currentMatches.get(nextMatchIndex++);
        rowsOut++;
        return concatenate(buildRow, currentProbeRow);
    }

    /**
     * Logs the operator summary with the estimated and actual output rows,
     * releases the hash table, and closes both children.
     *
     * @throws IllegalStateException if this join has not been opened
     */
    @Override
    public void close() {
        requireOpen();

        LOGGER.debug("operator=HashJoin join={} tables={} estRows={} buildKey={} probeKey={} rowsBuild={} "
                + "rowsProbe={} rowsOut={}", estimate.joinNumber(), estimate.describeTables(),
                estimate.roundedRows(), buildKey, probeKey, rowsBuild, rowsProbe, rowsOut);

        buildRowsByKey = Map.of();
        resetCursor();
        build.close();
        probe.close();
    }

    /**
     * Pulls every build row into a map from join key to rows. Rows with a
     * {@code null} key can never match and are counted but not stored.
     *
     * @return map from key to build rows, each list in build order
     * @throws IllegalStateException if a build row has no column at the build key
     */
    private Map<Object, List<Object[]>> drainBuildSide() {
        Map<Object, List<Object[]>> rowsByKey = new HashMap<>();
        Object[] buildRow;
        while ((buildRow = build.next()) != null) {
            rowsBuild++;
            Object key = readKey(buildRow, buildKey, "build");
            if (key != null) {
                rowsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(buildRow);
            }
        }
        return rowsByKey;
    }

    /**
     * Pulls the next probe row and makes its matches the current match list.
     *
     * @return false once the probe child is exhausted
     * @throws IllegalStateException if the probe row has no column at the probe
     *                               key
     */
    private boolean advanceProbe() {
        Object[] probeRow = probe.next();
        if (probeRow == null) {
            resetCursor();
            return false;
        }

        rowsProbe++;
        Object key = readKey(probeRow, probeKey, "probe");
        currentProbeRow = probeRow;
        currentMatches = key == null ? List.of() : buildRowsByKey.getOrDefault(key, List.of());
        nextMatchIndex = 0;
        return true;
    }

    /**
     * Reads the join key of a row.
     *
     * @param row      the row
     * @param keyIndex index of the key column
     * @param side     {@code build} or {@code probe}, for the error message
     * @return the key value, possibly {@code null}
     * @throws IllegalStateException if the row is narrower than the key index
     */
    private static Object readKey(Object[] row, int keyIndex, String side) {
        if (keyIndex >= row.length) {
            throw new IllegalStateException(side + " row has " + row.length
                    + " columns but the join key index is " + keyIndex);
        }
        return row[keyIndex];
    }

    /**
     * Builds an output row from a build row and a probe row without mutating
     * either.
     *
     * @param buildRow matching build row
     * @param probeRow current probe row
     * @return new row: build columns followed by probe columns
     */
    private static Object[] concatenate(Object[] buildRow, Object[] probeRow) {
        Object[] joinedRow = new Object[buildRow.length + probeRow.length];
        System.arraycopy(buildRow, 0, joinedRow, 0, buildRow.length);
        System.arraycopy(probeRow, 0, joinedRow, buildRow.length, probeRow.length);
        return joinedRow;
    }

    /**
     * Clears the cursor so no match of a previous probe row is emitted again.
     */
    private void resetCursor() {
        currentProbeRow = null;
        currentMatches = List.of();
        nextMatchIndex = 0;
    }

    /**
     * Guards against use of this operator outside its open/close lifecycle.
     *
     * @throws IllegalStateException if this join has not been opened
     */
    private void requireOpen() {
        if (!opened) {
            throw new IllegalStateException("hash join operator has not been opened");
        }
    }

    /**
     * Returns the build child, whose rows fill the hash table.
     *
     * @return the build operator
     */
    public Operator build() {
        return build;
    }

    /**
     * Returns the probe child, whose rows are streamed against the hash table.
     *
     * @return the probe operator
     */
    public Operator probe() {
        return probe;
    }

    /**
     * Returns the join key index in build rows.
     *
     * @return zero-based build key index
     */
    public int buildKey() {
        return buildKey;
    }

    /**
     * Returns the join key index in probe rows.
     *
     * @return zero-based probe key index
     */
    public int probeKey() {
        return probeKey;
    }

    /**
     * Returns the planner's estimate for this join.
     *
     * @return the join estimate logged on {@code close()}
     */
    public JoinEstimate estimate() {
        return estimate;
    }

    /**
     * Returns the number of rows drained from the build child.
     *
     * @return build row count of the current or last traversal
     */
    public long rowsBuild() {
        return rowsBuild;
    }

    /**
     * Returns the number of rows pulled from the probe child.
     *
     * @return probe row count of the current or last traversal
     */
    public long rowsProbe() {
        return rowsProbe;
    }

    /**
     * Returns the number of joined rows emitted.
     *
     * @return output row count of the current or last traversal
     */
    public long rowsOut() {
        return rowsOut;
    }
}
