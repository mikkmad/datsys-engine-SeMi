package datasys.semi.planner;

import java.util.List;
import java.util.function.Predicate;

/**
 * Immutable execution plan for a copy-on-write {@code DELETE} statement.
 *
 * <p>
 * Produced by {@link Planner#planDelete} from the same min/max pruning helper
 * that SELECT uses, in the opposite role: partitions in the rewrite set may
 * contain matching rows and are rewritten, all others are carried over by
 * reference. Instances are safe to share between threads.
 *
 * @param rewriteSet zero-based partition numbers of the pinned snapshot that may
 *                   contain matching rows, in ascending order
 * @param rowMatcher row test returning true for rows that must be deleted
 */
public record DeletePlan(List<Integer> rewriteSet, Predicate<Object[]> rowMatcher) {

    /**
     * Constructs a DeletePlan with a defensive copy of the rewrite set.
     *
     * @param rewriteSet zero-based partition numbers to rewrite
     * @param rowMatcher row test selecting rows to delete
     * @throws IllegalArgumentException if either argument is null
     */
    public DeletePlan {
        if (rewriteSet == null || rowMatcher == null) {
            throw new IllegalArgumentException("rewrite set and row matcher must not be null");
        }
        rewriteSet = List.copyOf(rewriteSet);
    }
}
