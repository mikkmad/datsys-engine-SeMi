package datasys.semi.util;

/**
 * Utility for sanitizing raw strings and objects before embedding them in engine log entries.
 *
 * <p>
 * Because engine execution logs conform to a strict 7-column CSV schema
 * ({@code timestamp,sessionId,statementNumber,threadId,logLevel,className,logMessage}),
 * raw values embedded into {@code logMessage} must never contain unescaped commas,
 * double quotes, newlines, or carriage returns. This class cleans such values safely.
 *
 * <p>
 * Threading assumptions: Stateless, immutable, and fully thread-safe.
 */
public final class LogSanitizer {

    /**
     * Private constructor to prevent instantiation of static utility class.
     */
    private LogSanitizer() {
    }

    /**
     * Sanitizes a string value by removing or replacing characters that violate
     * the engine's CSV log format.
     *
     * <p>
     * Replaces commas with spaces, double quotes with single quotes, and newline or
     * carriage return characters with spaces. If the input is null, returns an empty string.
     *
     * @param rawValue the raw string to sanitize, may be null
     * @return sanitized string without commas, double quotes, or newline characters
     */
    public static String sanitize(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        return rawValue.replace(',', ' ')
                .replace('"', '\'')
                .replace('\r', ' ')
                .replace('\n', ' ');
    }

    /**
     * Sanitizes an arbitrary object value by converting it to string and sanitizing it.
     *
     * @param rawObject the raw object to sanitize, may be null
     * @return sanitized string representation, or empty string if rawObject is null
     */
    public static String sanitize(Object rawObject) {
        if (rawObject == null) {
            return "";
        }
        if (rawObject instanceof String stringValue) {
            return sanitize(stringValue);
        }
        return sanitize(String.valueOf(rawObject));
    }
}

