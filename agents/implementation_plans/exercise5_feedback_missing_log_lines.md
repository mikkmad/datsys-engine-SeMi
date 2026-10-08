# Implementation Plan: Streamlined Fix for Issue #40 (Missing Log Lines)

**Reference**: [exercise_descriptions/Exercise5.md](file:///workspaces/SeMi/exercise_descriptions/Exercise5.md) & GitHub Issue #40

---

## 1. Background & Objective

GitHub Issue #40 details specific feedback from the course evaluation regarding missing logging lines and unformatted raw values:
1. `Planner.planWithoutPredicate` produces no decision lines; it must log `table={table} partition={partition} decision=READ reason=noPredicate` for each partition.
2. In `Planner.prunePartitions`, when `statistics == null`, the partition is kept silently; it must log `table={table} partition={partition} decision=READ reason=missingStats`.
3. In `Executor`, statement failure currently only logs the statement sequence number without error context while the statement number is active; it must log `statement_failed operation={operation} reason={cause}` at `ERROR` level before reverting MDC.
4. Raw values (file paths, constants, min/max statistics, exception messages) must be sanitized of CSV delimiters (`,`, `"`, `\r`, `\n`) to preserve the 7-column engine CSV log schema.
5. Dogfooding log ingestion (`COPY logs FROM 'logs/engine.log'`) must succeed reliably without failing on uninitialized or blank numeric columns.

**Goal**: Implement the required fixes cleanly and concisely, keeping the total changes under ~180 lines without unnecessary bloat, redundant test repetitions, or untouched-method modifications.

---

## 2. Invariants & Requirements

1. **CSV Log Schema Integrity**: Every log line must conform to:
   `timestamp,sessionId,statementNumber,threadId,logLevel,className,logMessage`
   with 7 columns and no unescaped commas, double quotes, or newlines.
2. **Strict Log Levels**: Use exclusively `LOGGER.debug` and `LOGGER.error`.
3. **Active Statement Context**: Statement failure errors must be emitted while the failing statement's sequence number (`1`, `2`, etc.) is active in MDC.
4. **Untouched Tests**: Existing integration tests (`SqlCorpusIT`, `StorageEngineIT`) remain untouched and green.
5. **Clean Diff**: Keep changes minimal, focused solely on Issue #40 requirements.

---

## 3. Proposed Changes

### A. Utility: `LogSanitizer.java` (~25 lines)
- **File**: `src/main/java/datasys/semi/util/LogSanitizer.java`
- Implement a single static method: `sanitize(Object value)`:
  - If null, return `""`.
  - Replaces `,` with space, `"` with `'`, and `\r`, `\n` with space.

### B. Planner: `Planner.java` (~15 lines)
- **`planWithoutPredicate`**: Emit `LOGGER.debug("table={} partition={} decision=READ reason=noPredicate", tableName, index)` for each partition.
- **`prunePartitions`**: In the `statistics == null` branch before `continue`, emit `LOGGER.debug("table={} partition={} decision=READ reason=missingStats", tableName, partitionNumber)`.
- Sanitize `predicate.constant()`, `statistics.min`, `statistics.max` in the partition decision log.
- Ensure MDC `statementNumber` defaults to `"0"` in constructor if not present.

### C. Executor: `Executor.java` (~25 lines)
- **Production Caller Audit**: `executeQuery(String)` has **zero callers in production** (`src/main`). The CLI entry point (`Engine.java`) dispatches exclusively via `executor.execute(...)`. Existing tests (`StorageEngineIT`) call `executeQuery`, so it remains untouched for test compatibility.
- In `execute(List<Statement>)`: Wrap each statement execution in a `try-catch`; on `RuntimeException`, log:
  `LOGGER.error("statement_failed operation={} reason={}", resolveOperation(statement), LogSanitizer.sanitize(exception.getMessage()))`
  with the active statement number in MDC, then rethrow.
- Helper `resolveOperation(Statement)` mapping statement instances to `"CREATE_TABLE"`, `"COPY"`, or `"SELECT"`.
- Ensure MDC `statementNumber` defaults to `"0"` if unset.

### D. Engine: `Engine.java` (~5 lines)
- Sanitize `exception.getMessage()` in `LOGGER.error("Engine execution failed: {}", ...)`.
- In `finally`, replace `MDC.clear()` with `MDC.remove("sessionId")` and retain `statementNumber = "0"`.

### E. StorageEngine & Operators (~15 lines)
- `StorageEngine.java`: Sanitize `file`, `constant`, `min`, `max`, and partition paths in debug/error logs.
- Defensively handle blank LONG values in `parseValue`: `case LONG -> (value == null || value.isBlank()) ? 0L : Long.parseLong(value.trim())`.
- `FilterOperator.java`: Sanitize `predicate.constant()` in `close()`.

### F. Tests (~55 lines total)
- `LogSanitizerTest.java`: Compact unit test covering null, delimiters, newlines, and objects via `@ParameterizedTest` (~25 lines).
- `PlannerTest.java`: 2 tests verifying `noPredicate` and `missingStats` decision log lines (~25 lines).
- `ExecutorTest.java`: 1 test verifying `statement_failed operation=... reason=...` with active statement number on `execute(sql)` (~12 lines). (No redundant test for `executeQuery` since it has no production callers).
- `EngineLogIT.java`: Assert the enriched error line format in the existing failing script test (~5 lines).

---

## 4. Verification Plan

1. **Compilation**:
   ```bash
   mvn clean compile
   ```
2. **Unit & Integration Test Suite**:
   ```bash
   mvn -B test
   mvn -B verify
   ```
3. **Dogfood Log Verification**:
   Package and execute engine dogfooding:
   ```bash
   mvn package
   ./engine -c "CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG, logLevel STRING, className STRING, logMessage STRING); COPY logs FROM 'logs/engine.log'; SELECT * FROM logs WHERE logLevel = 'ERROR';"
   ```

