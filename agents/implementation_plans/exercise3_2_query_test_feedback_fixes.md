# Implementation Plan - Exercise 3: Query-Test Feedback Fixes

## 1. Sub-Exercise Reference
- Specification: [`exercise_descriptions/Exercise3.md`](../../exercise_descriptions/Exercise3.md) (sections 1, 3 and 5)
- Feedback source: *Exercise 3 - SQL front-end results* (professor evaluation, 16 September 2026), group **SeMi**
- Test corpus: `good_sql_queries.txt` (77 cases) and `bad_sql_queries.txt` (87 cases)

## 2. Background & Objective
The professor's evaluation scored SeMi at 76/77 parse + AST, 71/77 good complete, and 87/87 bad correctly rejected. The written feedback:

> String.valueOf renders small and large doubles in exponent notation, which the grammar cannot read. Print plain decimals, preserve signed zero and add tests for those boundaries. Convert numeric overflow into SqlParseException with the original token position.

The report lists the failing cases:

| Case | Failure | Root cause |
|------|---------|------------|
| G0036-G0040 | Printer emits `4.9E-324`, `1.7976931348623157E308`, and similar values. The grammar has no exponent syntax, so reparsing fails. | `SqlPrinter.formatLiteral` uses `String.valueOf(Double)`. |
| G0057 | `COPY trips FROM '';` is rejected while the AST is built. | The `CopyStatement` constructor rejects blank paths, which is stricter than the grammar and binder spec. |
| (not in corpus) | An out-of-range literal such as `9223372036854775808` leaks `NumberFormatException` with no position. A huge decimal becomes `Infinity`, which cannot be printed back. | `SqlAstBuilder.visitLiteral` calls `Long.parseLong` / `Double.parseDouble` unguarded. |

The report also says: "The hand written error listener is not generated ANTLR code." This is a clarification, not a defect. `SqlErrorListener` is a hand-written class in `src/main/java`, so committing it does not break the "never commit ANTLR-generated code" rule. No change is needed.

## 3. Requirements & Invariants
1. **Plain-decimal doubles**: every finite `Double` prints in a form that matches `DOUBLE_LITERAL : '-'? [0-9]+ '.' [0-9]+`. That means no exponent, at least one digit on each side of the `.`, and a `-` sign only for negative values.
2. **Exact round-trip**: `Double.parseDouble(print(d))` is bit-identical to `d` (`Double.compare == 0`). This covers `Double.MIN_VALUE`, `Double.MIN_NORMAL`, `±Double.MAX_VALUE`, and powers of ten on both sides of `Double.toString`'s exponent thresholds (`1e-3`, `1e7`).
3. **Signed zero**: `-0.0` prints as `-0.0` and reparses to negative zero. `0.0` prints as `0.0`.
4. **Non-finite doubles**: `NaN` and `±Infinity` have no SQL literal. The printer throws `IllegalArgumentException` instead of emitting unparseable text.
5. **Numeric overflow**: a `LONG_LITERAL` outside `[Long.MIN_VALUE, Long.MAX_VALUE]`, or a `DOUBLE_LITERAL` whose magnitude rounds to infinity, throws `SqlParseException`. The exception carries the literal token's 1-based line and 0-based column. The existing `failed line=… col=…` error log line is written.
6. **Empty COPY path**: `COPY t FROM '';` parses to `CopyStatement("t", "")`, binds when `t` exists, and round-trips. A `null` path is still rejected. Whether the file is readable is left to execution.
7. **Corpus regression**: all 77 good cases parse, bind, print and reparse to an equal AST. All 87 bad cases are rejected at the stage their label names: LEXER/PARSER → `SqlParseException`, BINDER → `IllegalArgumentException` from the binder after a successful parse.

## 4. Proposed Changes
- `src/main/java/datasys/semi/parser/SqlPrinter.java` [MODIFY]
  - `formatLiteral` dispatches with a pattern-matching `switch` over `String` / `Long` / `Double`.
  - New `formatDouble(double)`: rejects non-finite values. Returns `Double.toString` when it has no exponent (this keeps `-0.0` and the current output for normal values). Otherwise expands the shortest representation through `new BigDecimal(Double.toString(value)).toPlainString()` and appends `.0` when no fraction digits remain.
- `src/main/java/datasys/semi/parser/SqlAstBuilder.java` [MODIFY]
  - `visitLiteral` delegates to new helpers `parseLongLiteral(Token)` and `parseDoubleLiteral(Token)`. These throw `SqlParseException(message, token.getLine(), token.getCharPositionInLine(), cause)` on overflow.
- `src/main/java/datasys/semi/models/CopyStatement.java` [MODIFY]
  - Validation relaxed to `csvFilePath != null`. Javadoc updated.
- `src/test/java/datasys/semi/SqlPrinterTest.java` [MODIFY]: boundary round-trip tests (subnormal, normal, max, powers of ten, signed zero, `Long` extremes, empty COPY path), plain-decimal shape assertions, and non-finite rejection.
- `src/test/java/datasys/semi/SqlParserTest.java` [MODIFY]: overflow position tests for `LONG` and `DOUBLE`, `Long` boundary acceptance, negative-zero parsing, and empty COPY path.
- `src/test/resources/sql-corpus/{good,bad}_sql_queries.txt` [NEW]: copies of the evaluation corpus. The three control-character payloads the professor repaired (B0005 → U+000B, B0009 → CR, G0070 → CRLF) are restored here. The root-level files are left untouched.
- `src/test/java/datasys/semi/SqlCorpusIT.java` [NEW]: data-driven Failsafe test (`@TestFactory` dynamic tests, one per case) that runs the professor's pipeline against a `trips` table on a `@TempDir` engine.

## 5. Verification Plan
```bash
mvn clean compile
mvn -B verify
```
Expected: all existing tests stay green. The new `SqlPrinterTest` and `SqlParserTest` boundary tests pass. `SqlCorpusIT` reports 77 good and 87 bad dynamic tests with 0 failures and 0 errors.
