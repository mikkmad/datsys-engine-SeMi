package datasys.semi.optimizer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datasys.semi.util.LogSanitizer;

/**
 * Chooses the left-deep join order of a multi-table {@code SELECT} from
 * estimated table sizes and join-column NDVs.
 *
 * <p>
 * Two orders are supported:
 * <ul>
 * <li>{@link #orderBySyntax}: week 10's plan. Tables join in {@code FROM}-list
 * order and the tables joined so far always build.</li>
 * <li>{@link #orderGreedily}: the course baseline, greedy
 * smallest-intermediate-first. Start with the join predicate whose estimated
 * output is smallest, then repeatedly add the connected table whose estimated
 * join output is smallest. A table that is not connected to the tables joined
 * so far is never a candidate, so no cross product is ever picked. The input
 * with the smaller estimate builds.</li>
 * </ul>
 * Both orders estimate every join with
 * {@link CardinalityEstimator#equiJoinRows}, using the base table NDV of each
 * join column for intermediate results too. Every combination the greedy order
 * checks and every choice is logged at {@code debug}; table lists are joined
 * with {@code +} so the CSV log stays parseable.
 *
 * <p>
 * Limitations, shared with week 10: cross products are not supported, and a
 * table connected to the tables joined so far by more than one predicate is
 * rejected, because the hash join applies one predicate and the second one would
 * be dropped silently.
 *
 * <p>
 * Stateless and thread-safe; reads no data files.
 */
public final class JoinOrderer {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(JoinOrderer.class);

    /**
     * Prevents instantiation of this utility class.
     */
    private JoinOrderer() {
    }

    /**
     * Orders the joins as written: left-deep in {@code FROM}-list order, the
     * tables joined so far building and each new table probing. This reproduces
     * week 10's plan exactly; the estimates are added so they can be logged.
     *
     * @param tables estimated tables in {@code FROM}-list order, not empty
     * @param edges  join predicates in {@code WHERE} order
     * @return the {@code FROM}-list order, starting at position 0
     * @throws IllegalArgumentException if an argument is invalid, a table is not
     *                                  connected to the tables listed before it,
     *                                  or a table is connected to them by more
     *                                  than one predicate
     */
    public static JoinOrder orderBySyntax(List<TableEstimate> tables, List<JoinEdge> edges) {
        requireValidInput(tables, edges);
        List<Integer> joined = new ArrayList<>(List.of(0));
        double joinedRows = tables.getFirst().estimatedRows();
        List<JoinStep> steps = new ArrayList<>();
        for (int position = 1; position < tables.size(); position++) {
            JoinEdge edge = findSyntaxEdge(tables, edges, joined, position);
            JoinStep estimated = estimateStep(tables, joinedRows, position, edge);
            JoinStep step = new JoinStep(position, edge, false, estimated.estimatedRows());
            logChoice(JoinOrderMode.SYNTAX, steps.size() + 1, tables, joined, step);
            steps.add(step);
            joined.add(position);
            joinedRows = step.estimatedRows();
        }
        return new JoinOrder(0, steps);
    }

    /**
     * Orders the joins greedily by smallest estimated intermediate result.
     *
     * @param tables estimated tables in {@code FROM}-list order, not empty
     * @param edges  join predicates in {@code WHERE} order; ties between equal
     *               estimates go to the earlier predicate in step 1 and to the
     *               earlier {@code FROM} table afterwards
     * @return the greedy order; a single table yields an order without steps
     * @throws IllegalArgumentException if an argument is invalid, the tables are
     *                                  not all connected by join predicates
     *                                  (a cross product would be needed), or a
     *                                  table is connected to the tables joined so
     *                                  far by more than one predicate
     */
    public static JoinOrder orderGreedily(List<TableEstimate> tables, List<JoinEdge> edges) {
        requireValidInput(tables, edges);
        if (tables.size() == 1) {
            return new JoinOrder(0, List.of());
        }

        JoinStep firstStep = chooseFirstJoin(tables, edges);
        int firstTable = firstStep.edge().otherSideOf(firstStep.newTablePosition()).tablePosition();
        List<Integer> joined = new ArrayList<>(List.of(firstTable, firstStep.newTablePosition()));
        List<JoinStep> steps = new ArrayList<>(List.of(firstStep));
        while (joined.size() < tables.size()) {
            JoinStep nextStep = chooseNextJoin(tables, edges, joined, steps.getLast().estimatedRows(),
                    steps.size() + 1);
            steps.add(nextStep);
            joined.add(nextStep.newTablePosition());
        }
        return new JoinOrder(firstTable, steps);
    }

