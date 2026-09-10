package datasys.semi;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

/**
 * Fail-fast ANTLR error listener that throws SqlParseException upon the first
 * syntax error.
 */
final class SqlErrorListener extends BaseErrorListener {

    // --- Singleton Instance ---
    static final SqlErrorListener INSTANCE = new SqlErrorListener();

    /**
     * Constructs a new SqlErrorListener.
     */
    private SqlErrorListener() {
    }

    /**
     * Handles syntax errors detected by the lexer or parser by throwing a
     * SqlParseException.
     *
     * @param recognizer         the recognizer that detected the error
     * @param offendingSymbol    the offending token or symbol
     * @param line               the 1-based line number where the error occurred
     * @param charPositionInLine the 0-based column offset where the error occurred
     * @param message            description of the error
     * @param exception          underlying recognition exception
     * @throws SqlParseException unconditionally on error detection
     */
    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
            int line, int charPositionInLine, String message, RecognitionException exception) {
        throw new SqlParseException(message, line, charPositionInLine, exception);
    }
}
