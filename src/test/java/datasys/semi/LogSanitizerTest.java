package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import datasys.semi.util.LogSanitizer;

/**
 * Unit tests verifying log message sanitization according to Issue #40.
 */
class LogSanitizerTest {

    @Test
    void sanitizeNullReturnsEmptyString() {
        assertEquals("", LogSanitizer.sanitize(null));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "hello,world|hello world",
            "say \"hello\"|say 'hello'",
            "'line1\rline2'|line1 line2",
            "'line1\nline2'|line1 line2"
    }, delimiter = '|')
    void sanitizeReplacesDelimitersAndNewlines(String input, String expected) {
        assertEquals(expected, LogSanitizer.sanitize(input));
    }

    /**
     * Verifies that list items share one log value joined with {@code +}, never
     * with the CSV delimiter, and that a null list is rejected.
     */
    @Test
    void joinListSeparatesItemsWithPlusInsteadOfComma() {
        assertEquals("products+orders+customers", LogSanitizer.joinList(List.of("products", "orders", "customers")));
        assertEquals("orders", LogSanitizer.joinList(List.of("orders")));
        assertEquals("", LogSanitizer.joinList(List.of()));
        assertThrows(IllegalArgumentException.class, () -> LogSanitizer.joinList(null));
    }
}
