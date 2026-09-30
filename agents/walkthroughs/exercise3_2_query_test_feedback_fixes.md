# Walkthrough - Exercise 3: Query-Test Feedback Fixes

Implementation plan: [`agents/implementation_plans/exercise3_2_query_test_feedback_fixes.md`](../implementation_plans/exercise3_2_query_test_feedback_fixes.md)

## 1. Summary of Work Accomplished
This change fixes every SeMi defect in the professor's *Exercise 3 - SQL front-end results* report (16 September 2026):

| Report item | Before | After |
|-------------|--------|-------|
| G0036-G0040: extreme doubles printed as `4.9E-324` / `1.7976931348623157E308` | 5 round-trip failures | Plain decimals that reparse to a bit-identical double |
| G0057: `COPY trips FROM '';` rejected by `CopyStatement` | AST construction failed | Parses, binds, round-trips |
| Signed zero | Preserved, but not tested | Preserved, with explicit boundary tests |
| Numeric overflow (`9223372036854775808`, 310-digit doubles) | Raw `NumberFormatException` or silent `Infinity`, logged as `line=0 col=0` | `SqlParseException` at the literal token's line and column |

The corpus now scores **77/77** good complete and **87/87** bad cases handled. B0029 is intentionally accepted since Exercise 4; see section 4. The report's comment that the hand-written error listener is not generated ANTLR code is a clarification: `SqlErrorListener` lives in `src/main/java` and may be committed. No change was needed.

## 2. Key Changes & Architecture
- **`SqlPrinter`**: `formatLiteral` is now a pattern-matching `switch` (`String` / `Long` / `Double`). The new `formatDouble`:
  1. rejects `NaN` and `±Infinity` with `IllegalArgumentException`, since the grammar has no literal for them;
  2. returns `Double.toString(value)` when it has no exponent. This covers `[1e-3, 1e7)` and zero, so `-0.0` keeps its sign and ordinary output is unchanged;
  3. otherwise expands the **shortest round-trip digits** with `new BigDecimal(Double.toString(value)).toPlainString()` and appends `.0` if no fraction is left. For example, `Double.MAX_VALUE` becomes a 309-digit integer part plus `.0`.

  Using the shortest representation instead of `new BigDecimal(double)` keeps `0.1` printing as `0.1`, not its 55-digit binary expansion. It is still exact, because Java's `Double.toString` is shortest-round-trip.
- **`SqlAstBuilder`**: `visitLiteral` delegates to `parseLongLiteral(Token)` and `parseDoubleLiteral(Token)`. Out-of-range values throw `SqlParseException(message, token.getLine(), token.getCharPositionInLine())`. The `LONG` variant chains the `NumberFormatException` as the cause. The facade's existing `catch (SqlParseException)` then logs `failed line=L col=C` with the real position.
- **`CopyStatement`**: only `null` paths are rejected. The empty path is valid front-end input, as in the exercise grammar and binder spec. Executing it fails later in `StorageEngine.copyFile` with `Could not read`.
- **`SqlCorpusIT`** (new, Failsafe): one `DynamicTest` per corpus case (164 in total). Good cases run the professor's pipeline in order against one `@TempDir` engine: parse → bind → create the table if absent → print → reparse → `assertEquals`. Bad cases must throw `SqlParseException` (LEXER/PARSER, with line ≥ 1 and column ≥ 0) or parse and then throw `IllegalArgumentException` from the binder (BINDER).
- **Test corpus**: `src/test/resources/sql-corpus/` holds copies of the two root `.txt` files. The three payloads the report says were corrupted in transit are restored: B0005 → U+000B, B0009 → CR inside a string, G0070 → CRLF. `.gitattributes` marks them `-text` so git keeps those bytes.

## 3. Verification Results
```
$ mvn -B clean verify
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.SqlParserTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.SqlPrinterTest
[INFO] Tests run: 43, Failures: 0, Errors: 0, Skipped: 0          (Surefire)
[INFO] Tests run: 164, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.SqlCorpusIT
[INFO] Tests run: 184, Failures: 0, Errors: 0, Skipped: 0         (Failsafe)
[INFO] BUILD SUCCESS
```

Regression check: `SqlCorpusIT` was run with the previous `SqlPrinter` and `CopyStatement` from `HEAD` swapped back in. It failed exactly good cases 36-40 and 57 (G0036-G0040, G0057), which reproduces the report's 71/77. With the fixes it passes 77/77.

## 4. Edge Cases & Invariants Tested
- **Double boundaries** (`SqlPrinterTest.extremeDoublesPrintAsPlainDecimalsAndRoundTripExactly`): `±Double.MIN_VALUE`, `±Double.MIN_NORMAL`, the largest subnormal, `±Double.MAX_VALUE`, and values on both sides of the `Double.toString` exponent thresholds (`1e-3`, `nextDown(1e-3)`, `1e-4`, `1e-5`, `1e7`, `nextDown(1e7)`, `1e8`, `1e20`). Also `2^53 + 1`, `0.1`, and excess-precision input. Each case checks the grammar shape `-?[0-9]+\.[0-9]+`, bit-exact reparsing with `Double.compare == 0`, and AST round-trip.
- **Signed zero**: `-0.0` prints as `-0.0` and `0.0` as `0.0`. The reparsed constant is negative zero. Parsing `-000.000` yields `-0.0`.
- **Non-finite doubles**: `NaN` and `±Infinity` make the printer throw `IllegalArgumentException`.
- **Long boundaries**: `Long.MIN_VALUE` and `Long.MAX_VALUE` parse exactly and round-trip.
- **Overflow positions**: `9223372036854775808` on line 2, column 19 after indentation. `-9223372036854775809` at column 37, where the column points at the `-`. A 310-digit `DOUBLE`, positive and negative, after a leading comment line. An overflow in the second statement of a script is reported on line 2.
- **Empty COPY path**: parses to `CopyStatement("trips", "")`, prints `COPY trips FROM '';`, and round-trips.
- **Superseded corpus case**: B0029 (`SELECT city FROM trips;`) was an Exercise 3 PARSER rejection. Exercise 4 §5 column lists make it valid, so the corpus test asserts it parses and binds. This mirrors how the report treats the professor's current version.
