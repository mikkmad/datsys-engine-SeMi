package datasys.semi.optimizer;

import java.util.ArrayList;
import java.util.List;

/**
 * A left-deep join order: the first table, then one {@link JoinStep} per
 * further table, in execution order.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * @param firstTablePosition {@code FROM}-list position of the table the plan
 *                           starts from, non-negative
 * @param steps              joins in execution order; empty for a single
 *                           table
 */
public record JoinOrder(int firstTablePosition, List<JoinStep> steps) {

    /**
     * Validates and copies the steps.
     *
     * @throws IllegalArgumentException if the first position is negative or the
     *                                  steps are null or contain null
     */
    public JoinOrder {
        if (firstTablePosition < 0) {
            throw new IllegalArgumentException("first table position must not be negative: " + firstTablePosition);
        }
        if (steps == null || steps.stream().anyMatch(step -> step == null)) {
            throw new IllegalArgumentException("join steps must not be null or contain null");
        }
        steps = List.copyOf(steps);
    }

    /**
     * Returns the tables in the order they are joined.
     *
     * @return {@code FROM}-list positions, the first table followed by each
     *         step's new table
     */
    public List<Integer> tableOrder() {
        List<Integer> order = new ArrayList<>();
        order.add(firstTablePosition);
        steps.forEach(step -> order.add(step.newTablePosition()));
        return List.copyOf(order);
    }
}
