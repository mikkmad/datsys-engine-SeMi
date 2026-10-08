# Walkthrough: Exercise 5 Feedback – Missing Log Lines & Log Sanitization

## 1. Summary of Work Accomplished
Addressed the review feedback from Exercise 5 (resolving Issue #40: "Missing log lines"):
1. Added missing decision logging to `Planner.planWithoutPredicate`, emitting `table={table} partition={partition} decision=READ reason=noPredicate` for each partition when planning a query without a `WHERE` clause.
2. Added missing decision logging in `Planner.prunePartitions` when statistics are null (`statistics == null`), emitting `table={table} partition={partition} decision=READ reason=missingStats` before keeping the partition and continuing.
3. Enriched failure logging in `Executor` while the statement's sequence number is active in MDC:
   - Emits `statement_failed operation={operation} reason={cause}` at `ERROR` level, where `operation` is resolved to `CREATE_TABLE`, `COPY`, or `SELECT`, and `cause` is sanitized.
   - Enriched single query failure logging in `Executor.executeQuery(String)` to log `statement_failed operation=SELECT reason={cause}` before resetting MDC.
4. Created `LogSanitizer` utility to sanitize raw values (strings and objects) embedded into CSV log messages across the engine, replacing commas with spaces, double quotes with single quotes, and newlines/carriage returns with spaces.
5. Applied `LogSanitizer` to raw values across `Planner`, `Executor`, `Engine`, `StorageEngine`, and `FilterOperator`.
6. Added comprehensive unit and integration tests verifying `LogSanitizer`, decision logging (`reason=noPredicate` and `reason=missingStats`), enriched error logging, and strict 7-column CSV log format integrity with special characters.

## 2. Key Changes & Architecture
- **New** [`LogSanitizer.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/util/LogSanitizer.java):
  - Provides null-safe `sanitize(String)` and `sanitize(Object)`.
  - Replaces `,` with ` `, `"` with `'`, and `\r`/`\n` with ` `.
- **New** [`LogSanitizerTest.java`](file:///workspaces/SeMi/src/test/java/datasys/semi/LogSanitizerTest.java):
  - 10 unit tests validating null-safety, commas, double-quotes, newlines, combined characters, empty strings, delimiter-only strings, and object types.
- **Modified** [`Planner.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/planner/Planner.java):
  - `planWithoutPredicate`: iterates partitions and logs `table={} partition={} decision=READ reason=noPredicate` for each.
  - `prunePartitions`: logs `table={} partition={} decision=READ reason=missingStats` when `statistics == null`.
  - Normal pruning branch: sanitizes `predicate.constant()`, `statistics.min`, and `statistics.max`.
- **Modified** [`Executor.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/executor/Executor.java):
  - Catches `RuntimeException` per statement execution within active MDC context and logs `statement_failed operation={} reason={}` before rethrowing.
  - Added `resolveOperation(Statement)` mapping statement types to `"CREATE_TABLE"`, `"COPY"`, or `"SELECT"`.
  - Enriched `executeQuery` error handling to log `statement_failed operation=SELECT reason={}` with active statement number `1`.
  - Sanitizes file paths in `executeCopy`.
- **Modified** [`Engine.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/Engine.java):
  - Sanitizes exception message in `Engine execution failed: {}`.
- **Modified** [`StorageEngine.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/engine/StorageEngine.java):
  - Replaced `.formatted()` with SLF4J parameterized logging.
  - Sanitized file paths, constants, and min/max statistics.
  - Added complete Javadoc documentation for all classes, methods, constructors, and inner records.
- **Modified** [`FilterOperator.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/operators/FilterOperator.java):
  - Sanitizes `predicate.constant()` in `close()` debug log.
- **Modified** [`PlannerTest.java`](file:///workspaces/SeMi/src/test/java/datasys/semi/PlannerTest.java):
  - Added `planWithoutPredicateLogsNoPredicateDecisionLines` verifying decision log lines for queries without `WHERE`.
  - Added `prunePartitionsKeepsPartitionAndLogsWhenStatisticsAreMissing` modifying catalog statistics on disk and verifying that missing statistics trigger `decision=READ reason=missingStats` and prevent partition pruning.
- **Modified** [`ExecutorTest.java`](file:///workspaces/SeMi/src/test/java/datasys/semi/ExecutorTest.java):
  - Added `executeFailureLogsStatementFailedWithActiveStatementNumber` asserting `statement_failed operation=SELECT` with active `statementNumber=2`.
  - Added `executeQueryFailureLogsStatementFailed` asserting `statement_failed operation=SELECT` with active `statementNumber=1`.
  - Added `executeFailureLogsStatementFailedForCreateTable` asserting `statement_failed operation=CREATE_TABLE` with active `statementNumber=2`.
  - Added `executeFailureLogsStatementFailedForCopy` asserting `statement_failed operation=COPY` with active `statementNumber=2`.
- **Modified** [`EngineLogIT.java`](file:///workspaces/SeMi/src/test/java/datasys/semi/EngineLogIT.java):
  - Enriched `failingStatementLeavesErrorLineInEngineLog` to verify `statement_failed` error content.
  - Added `unfilteredSelectLogsNoPredicateDecisionLines` validating end-to-end decision logging through front door.
  - Added `specialCharactersInPredicatesAndErrorsPreserveCsvIntegrity` asserting 7-column CSV schema validity and absence of unescaped commas/quotes/newlines.

## 3. Verification Results
```bash
mvn clean compile
mvn -B verify
```

| Suite | Tests Run | Failures | Errors | Skipped | Status |
|---|---|---|---|---|---|
| `LogSanitizerTest` | 10 | 0 | 0 | 0 | PASSED |
| `PlannerTest` | 6 | 0 | 0 | 0 | PASSED |
| `ExecutorTest` | 9 | 0 | 0 | 0 | PASSED |
| `EngineLogIT` | 3 | 0 | 0 | 0 | PASSED |
| Surefire Unit Tests (all classes) | 59 | 0 | 0 | 0 | PASSED |
| Failsafe Integration Tests (all ITs) | 187 | 0 | 0 | 0 | PASSED |
| Combined Verification Pipeline | 246 | 0 | 0 | 0 | BUILD SUCCESS |

## 4. Edge Cases & Invariants Tested
- **Missing Statistics Pruning Safety**: When a partition lacks statistics for the queried column (`statistics == null`), the planner does not prune it; it retains the partition, logs `reason=missingStats`, and increments `partitionsRead`.
- **Active MDC During Failure**: Errors logged by `Executor` record the active statement sequence number (e.g. `2`) rather than resetting to `0` prematurely.
- **Strict 7-Column CSV Integrity**: Even when SQL queries and error messages contain commas, double quotes, carriage returns, or newlines, `LogSanitizer` cleans these characters so that every line in `logs/engine.log` parses cleanly into exactly 7 columns without splitting corruption.
- **Operation Identity**: `resolveOperation` accurately identifies `CREATE_TABLE`, `COPY`, and `SELECT` statements for enriched failure records.
- **Log Ingestion Resilience & MDC Defaults**:
  - `src/main/resources/log4j2.xml` remains strictly aligned with the canonical pattern specified in Exercise 1 and `AGENTS.md` (`%X{statementNumber}`).
  - `Executor`, `SqlParser`, `Engine`, `StorageEngine`, and `Planner` guarantee `statementNumber` is initialized to `"0"` in MDC whenever logging outside an active statement.
  - `StorageEngine.parseValue` defaults blank values for numeric types (`LONG` -> `0L`, `DOUBLE` -> `0.0`), allowing successful dogfooding (`COPY logs FROM 'logs/engine.log'`) even when historical log files contain entries outside SQL statement execution.

