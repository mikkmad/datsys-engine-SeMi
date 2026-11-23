package datasys.semi.optimizer;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Chooses how the planner orders the joins of a multi-table {@code SELECT}.
 *
 * <p>
 * {@link #OPTIMIZED} (the default) orders joins greedily by smallest estimated
 * intermediate result. {@link #SYNTAX} reproduces week 10's plan exactly:
 * tables are joined left-deep in {@code FROM}-list order and the tables joined
 * so far always build. Forcing a join order is then a matter of running in
 * {@code SYNTAX} mode with the {@code FROM} list in the wanted order. Immutable
 * and thread-safe.
 */
public enum JoinOrderMode {

    /** Week 10's left-deep plan in {@code FROM}-list order. */
    SYNTAX,

    /** Greedy smallest-intermediate-first ordering from catalog statistics. */
    OPTIMIZED;

    /**
     * Parses the value of the engine's {@code -j} flag.
     *
     * @param flagValue {@code syntax} or {@code optimized}, case-insensitive
     * @return the matching mode
     * @throws IllegalArgumentException if {@code flagValue} is null or names no
     *                                  mode
     */
    public static JoinOrderMode fromFlag(String flagValue) {
        if (flagValue == null) {
            throw new IllegalArgumentException("join order mode must not be null");
        }
        return switch (flagValue.toLowerCase(Locale.ROOT)) {
            case "syntax" -> SYNTAX;
            case "optimized" -> OPTIMIZED;
            default -> throw new IllegalArgumentException("unknown join order mode: " + flagValue
                    + "; expected one of " + supportedFlagValues());
        };
    }

    /**
     * Returns the value of this mode as written after the {@code -j} flag and in
     * log lines.
     *
     * @return {@code syntax} or {@code optimized}
     */
    public String flagValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Lists the accepted flag values for an error message.
     *
     * @return flag values separated by {@code " or "}
     */
    private static String supportedFlagValues() {
        return Arrays.stream(values())
                .map(JoinOrderMode::flagValue)
                .collect(Collectors.joining(" or "));
    }
}