    /**
     * Greedy step 1: estimates every join predicate and picks the one with the
     * smallest output. The smaller of its two tables becomes the first table and
     * builds; ties go to the earlier {@code FROM} table.
     *
     * @param tables estimated tables in {@code FROM}-list order, at least two
     * @param edges  join predicates in {@code WHERE} order
     * @return the first join, whose new table is the probe side
     * @throws IllegalArgumentException if there is no join predicate, or the two
     *                                  chosen tables are joined by more than one
     */
    private static JoinStep chooseFirstJoin(List<TableEstimate> tables, List<JoinEdge> edges) {
        if (edges.isEmpty()) {
            throw new IllegalArgumentException("no join predicate connects the FROM tables ("
                    + describeTables(tables, allPositions(tables)) + "); cross products are not supported");
        }
        JoinStep best = null;
        for (JoinEdge edge : edges) {
            int leftTable = edge.left().tablePosition();
            double leftRows = tables.get(leftTable).estimatedRows();
            JoinStep candidate = estimateStep(tables, leftRows, edge.right().tablePosition(), edge);
            logCandidate(1, tables, List.of(leftTable), leftRows, candidate);
            if (best == null || candidate.estimatedRows() < best.estimatedRows()) {
                best = candidate;
            }
        }
        return orientFirstJoin(tables, edges, best);
    }

    /**
     * Decides the build side of the first join: the smaller table builds and
     * becomes the first table; the other is the step's new table and probes.
     *
     * @param tables estimated tables in {@code FROM}-list order
     * @param edges  all join predicates, to reject a second predicate between the
     *               same two tables
     * @param chosen the chosen first join, in predicate orientation
     * @return the first join with the larger table as new table
     * @throws IllegalArgumentException if the two tables are joined by more than
     *                                  one predicate
     */
    private static JoinStep orientFirstJoin(List<TableEstimate> tables, List<JoinEdge> edges, JoinStep chosen) {
        int leftTable = chosen.edge().left().tablePosition();
        int rightTable = chosen.edge().right().tablePosition();
        double leftRows = tables.get(leftTable).estimatedRows();
        double rightRows = tables.get(rightTable).estimatedRows();
        boolean rightBuilds = rightRows < leftRows || (rightRows == leftRows && rightTable < leftTable);
        int buildTable = rightBuilds ? rightTable : leftTable;
        int probeTable = rightBuilds ? leftTable : rightTable;

        requireSingleEdge(tables, probeTable, connectingEdges(edges, List.of(buildTable), probeTable));
        JoinStep step = new JoinStep(probeTable, chosen.edge(), false, chosen.estimatedRows());
        logChoice(JoinOrderMode.OPTIMIZED, 1, tables, List.of(buildTable), step);
        return step;
    }

    /**
     * Greedy step k: among the tables connected to the tables joined so far,
     * picks the one whose join has the smallest estimated output.
     *
     * @param tables     estimated tables in {@code FROM}-list order
     * @param edges      join predicates in {@code WHERE} order
     * @param joined     positions of the tables joined so far, in join order
     * @param joinedRows estimated rows of the intermediate result so far
     * @param stepNumber one-based number of this join, for the log
     * @return the cheapest connected join
     * @throws IllegalArgumentException if no remaining table is connected, or a
     *                                  connected table has more than one
     *                                  connecting predicate
     */
    private static JoinStep chooseNextJoin(List<TableEstimate> tables, List<JoinEdge> edges, List<Integer> joined,
            double joinedRows, int stepNumber) {
        JoinStep best = null;
        for (int candidateTable = 0; candidateTable < tables.size(); candidateTable++) {
            List<JoinEdge> connecting = joined.contains(candidateTable)
                    ? List.of()
                    : connectingEdges(edges, joined, candidateTable);
            if (connecting.isEmpty()) {
                continue;
            }
            JoinEdge edge = requireSingleEdge(tables, candidateTable, connecting);
            JoinStep candidate = estimateStep(tables, joinedRows, candidateTable, edge);
            logCandidate(stepNumber, tables, joined, joinedRows, candidate);
            if (best == null || candidate.estimatedRows() < best.estimatedRows()) {
                best = candidate;
            }
        }
        if (best == null) {
            throw notConnectedToJoinedTables(tables, joined);
        }
        logChoice(JoinOrderMode.OPTIMIZED, stepNumber, tables, joined, best);
        return best;
    }

