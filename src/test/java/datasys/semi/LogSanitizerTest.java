package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import datasys.semi.util.LogSanitizer;

/**
 * Unit tests verifying log value sanitization by {@link LogSanitizer}.
 */
class LogSanitizerTest {

    /**
     * Verifies that null string input produces an empty string.
     */
    @Test
    void sanitizeNullStringReturnsEmptyString() {
        String nullString = null;
        assertEquals("", LogSanitizer.sanitize(nullString));
    }

    /**
     * Verifies that null object input produces an empty string.
     */
    @Test
    void sanitizeNullObjectReturnsEmptyString() {
        Object nullObject = null;
        assertEquals("", LogSanitizer.sanitize(nullObject));
    }

    /**
     * Verifies that strings without illegal characters are unchanged.
     */
    @Test
    void sanitizeCleanStringRemainsUnchanged() {
        assertEquals("clean_identifier", LogSanitizer.sanitize("clean_identifier"));
        assertEquals("value123", LogSanitizer.sanitize("value123"));
    }

    /**
     * Verifies that commas are replaced with single spaces.
     */
    @Test
    void sanitizeCommasReplacedWithSpaces() {
        assertEquals("foo bar baz", LogSanitizer.sanitize("foo,bar,baz"));
        assertEquals("a  b", LogSanitizer.sanitize("a, b"));
    }

    /**
     * Verifies that double quotes are replaced with single quotes.
     */
    @Test
    void sanitizeDoubleQuotesReplacedWithSingleQuotes() {
        assertEquals("hello 'world'", LogSanitizer.sanitize("hello \"world\""));
        assertEquals("''", LogSanitizer.sanitize("\"\""));
    }

    /**
     * Verifies that newlines and carriage returns are replaced with spaces.
     */
    @Test
    void sanitizeNewlinesAndCarriageReturnsReplacedWithSpaces() {
        assertEquals("line1 line2", LogSanitizer.sanitize("line1\nline2"));
        assertEquals("line1 line2", LogSanitizer.sanitize("line1\rline2"));
        assertEquals("line1  line2", LogSanitizer.sanitize("line1\r\nline2"));
    }

    /**
     * Verifies that combined illegal characters are all replaced cleanly.
     */
    @Test
    void sanitizeCombinedIllegalCharacters() {
        String raw = "col1,'col2',\"value\",\nnext_line\r\n";
        String expected = "col1 'col2' 'value'  next_line  ";
        assertEquals(expected, LogSanitizer.sanitize(raw));
    }

    /**
     * Verifies that primitive wrappers and objects are properly converted and sanitized.
     */
    @Test
    void sanitizeObjectValuesConvertedAndSanitized() {
        assertEquals("42", LogSanitizer.sanitize(42L));
        assertEquals("3.14", LogSanitizer.sanitize(3.14));
        assertEquals("record[a=1  b=2]", LogSanitizer.sanitize((Object) "record[a=1, b=2]"));
    }

    /**
     * Verifies that an empty string remains empty.
     */
    @Test
    void sanitizeEmptyStringReturnsEmptyString() {
        assertEquals("", LogSanitizer.sanitize(""));
        assertEquals("", LogSanitizer.sanitize((Object) ""));
    }

    /**
     * Verifies that strings containing only delimiter characters are converted to spaces and quotes.
     */
    @Test
    void sanitizeStringWithOnlyDelimiters() {
        assertEquals(" '  ", LogSanitizer.sanitize(",\"\r\n"));
        assertEquals("       ", LogSanitizer.sanitize(",,,,,,,"));
    }
}

