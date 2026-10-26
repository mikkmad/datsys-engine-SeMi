# Walkthrough — Exercise 7.3: DELETE Through the Front End

## 1. Summary of Work Accomplished

Implemented the complete front-end pipeline for the `DELETE` statement per [`exercise_descriptions/Exercise7.md` §3](file:///workspaces/SeMi/exercise_descriptions/Exercise7.md#L35-L53) and [`agents/implementation_plans/exercise7_3_delete_front_end.md`](file:///workspaces/SeMi/agents/implementation_plans/exercise7_3_delete_front_end.md), incorporating review feedback.

- **Grammar & Lexer** ([`Sql.g4`](file:///workspaces/SeMi/src/main/antlr4/datasys/semi/sql/Sql.g4)): Added `delete : DELETE FROM IDENTIFIER (WHERE predicate)? ;` to `statement`, and declared `DELETE : 'DELETE' ;` in the lexer before `IDENTIFIER`.
- **AST Representation** ([`DeleteStatement.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/models/DeleteStatement.java), [`Statement.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/models/Statement.java)): Created immutable record `DeleteStatement(String tableName, Optional<Predicate> where)` with argument validation, and permitted it in the sealed `Statement` interface.
- **AST Construction** ([`SqlAstBuilder.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/SqlAstBuilder.java)): Dispatched `delete` in `visitStatement` and implemented `visitDelete` extracting table identifier and optional typed `Predicate`. Added `@throws IllegalStateException` to `visitStatement` Javadoc.
- **SQL Pretty-Printing & Literal Escaping** ([`SqlPrinter.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/SqlPrinter.java)): Formatted `DeleteStatement` into DuckDB-compatible canonical SQL (`DELETE FROM <table>;` and `DELETE FROM <table> WHERE <predicate>;`) ensuring `parse(print(s)).getFirst().equals(s)` round-trip equality; validated string literals to reject unprintable programmatic strings containing `'`, `\r`, or `\n`.
- **Catalog Binding & Error Logging** ([`Binder.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/Binder.java)): Unified table-existence, column-existence, and predicate constant type validation for both `SELECT` and `DELETE` via shared helper `bindTableAndWhere`. Added SLF4J logger emitting `bind_failed` at `ERROR` for all validation failures per `AGENTS.md` §5.3.
- **Execution Boundary, Null-Safety & Error Logging** ([`Executor.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/executor/Executor.java)): Pinned snapshot at statement start, validated via `binder.bind(delete, snapshot)`, mapped operation name to `"DELETE"`, preserved empty stdout for `DELETE`, handled `null` statements safely (avoiding secondary NPEs in `resolveOperation`), logged `LOGGER.error` on all programmatic entry points, and stubbed `executeDelete` throwing `UnsupportedOperationException("DELETE execution is not implemented yet")` ready for Exercise 7 §4.

---

## 2. Key Changes & Architecture

| Component | File | Summary of Changes |
|-----------|------|--------------------|
| Grammar | [`Sql.g4`](file:///workspaces/SeMi/src/main/antlr4/datasys/semi/sql/Sql.g4) | Extended `statement` rule with `delete`; defined `delete` rule; declared lexer keyword `DELETE` before `IDENTIFIER`. |
| AST Model | [`DeleteStatement.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/models/DeleteStatement.java) | New immutable record implementing `Statement`, validating non-blank `tableName` and non-null `where`. |
| AST Interface | [`Statement.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/models/Statement.java) | Added `DeleteStatement` to sealed `permits` clause. |
| AST Builder | [`SqlAstBuilder.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/SqlAstBuilder.java) | Added `delete` branch to `visitStatement`; added `@throws IllegalStateException` tag; implemented `visitDelete(SqlParser.DeleteContext)`. |
| Pretty-Printer | [`SqlPrinter.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/SqlPrinter.java) | Added `DeleteStatement` branch to `switch (statement)`; implemented `printDelete`; added `formatString` rejecting `'`, `\r`, and `\n`. |
| Catalog Binder | [`Binder.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/Binder.java) | Added `DeleteStatement` branches; extracted `bindTableAndWhere` helper; added SLF4J `LOGGER.error("bind_failed reason=...")` helper. |
| Executor | [`Executor.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/executor/Executor.java) | Pinned snapshot and bound `DeleteStatement` at statement start; mapped `"DELETE"` in `resolveOperation`; added null-safety (`UNKNOWN` fallback) and `LOGGER.error` logging across all programmatic entry points; stubbed `executeDelete`. |

---

## 3. Verification Results

Executed `mvn clean compile` and `mvn -B verify`. All unit and integration test suites passed with **0 failures and 0 errors**:

| Suite | Test Class | Tests Run | Failures | Errors |
|-------|------------|-----------|----------|--------|
| Surefire | `EngineTest` | 5 | 0 | 0 |
| Surefire | `ExecutorTest` | 14 | 0 | 0 |
| Surefire | `FilterOperatorTest` | 1 | 0 | 0 |
| Surefire | `LogSanitizerTest` | 5 | 0 | 0 |
| Surefire | `PlannerTest` | 6 | 0 | 0 |
| Surefire | `ScanOperatorTest` | 2 | 0 | 0 |
| Surefire | `SqlParserTest` | 14 | 0 | 0 |
| Surefire | `SqlPrinterTest` | 14 | 0 | 0 |
| Surefire | `StorageEngineUnitTest` | 3 | 0 | 0 |
| Failsafe | `EngineIT` | 6 | 0 | 0 |
| Failsafe | `EngineLogIT` | 2 | 0 | 0 |
| Failsafe | `SqlCorpusIT` | 164 | 0 | 0 |
| Failsafe | `StorageEngineIT` | 17 | 0 | 0 |
| Failsafe | `BinderIT` | 10 | 0 | 0 |
| **Total** | **All Suites** | **263** | **0** | **0** |

---

## 4. Edge Cases & Invariants Tested

1. **Parser AST Construction & Round-Trip** ([`SqlParserTest.parsesEachStatementShape`](file:///workspaces/SeMi/src/test/java/datasys/semi/SqlParserTest.java#L28-L86), [`SqlPrinterTest.roundTripDeleteStatementsWithoutWhere`](file:///workspaces/SeMi/src/test/java/datasys/semi/SqlPrinterTest.java#L64-L69), [`SqlPrinterTest.roundTripDeleteStatementsWithWhereComparisonsAndTypes`](file:///workspaces/SeMi/src/test/java/datasys/semi/SqlPrinterTest.java#L184-L213)):
   - `DELETE FROM trips;` parses to `DeleteStatement("trips", Optional.empty())` and round-trips (**Exercise 7 §5 Test 1**).
   - `DELETE FROM trips WHERE distance > 100;` parses to typed predicate and round-trips (**Exercise 7 §5 Test 1**).
   - Tested all comparison operators (`=`, `<`, `>`) and literal types (`String`, `Long`, `Double`, including extremes `Long.MIN_VALUE`, `Long.MAX_VALUE`, `Double.MIN_VALUE`, `Double.MAX_VALUE`, `0.0`, and `-0.0`). Non-finite doubles (`NaN`, `±Infinity`) are rejected.
2. **Case Insensitivity & Identifier Boundaries** ([`SqlParserTest.deleteKeywordCaseInsensitiveAndKeywordPrefixIdentifier`](file:///workspaces/SeMi/src/test/java/datasys/semi/SqlParserTest.java#L271-L287)):
   - `dElEtE fRoM TriPs wHeRe CityName = 'Odense';` preserves table and column identifier casing (`TriPs`, `CityName`).
   - Identifiers starting with keyword prefixes (`DELETE FROM delete1 WHERE deleted_rows = 1;`) are recognized as valid identifiers.
3. **Syntax Error Coordinates** ([`SqlParserTest.malformedDeleteInputsAssertLineAndColumn`](file:///workspaces/SeMi/src/test/java/datasys/semi/SqlParserTest.java#L288-L332)):
   - `DELETE trips;`, `DELETE * FROM trips;`, `DELETE FROM;`, `DELETE FROM DELETE;`, `DELETE FROM trips WHERE;`, and missing semicolon report exact 1-based line and 0-based column in `SqlParseException`.
   - Numeric overflow in `DELETE ... WHERE` reports token position and wraps `NumberFormatException`.
4. **Binder Validation & Complete Off-Diagonal Type Checks** ([`BinderIT.deleteUnknownTableThrowsIllegalArgumentException`](file:///workspaces/SeMi/src/test/java/datasys/semi/BinderIT.java#L102-L121), [`BinderIT.deleteUnknownColumnInWhereThrowsIllegalArgumentException`](file:///workspaces/SeMi/src/test/java/datasys/semi/BinderIT.java#L123-L132), [`BinderIT.deleteTypeMismatchedConstantInWhereThrowsIllegalArgumentException`](file:///workspaces/SeMi/src/test/java/datasys/semi/BinderIT.java#L134-L168), [`BinderIT.bindFailureLogsBindFailedAtErrorLevel`](file:///workspaces/SeMi/src/test/java/datasys/semi/BinderIT.java#L240-L261)):
   - Unknown table (with/without `WHERE`, null snapshot, mismatched snapshot) throws `IllegalArgumentException` (**Exercise 7 §5 Test 2**).
   - Unknown column in `WHERE` throws `IllegalArgumentException` (**Exercise 7 §5 Test 2**).
   - Tested all 6 cross-type combinations (`LONG`←`String`, `LONG`←`Double`, `STRING`←`Long`, `STRING`←`Double`, `DOUBLE`←`String`, `DOUBLE`←`Long`) throwing `IllegalArgumentException` (**Exercise 7 §5 Test 2**).
   - Direct `Binder.bind(...)` validation failures log `bind_failed` at `ERROR`.
5. **Stdout Silence, Null-Safety & Error Logging** ([`ExecutorTest.deleteBindingFailureLogsStatementFailedAndLeavesOutputEmpty`](file:///workspaces/SeMi/src/test/java/datasys/semi/ExecutorTest.java#L252-L277), [`ExecutorTest.deleteExecutionThrowsUnsupportedOperationExceptionAndLogsStatementFailed`](file:///workspaces/SeMi/src/test/java/datasys/semi/ExecutorTest.java#L284-L316), [`ExecutorTest.executeQueryRejectsDeleteStatement`](file:///workspaces/SeMi/src/test/java/datasys/semi/ExecutorTest.java#L319-L323), [`ExecutorTest.executeListWithNullElementLogsErrorAndThrowsIllegalArgumentException`](file:///workspaces/SeMi/src/test/java/datasys/semi/ExecutorTest.java#L329-L352), [`ExecutorTest.executeQueryRejectsNonSelectAndLogsError`](file:///workspaces/SeMi/src/test/java/datasys/semi/ExecutorTest.java#L357-L374)):
   - Failing `DELETE` in script throws `IllegalArgumentException`, logs `statement_failed operation=DELETE reason=...` at `ERROR` with active statement number in MDC, resets MDC to `"0"`, and leaves `output` with 0 bytes.
   - Front-end execution boundary throws `UnsupportedOperationException`, logs `operation=DELETE`, and keeps `output` at 0 bytes.
   - `executeQuery` rejects `DELETE` with `IllegalArgumentException("expected exactly one SELECT statement")` and logs `ERROR`.
   - Passing a list with `null` element throws `IllegalArgumentException`, resolves `UNKNOWN` operation name, and logs `ERROR` without secondary NPE.
6. **Unprintable String Literals** ([`SqlPrinterTest.unprintableStringLiteralsAreRejectedByPrinter`](file:///workspaces/SeMi/src/test/java/datasys/semi/SqlPrinterTest.java#L214-L235)):
   - Programmatically constructed statements with strings containing `'`, `\r`, or `\n` throw `IllegalArgumentException` to maintain `parse(print(s)).equals(s)` invariant.
7. **Corpus Non-Regression** ([`SqlCorpusIT`](file:///workspaces/SeMi/src/test/java/datasys/semi/SqlCorpusIT.java)):
   - All 77 good cases and 87 bad cases in `SqlCorpusIT` pass unchanged.