    /**
     * Estimates the join of the tables joined so far with one new table. The new
     * table builds only if its estimate is strictly smaller.
     *
     * @param tables     estimated tables in {@code FROM}-list order
     * @param joinedRows estimated rows of the tables joined so far
     * @param newTable   position of the table to add
     * @param edge       the predicate connecting it to the tables joined so far
     * @return the estimated step
     * @throws IllegalArgumentException if the edge does not touch the new table
     */
    private static JoinStep estimateStep(List<TableEstimate> tables, double joinedRows, int newTable,
            JoinEdge edge) {
        double newTableRows = tables.get(newTable).estimatedRows();
        double estimatedRows = CardinalityEstimator.equiJoinRows(joinedRows, newTableRows,
                edge.otherSideOf(newTable).ndv(), edge.sideOf(newTable).ndv());
        return new JoinStep(newTable, edge, newTableRows < joinedRows, estimatedRows);
    }

    /**
     * Finds week 10's connecting predicate for a table: the one predicate between
     * it and the tables listed before it.
     *
     * @param tables   estimated tables in {@code FROM}-list order
     * @param edges    join predicates in {@code WHERE} order
     * @param joined   positions {@code 0..newTable-1}
     * @param newTable position of the table to add
     * @return the connecting predicate
     * @throws IllegalArgumentException if no predicate, or more than one,
     *                                  connects the table to the earlier ones
     */
    private static JoinEdge findSyntaxEdge(List<TableEstimate> tables, List<JoinEdge> edges, List<Integer> joined,
            int newTable) {
        List<JoinEdge> connecting = connectingEdges(edges, joined, newTable);
        if (connecting.isEmpty()) {
            String earlierTables = joined.stream()
                    .map(position -> tables.get(position).tableName())
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("table " + tables.get(newTable).tableName()
                    + " is not connected by a join predicate to the tables before it (" + earlierTables
                    + "); reorder the FROM list so that each table joins a table listed before it");
        }
        return requireSingleEdge(tables, newTable, connecting);
    }

    /**
     * Collects the predicates between one table and a set of joined tables.
     *
     * @param edges    join predicates in {@code WHERE} order
     * @param joined   positions of the tables joined so far
     * @param newTable position of a table outside {@code joined}
     * @return the connecting predicates in {@code WHERE} order
     */
    private static List<JoinEdge> connectingEdges(List<JoinEdge> edges, List<Integer> joined, int newTable) {
        return edges.stream()
                .filter(edge -> edge.touches(newTable))
                .filter(edge -> joined.contains(edge.otherSideOf(newTable).tablePosition()))
                .toList();
    }

    /**
     * Returns the only connecting predicate of a table, rejecting a second one,
     * which the plan could not apply.
     *
     * @param tables     estimated tables in {@code FROM}-list order
     * @param newTable   position of the table being connected
     * @param connecting its connecting predicates, not empty
     * @return the single connecting predicate
     * @throws IllegalArgumentException if there is more than one
     */
    private static JoinEdge requireSingleEdge(List<TableEstimate> tables, int newTable, List<JoinEdge> connecting) {
        if (connecting.size() > 1) {
            String predicates = connecting.stream().map(JoinEdge::describe).collect(Collectors.joining(" and "));
            throw new IllegalArgumentException("table " + tables.get(newTable).tableName() + " is connected by "
                    + connecting.size() + " join predicates (" + predicates
                    + "); only one join predicate per table is supported");
        }
        return connecting.getFirst();
    }

    /**
     * Builds the error for tables that only a cross product could add.
     *
     * @param tables estimated tables in {@code FROM}-list order
     * @param joined positions of the tables joined so far
     * @return exception naming the unconnected and the joined tables
     */
    private static IllegalArgumentException notConnectedToJoinedTables(List<TableEstimate> tables,
            List<Integer> joined) {
        List<Integer> remaining = allPositions(tables).stream()
                .filter(position -> !joined.contains(position))
                .toList();
        return new IllegalArgumentException("tables " + describeTables(tables, remaining)
                + " are not connected by a join predicate to " + describeTables(tables, joined)
                + "; cross products are not supported");
    }

