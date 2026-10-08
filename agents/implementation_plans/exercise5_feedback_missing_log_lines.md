# Implementation Plan: Exercise 5 Feedback – Missing Log Lines & Log Sanitization

## 1. Sub-Exercise Reference
Exercise 5: Reading the Log, Owning the Code, Designing the Experiment.
See [`exercise_descriptions/Exercise5.md`](../../exercise_descriptions/Exercise5.md).
Resolves GitHub Issue #40: "Missing log lines".

## 2. Background & Objective
Per the weekly feedback review for Exercise 5:
1. `Planner.planWithoutPredicate` produces no decision lines when scanning all partitions of a table without a `WHERE` clause. It should log `decision=READ reason=noPredicate` for each partition.
2. In `Planner.prunePartitions`, when partition statistics are missing (`statistics == null`), the planner silently keeps the partition and continues without logging. It should log `table={table} partition={partition} decision=READ reason=missingStats`.
3. `Executor` currently logs only the statement number on failure (`Execution failed at statementNumber=N`) and leaves the detailed cause to `Engine` at statement number `0`. Instead, `Executor` must enrich its `ERROR` log while the statement's sequence number is active in MDC, with the format: `statement_failed operation={operation} reason={cause}` where `operation` is `CREATE_TABLE`, `COPY`, or `SELECT`, and `cause` is sanitized.
4. Raw values throughout logging (predicates, constants, statistics min/max, file paths, error messages) may contain unescaped commas, double quotes, or newlines, which violates the strict 7-column CSV log format. A dedicated utility `LogSanitizer` must be introduced to sanitize all such raw values across `Planner`, `Executor`, `Engine`, `StorageEngine`, and `FilterOperator`.

## 3. Requirements & Invariants
- **Log Format Invariant**: Every log line written to `logs/engine.log` must conform to the 7-column CSV schema: `timestamp,sessionId,statementNumber,threadId,logLevel,className,logMessage`. Log messages must never contain unescaped commas, double quotes, newlines, or carriage returns.
- **Log Levels**: Exactly two log levels are permitted: `DEBUG` for normal operational flow and `ERROR` for failures.
- **Decision Logging**:
  - In `Planner.planWithoutPredicate`: For every partition `index` from 0 to `partitions.size() - 1`, emit `table={tableName} partition={index} decision=READ reason=noPredicate` at `DEBUG`.
  - In `Planner.prunePartitions`: When `statistics == null`, emit `table={tableName} partition={partitionNumber} decision=READ reason=missingStats` at `DEBUG`.
  - In standard pruning branch: Sanitize `predicate.constant()`, `statistics.min`, and `statistics.max`.
- **Executor Error Enrichment**:
  - In `Executor.execute(List<Statement>)`: When `executeStatement(statement)` fails, catch the `RuntimeException`, log `statement_failed operation={operation} reason={sanitizedCause}` at `ERROR` while `statementNumber` in MDC is still active, and rethrow.
  - In `Executor.executeQuery(String sql)`: When execution fails, log `statement_failed operation=SELECT reason={sanitizedCause}` at `ERROR` while MDC `statementNumber` is `"1"`, and rethrow.
- **Log Sanitizer Utility**:
  - `LogSanitizer` in package `datasys.semi.util`.
  - Null-safe: returns empty string `""` on null input.
  - Replaces commas `,` with space ` `, double quotes `"` with single quotes `'`, carriage returns `\r` with space ` `, and newlines `\n` with space ` `.
  - Overloaded for `String` and `Object`.
- **Code Standards**:
  - Java 25, 4-space indentation.
  - Descriptive variable names, grouped class members with section comments and blank lines.
  - Short, focused methods (10–20 lines) with guard clauses.
  - Complete Javadoc comments for all classes, methods, and constructors (`@param`, `@return`, `@throws`).

## 4. Proposed Changes
- **New** `src/main/java/datasys/semi/util/LogSanitizer.java`:
  - Static utility class with `sanitize(String)` and `sanitize(Object)`.
- **New** `src/test/java/datasys/semi/LogSanitizerTest.java`:
  - Unit tests covering null handling, comma replacement, double-quote replacement, newline replacement, combined illegal characters, and `Object` conversion.
- **Modify** `src/main/java/datasys/semi/planner/Planner.java`:
  - In `planWithoutPredicate`: emit `table={tableName} partition={index} decision=READ reason=noPredicate` for each partition.
  - In `prunePartitions`: emit `table={tableName} partition={partitionNumber} decision=READ reason=missingStats` when `statistics == null`.
  - In `prunePartitions`: sanitize `predicate.constant()`, `statistics.min`, and `statistics.max` using `LogSanitizer.sanitize(...)`.
- **Modify** `src/main/java/datasys/semi/executor/Executor.java`:
  - In `execute(List<Statement>)`: catch failure per statement, log `statement_failed operation={operation} reason={sanitizedReason}`, then rethrow.
  - In `executeQuery(String)`: catch failure, log `statement_failed operation=SELECT reason={sanitizedReason}`, then rethrow.
  - Helper method `resolveOperation(Statement)` returning `"CREATE_TABLE"`, `"COPY"`, or `"SELECT"`.
  - Sanitize `csvFilePath` in `executeCopy`.
- **Modify** `src/main/java/datasys/semi/Engine.java`:
  - Sanitize `exception.getMessage()` in `LOGGER.error("Engine execution failed: {}", LogSanitizer.sanitize(exception.getMessage()))`.
- **Modify** `src/main/java/datasys/semi/engine/StorageEngine.java`:
  - Convert formatted string loggers to SLF4J parameterized logging.
  - Sanitize raw values: file paths, constants, min/max statistics.
- **Modify** `src/main/java/datasys/semi/operators/FilterOperator.java`:
  - Sanitize `predicate.constant()` in `close()` debug logging.
- **Modify** `src/test/java/datasys/semi/PlannerTest.java`:
  - Add tests verifying decision log lines for queries without WHERE clauses (`reason=noPredicate`) and partitions with missing statistics (`reason=missingStats`).
- **Modify** `src/test/java/datasys/semi/ExecutorTest.java`:
  - Add tests verifying `statement_failed operation=... reason=...` error logging on statement failure.
- **Modify** `src/test/java/datasys/semi/EngineLogIT.java`:
  - Extend integration tests to verify CSV integrity with special characters and verify `statement_failed` error lines.

## 5. Verification Plan
```bash
# 1. Clean and compile
mvn clean compile

# 2. Run unit tests
mvn test

# 3. Run full integration verification
mvn -B verify
```
Expected: All tests pass with 0 failures and 0 errors, validating the 7-column CSV schema, decision logging, and enriched failure logging.

