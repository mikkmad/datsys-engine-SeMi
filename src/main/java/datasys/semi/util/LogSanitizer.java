package datasys.semi.util;

/**
 * Utility for sanitizing raw log values to comply with the 7-column CSV log
 * format.
 */
public final class LogSanitizer {

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
}