    /**
     * Logs one combination checked by the greedy order.
     *
     * @param stepNumber one-based join number
     * @param tables     estimated tables in {@code FROM}-list order
     * @param joined     positions of the tables on the joined side
     * @param joinedRows estimated rows of the joined side
     * @param candidate  the estimated join with one new table
     */
    private static void logCandidate(int stepNumber, List<TableEstimate> tables, List<Integer> joined,
            double joinedRows, JoinStep candidate) {
        int newTable = candidate.newTablePosition();
        JoinEdge edge = candidate.edge();
        LOGGER.debug("mode={} step={} candidate={} on={}={} inputRows={}/{} ndv={}/{} estRows={}",
                JoinOrderMode.OPTIMIZED.flagValue(), stepNumber, describeJoinedTables(tables, joined, newTable),
                edge.left().columnName(), edge.right().columnName(),
                Math.round(joinedRows), Math.round(tables.get(newTable).estimatedRows()),
                edge.otherSideOf(newTable).ndv(), edge.sideOf(newTable).ndv(),
                Math.round(candidate.estimatedRows()));
    }

    /**
     * Logs the join chosen at one step, with its build and probe sides.
     *
     * @param mode       the ordering mode
     * @param stepNumber one-based join number
     * @param tables     estimated tables in {@code FROM}-list order
     * @param joined     positions of the tables joined before this step
     * @param step       the chosen join
     */
    private static void logChoice(JoinOrderMode mode, int stepNumber, List<TableEstimate> tables,
            List<Integer> joined, JoinStep step) {
        String joinedTables = describeTables(tables, joined);
        String newTable = tables.get(step.newTablePosition()).tableName();
        LOGGER.debug("mode={} step={} chosen={} estRows={} build={} probe={}", mode.flagValue(), stepNumber,
                describeJoinedTables(tables, joined, step.newTablePosition()), Math.round(step.estimatedRows()),
                step.newTableBuilds() ? newTable : joinedTables, step.newTableBuilds() ? joinedTables : newTable);
    }

    /**
     * Formats the tables of a join result for the log.
     *
     * @param tables   estimated tables in {@code FROM}-list order
     * @param joined   positions of the tables joined so far
     * @param newTable position of the table added
     * @return table names in join order separated by {@code +}
     */
    private static String describeJoinedTables(List<TableEstimate> tables, List<Integer> joined, int newTable) {
        List<Integer> positions = new ArrayList<>(joined);
        positions.add(newTable);
        return describeTables(tables, positions);
    }

    /**
     * Formats table positions as names for the log and for messages.
     *
     * @param tables    estimated tables in {@code FROM}-list order
     * @param positions table positions in the wanted order
     * @return table names separated by {@code +}
     */
    private static String describeTables(List<TableEstimate> tables, List<Integer> positions) {
        return LogSanitizer.joinList(positions.stream()
                .map(position -> tables.get(position).tableName())
                .toList());
    }

    /**
     * Lists every table position.
     *
     * @param tables estimated tables in {@code FROM}-list order
     * @return {@code 0..tables.size()-1}
     */
    private static List<Integer> allPositions(List<TableEstimate> tables) {
        List<Integer> positions = new ArrayList<>();
        for (int position = 0; position < tables.size(); position++) {
            positions.add(position);
        }
        return positions;
    }

    /**
     * Validates the orderer's input.
     *
     * @param tables estimated tables in {@code FROM}-list order
     * @param edges  join predicates
     * @throws IllegalArgumentException if a list is null or contains null, there
     *                                  is no table, or an edge refers to a table
     *                                  position outside the list
     */
    private static void requireValidInput(List<TableEstimate> tables, List<JoinEdge> edges) {
        if (tables == null || tables.isEmpty() || tables.stream().anyMatch(table -> table == null)) {
            throw new IllegalArgumentException("tables must not be null, empty or contain null");
        }
        if (edges == null || edges.stream().anyMatch(edge -> edge == null)) {
            throw new IllegalArgumentException("join edges must not be null or contain null");
        }
        for (JoinEdge edge : edges) {
            if (edge.laterTablePosition() >= tables.size()) {
                throw new IllegalArgumentException("join predicate " + edge.describe()
                        + " refers to a table outside the FROM list");
            }
        }
    }
}
