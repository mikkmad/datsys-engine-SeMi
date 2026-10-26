# Implementation Plan — Exercise 7.3: DELETE Through the Front End

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise7.md` §3 "DELETE through the front end"](file:///workspaces/SeMi/exercise_descriptions/Exercise7.md#L35-L53) & [§5 "Required tests (JUnit 6)" (Tests 1–2)](file:///workspaces/SeMi/exercise_descriptions/Exercise7.md#L64-L67)
- Design decisions: [`docs/transactions-design.md`](file:///workspaces/SeMi/docs/transactions-design.md#L15-L20) (§3 Snapshot: statement-start catalog pinning before binding)
- Prerequisite plan: [`agents/implementation_plans/exercise7_2_versioned_catalog_repeated_copy.md`](file:///workspaces/SeMi/agents/implementation_plans/exercise7_2_versioned_catalog_repeated_copy.md)

## 2. Background & Objective

Today the SQL front end (`Sql.g4`, `Statement`, `SqlAstBuilder`, `SqlPrinter`, `Binder`, and `Executor`) recognizes only three statement kinds: `CREATE TABLE`, `COPY`, and `SELECT`. Exercise 7 introduces light-weight DML via `DELETE` without in-place mutation.

Before implementing copy-on-write partition rewriting in §4, the SQL front end must accept `DELETE FROM <table>` with an optional `WHERE <predicate>` clause, build an immutable `DeleteStatement` AST node, and propagate it through every exhaustive `switch` over the sealed [`Statement`](file:///workspaces/SeMi/src/main/java/datasys/semi/models/Statement.java#L6-L8) hierarchy:

- **Grammar & Parser** ([`Sql.g4`](file:///workspaces/SeMi/src/main/antlr4/datasys/semi/sql/Sql.g4), [`SqlAstBuilder.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/SqlAstBuilder.java)): Lex `DELETE` as a case-insensitive keyword before `IDENTIFIER` and parse `DELETE FROM <identifier> [WHERE <predicate>];` into `DeleteStatement`.
- **Pretty-Printer** ([`SqlPrinter.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/SqlPrinter.java)): Render `DeleteStatement` back to canonical, DuckDB-compatible SQL that round-trips through `SqlParser.parse`.
- **Binder** ([`Binder.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/Binder.java)): Validate `DeleteStatement` against the pinned [`Catalog`](file:///workspaces/SeMi/src/main/java/datasys/semi/engine/StorageEngine.java#L970-L1005) snapshot using the exact same rules as `SELECT` (table exists, predicate column exists, constant matches column type).
- **Executor** ([`Executor.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/executor/Executor.java)): Pin the table's catalog snapshot at statement start, bind `DeleteStatement`, emit nothing to `stdout`, map `DeleteStatement` to `"DELETE"` in failure logs, and fail explicitly with `UnsupportedOperationException` in `executeDelete` until §4 wires in copy-on-write execution.

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | `Sql.g4` adds `delete` to `statement`, defines `delete : DELETE FROM IDENTIFIER (WHERE predicate)? ;`, and declares `DELETE : 'DELETE' ;` before `IDENTIFIER` in the lexer. |
| F2 | Keywords (`DELETE`, `FROM`, `WHERE`) remain case-insensitive while table and column identifier casing is preserved. `DELETE` is a reserved keyword and cannot be used as a table or column identifier, whereas prefixed identifiers (e.g., `delete1`, `deleted_rows`) remain valid identifiers. |
| F3 | `DeleteStatement(String tableName, Optional<Predicate> where)` is an immutable Java record in `datasys.semi.models` implementing `Statement`, and is added to `Statement`'s `permits` list. |
| F4 | `DeleteStatement`'s compact constructor rejects `null` or blank `tableName` and `null` `where` with `IllegalArgumentException` (matching `SelectStatement`). |
| F5 | `SqlPrinter.print(DeleteStatement)` emits `"DELETE FROM <table>;"` when `where` is empty and `"DELETE FROM <table> WHERE <predicate>;"` when `where` is present, satisfying `parse(print(s)).getFirst().equals(s)`. |
| F6 | Every `DELETE` statement accepted by our grammar and emitted by `SqlPrinter` is valid, unchanged DuckDB SQL (e.g., `DELETE * FROM t;` is rejected). |
| F7 | `Binder.bind(Statement)` and `Binder.bind(Statement, Catalog)` validate `DeleteStatement` identically to `SelectStatement`: target table exists in the pinned snapshot, and if `WHERE` is present, the named column exists in the schema and the literal constant's Java type (`String`, `Long`, `Double`) matches the column's `ColumnType`. Violations throw `IllegalArgumentException`. |
| F8 | `Executor` pins `engine.snapshot(delete.tableName())` at statement start before binding, binds `DeleteStatement` against that snapshot, writes zero bytes to `output` (`stdout` stays reserved for `SELECT`), resolves the operation name as `"DELETE"` for `statement_failed` logs, and stubs `executeDelete` with `UnsupportedOperationException` until Exercise 7 §4. |

### Invariants
- **I1 Sealed Exhaustiveness:** Adding `DeleteStatement` to `Statement`'s `permits` clause requires every `switch (statement)` (`SqlPrinter.print`, `Binder.bind` overloads, `Executor.executeStatement`, `Executor.resolveOperation`) to handle `DeleteStatement` explicitly without `default` catch-alls.
- **I2 Snapshot Pinning:** `Executor` pins the `Catalog` snapshot once at statement start before calling `Binder.bind(delete, snapshot)` (design §3). `Binder.bind(Statement)` pins via `engine.snapshot(delete.tableName())` when invoked standalone.
- **I3 Stdout Cleanliness:** `DELETE` never writes rows or diagnostic text to `stdout` (`Executor.output`), whether succeeding or failing.
- **I4 Logging Hygiene:** Only `LOGGER.debug` and `LOGGER.error` are used; all failure paths through `SqlParser`, `StorageEngine.snapshot`, and `Executor.execute` emit comma-free `ERROR` log lines with active MDC `sessionId` and `statementNumber` (`AGENTS.md` §5).
- **I5 Corpus Compatibility:** All 77 good cases and 87 bad cases in `SqlCorpusIT` continue to pass with zero regressions.

### Non-Functional
- Methods remain short (10–20 lines) with guard clauses, 4-space indentation, grouped fields, and full Javadoc (`@param`, `@return`, `@throws`) on all classes, records, constructors, and methods (`AGENTS.md` §3–§4).

## 4. Proposed Changes

### 4.1 Grammar — [`src/main/antlr4/datasys/semi/sql/Sql.g4`](file:///workspaces/SeMi/src/main/antlr4/datasys/semi/sql/Sql.g4)
- Extend `statement` (L6):
  ```antlr
  statement   : createTable | copy | select | delete ;
  ```
- Add the `delete` parser rule alongside `select` (after L15):
  ```antlr
  delete      : DELETE FROM IDENTIFIER (WHERE predicate)? ;
  ```
- Add the `DELETE` lexer rule **before** `IDENTIFIER` (L21):
  ```antlr
  SELECT : 'SELECT' ;   DELETE : 'DELETE' ;   WHERE : 'WHERE' ;
  ```

### 4.2 AST Model — [`DeleteStatement.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/models/DeleteStatement.java) & [`Statement.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/models/Statement.java)
- Create `src/main/java/datasys/semi/models/DeleteStatement.java`:
  ```java
  public record DeleteStatement(String tableName, Optional<Predicate> where)
          implements Statement {
      public DeleteStatement {
          if (tableName == null || tableName.isBlank()) {
              throw new IllegalArgumentException("table name must not be null or blank");
          }
          if (where == null) {
              throw new IllegalArgumentException("where optional must not be null");
          }
      }
  }
  ```
- Update [`Statement.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/models/Statement.java#L6-L8) `permits` list:
  ```java
  public sealed interface Statement
          permits CreateTableStatement, CopyStatement, SelectStatement, DeleteStatement {
  }
  ```

### 4.3 AST Builder — [`src/main/java/datasys/semi/parser/SqlAstBuilder.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/SqlAstBuilder.java)
- Update `visitStatement` (L48–L57) to dispatch `context.delete()` (with an explicit `IllegalStateException` guard if no alternative matches, matching `visitColumnType` and `visitLiteral`).
- Override `visitDelete(SqlParser.DeleteContext context)`:
  - Extract `tableName = context.IDENTIFIER().getText()`.
  - Build `Optional<Predicate> where` by delegating to `visit(context.predicate())` when `context.predicate() != null`, inheriting existing literal typing and numeric overflow position reporting (`SqlParseException`).
  - Return `new DeleteStatement(tableName, where)`.

### 4.4 Pretty-Printer — [`src/main/java/datasys/semi/parser/SqlPrinter.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/SqlPrinter.java)
- Add `case DeleteStatement delete -> printDelete(delete);` to `print(Statement)` (L31–L35).
- Add private helper `printDelete(DeleteStatement statement)`:
  - When `statement.where().isEmpty()`, return `"DELETE FROM " + statement.tableName() + ";"`.
  - Otherwise, return `"DELETE FROM " + statement.tableName() + " WHERE " + printPredicate(statement.where().get()) + ";"`, reusing `printPredicate` and `formatLiteral`/`formatDouble` for exact round-trip formatting.

### 4.5 Catalog Binder — [`src/main/java/datasys/semi/parser/Binder.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/Binder.java)
- Add `case DeleteStatement delete -> bind(delete, engine.snapshot(delete.tableName()));` to `bind(Statement)` (L53–L57).
- Add `case DeleteStatement delete -> bindDelete(delete, snapshot);` to `bind(Statement, Catalog)` (L73–L77).
- Extract shared helper `bindTableAndWhere(String tableName, Optional<Predicate> where, Catalog snapshot)` from `bindSelect` (L125–L140) so both `bindSelect` and `bindDelete` execute the exact same table-existence, column-existence, and constant-type checks without code duplication:
  1. `requireMatchingSnapshot(tableName, snapshot)` (`IllegalArgumentException` on null/mismatched snapshot).
  2. Guard clause: if `where.isEmpty()`, return immediately.
  3. Look up `predicate.columnName()` in `snapshot.columnSpecs()`, throwing `IllegalArgumentException("unknown column: " + predicate.columnName())` if absent.
  4. Call `validatePredicateType(matchedColumn.type(), predicate.constant())`.

### 4.6 Executor — [`src/main/java/datasys/semi/executor/Executor.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/executor/Executor.java)
- In `executeStatement(Statement)` (L175–L190), add the `DeleteStatement` branch:
  ```java
  case DeleteStatement delete -> {
      Catalog snapshot = engine.snapshot(delete.tableName());
      binder.bind(delete, snapshot);
      executeDelete(delete, snapshot, started);
  }
  ```
- In `resolveOperation(Statement)` (L199–L205), add `case DeleteStatement _ -> "DELETE";`.
- Add `private void executeDelete(DeleteStatement statement, Catalog snapshot, long started)` which emits nothing to `output` and throws `new UnsupportedOperationException("DELETE execution is not implemented yet")` as a clean front-end boundary stub to be replaced in Exercise 7 §4 (`execute(List<Statement>)` catches this `RuntimeException`, logs `statement_failed operation=DELETE reason=...` at `ERROR` with active `statementNumber`, and rethrows).
- Note: `executeQuery(String)` (L147) already requires a single `SelectStatement` and rejects `DELETE` with `IllegalArgumentException("expected exactly one SELECT statement")`.

### 4.7 Files Touched
| File | Nature |
|------|--------|
| [`src/main/antlr4/datasys/semi/sql/Sql.g4`](file:///workspaces/SeMi/src/main/antlr4/datasys/semi/sql/Sql.g4) | Add `delete` parser rule and `DELETE` lexer keyword |
| [`src/main/java/datasys/semi/models/DeleteStatement.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/models/DeleteStatement.java) *(new record)* | Immutable `DeleteStatement(tableName, where)` AST record |
| [`src/main/java/datasys/semi/models/Statement.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/models/Statement.java) | Add `DeleteStatement` to `permits` list |
| [`src/main/java/datasys/semi/parser/SqlAstBuilder.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/SqlAstBuilder.java) | Dispatch `delete` in `visitStatement`; implement `visitDelete` |
| [`src/main/java/datasys/semi/parser/SqlPrinter.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/SqlPrinter.java) | Add `DeleteStatement` branch and `printDelete` helper |
| [`src/main/java/datasys/semi/parser/Binder.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/parser/Binder.java) | Add `DeleteStatement` branches and shared `bindTableAndWhere` helper |
| [`src/main/java/datasys/semi/executor/Executor.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/executor/Executor.java) | Pin snapshot, bind `DeleteStatement`, map `"DELETE"` operation, stub `executeDelete` |
| [`src/test/java/datasys/semi/SqlParserTest.java`](file:///workspaces/SeMi/src/test/java/datasys/semi/SqlParserTest.java) | `DELETE` AST shapes, case-insensitivity, malformed syntax & overflow coordinates, record validation |
| [`src/test/java/datasys/semi/SqlPrinterTest.java`](file:///workspaces/SeMi/src/test/java/datasys/semi/SqlPrinterTest.java) | `DELETE` round-trip with/without `WHERE`, boundary literals, non-finite double rejection |
| [`src/test/java/datasys/semi/BinderIT.java`](file:///workspaces/SeMi/src/test/java/datasys/semi/BinderIT.java) | `DELETE` binding: valid cases, unknown table, unknown column, mistyped constants |
| [`src/test/java/datasys/semi/ExecutorTest.java`](file:///workspaces/SeMi/src/test/java/datasys/semi/ExecutorTest.java) | Front-end `DELETE` error logging, `stdout` silence, and `executeQuery` rejection |

## 5. Verification Plan

### 5.1 New & Updated Tests
| Test class | Test method(s) | Asserts |
|------------|----------------|---------|
| `SqlParserTest` | `parsesEachStatementShape` *(extend)* / `parsesDeleteWithAndWithoutWhere` | `DELETE FROM trips;` → `DeleteStatement("trips", Optional.empty())`; `DELETE FROM trips WHERE distance > 100;` → `DeleteStatement("trips", Optional.of(Predicate(...)))`; multi-statement script with `CREATE`, `COPY`, `DELETE`, `SELECT` (**Exercise 7 §5 Test 1**). |
| `SqlParserTest` | `deleteKeywordCaseInsensitiveAndKeywordPrefixIdentifier` | `dElEtE fRoM TriPs wHeRe CityName = 'Odense';` preserves `TriPs` and `CityName`; `DELETE FROM delete1 WHERE deleted_rows = 1;` accepts keyword-prefixed identifiers. |
| `SqlParserTest` | `malformedDeleteInputsAssertLineAndColumn` | `DELETE trips;`, `DELETE * FROM trips;`, `DELETE FROM;`, `DELETE FROM DELETE;`, `DELETE FROM trips WHERE;`, `DELETE FROM trips`, and `LONG` overflow in `DELETE ... WHERE` each throw `SqlParseException` with exact 1-based line and 0-based column. |
| `SqlParserTest` | `deleteStatementRejectsInvalidArguments` | Null/blank `tableName` or null `where` in `new DeleteStatement(...)` throws `IllegalArgumentException`. |
| `SqlPrinterTest` | `roundTripDeleteStatementsWithoutWhere` | `new DeleteStatement("trips", Optional.empty())` prints `"DELETE FROM trips;"` and round-trips (**Exercise 7 §5 Test 1**). |
| `SqlPrinterTest` | `roundTripDeleteStatementsWithWhereComparisonsAndTypes` | `DELETE` with `=`, `<`, `>` across `String`, `Long` (including `Long.MIN_VALUE`/`MAX_VALUE`), and `Double` (including `-0.0` and `Double.MIN_VALUE`/`MAX_VALUE`) prints plain SQL and round-trips (**Exercise 7 §5 Test 1**); `NaN`/`±Infinity` throw `IllegalArgumentException`. |
| `BinderIT` | `validStatementsBindCleanly` *(extend)* | `DELETE` without `WHERE` and `DELETE` with valid `STRING`, `LONG`, and `DOUBLE` predicates bind cleanly against `trips`. |
| `BinderIT` | `deleteUnknownTableThrowsIllegalArgumentException` | `DELETE` (with and without `WHERE`, and with null/mismatched snapshot) on a non-existent table throws `IllegalArgumentException` (**Exercise 7 §5 Test 2**). |
| `BinderIT` | `deleteUnknownColumnInWhereThrowsIllegalArgumentException` | `DELETE FROM trips WHERE missing_col = 1;` throws `IllegalArgumentException` (**Exercise 7 §5 Test 2**). |
| `BinderIT` | `deleteTypeMismatchedConstantInWhereThrowsIllegalArgumentException` | `LONG` column with `String`/`Double`, `STRING` column with `Long`, and `DOUBLE` column with `String`/`Long` in `DELETE` each throw `IllegalArgumentException` (**Exercise 7 §5 Test 2**). |
| `ExecutorTest` | `deleteBindingFailureLogsStatementFailedAndLeavesOutputEmpty` | Failing `DELETE` in a script throws `IllegalArgumentException`, logs `statement_failed operation=DELETE reason=...` at `ERROR` with active `statementNumber`, resets MDC to `"0"`, and writes 0 bytes to `output`. |
| `ExecutorTest` | `executeQueryRejectsDeleteStatement` | `executor.executeQuery("DELETE FROM trips;")` throws `IllegalArgumentException`. |
| `SqlCorpusIT` | *(existing 164 dynamic tests)* | All 77 good cases and 87 bad cases pass unchanged. |

### 5.2 Commands
```bash
# 1. Clean, generate ANTLR sources from Sql.g4, and compile all production code
mvn clean compile

# 2. Run all unit tests (Surefire) and integration tests (Failsafe) in batch mode
mvn -B verify
```
**Expected Outcome:** ANTLR regenerates `SqlLexer`, `SqlParser`, and `SqlVisitor` cleanly with the `DELETE` rule; all Surefire (`*Test`) and Failsafe (`*IT`) suites pass with **0 failures and 0 errors**.

## 6. Out of Scope (Exercise 7 §4)
- Copy-on-write partition rewriting in `StorageEngine` and replacing the `Executor.executeDelete` stub with real execution and `statement=DELETE ...` summary logging (§4).
- Exposing `Planner`'s partition-pruning helper for rewrite-set computation and carrying untouched/unmatched partitions by reference (§4).
- Per-version reader reference counting during retention cleanup (§4).
- End-to-end version progression (`CREATE`, `COPY`, `COPY`, `DELETE` → v0..v3), rewrite-set, restart, and CLI script integration tests (§5 Tests 3–6).

