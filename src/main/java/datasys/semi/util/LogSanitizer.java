package datasys.semi.util;

import java.util.List;

/**
 * Utility for sanitizing raw log values to comply with the 7-column CSV log
 * format, and for writing lists into a single log value without commas.
 * Stateless and thread-safe.
 */
public final class LogSanitizer {

    // --- Constants ---
    /**
     * Separates the items of a list inside one log value, for example the tables
     * of a join ({@code orders+customers}); a comma would split the CSV column.
     */
    public static final String LIST_SEPARATOR = "+";

    /**
     * Prevents instantiation of this utility class.
     */
    private LogSanitizer() {
    }

    /**
     * Sanitizes a value by stripping CSV delimiters and control characters.
     *
     * @param value the raw object value to sanitize
     * @return sanitized string representation, or empty string if null
     */
    public static String sanitize(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .replace(',', ' ')
                .replace('"', '\'')
                .replace('\r', ' ')
                .replace('\n', ' ');
    }

    /**
     * Joins list items into one log value with {@link #LIST_SEPARATOR}.
     *
     * @param items the items in the wanted order, e.g. table names
     * @return the items separated by {@code +}; empty for an empty list
     * @throws IllegalArgumentException if {@code items} is null
     */
    public static String joinList(List<String> items) {
        if (items == null) {
            throw new IllegalArgumentException("items must not be null");
        }
        return String.join(LIST_SEPARATOR, items);
    }
}
