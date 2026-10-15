# Exercise 4, Part 2 — The Planner and Executor: Walkthrough

## 1. Summary of Work Accomplished

In Exercise 4 §2, we refactored the query planning and execution architecture to decouple partition pruning from disk I/O and row filtering:

1. **Planner (`Planner.java`)**: Created a dedicated query planner that analyzes a bound `SelectStatement` against catalog partition summaries, performs partition pruning prior to opening any data files, logs `decision=READ|PRUNED` lines under logger name `Planner`, populates `ScanStats`, and constructs Volcano operator trees (`FilterOperator` over `ScanOperator` when `WHERE` is present, bare `ScanOperator` over all partitions when no `WHERE` is present).
2. **StorageEngine Refactoring (`StorageEngine.java`)**: Refactored `StorageEngine.select(...)` to plan through `Planner` and drain an operator pipeline internally, retaining the week 2 public API signature and updating `lastScanStats`. Removed legacy partition reading loops and `readMatchingRows(...)`. Exposed catalog partition metadata through `public List<Partition> partitions(String tableName)` and widened `parseStatistic(...)` to `public static`.
3. **Executor (`Executor.java`)**: Implemented the SQL orchestrator executing `parse → bind → plan → execute`, statement by statement. Managed statement sequence counting via SLF4J MDC (`statementNumber` initialized to 1 for the first statement and reset to `0` upon completion or failure). Dispatches DDL (`CREATE TABLE`) and `COPY` directly to storage, and drains `SELECT` pipelines emitting headerless CSV to standard output (or a configured `PrintStream`).
4. **Operator Inspection**: Extended `FilterOperator` with `child()` and `predicate()` accessors, and `ScanOperator` with `partitionNumbers()` and `tableName()` accessors to enable structural plan assertions.
5. **Testing**: Implemented `PlannerTest` covering Required Unit Tests 3 and 4 (sorted golden data pruning and plan shapes), and `ExecutorTest` validating multi-statement script execution, MDC statement counting, and halt-on-first-error semantics. Existing Exercise 2 integration tests (`StorageEngineIT`) passed completely untouched.

---

## 2. Key Changes & Architecture

### Class Architecture

```
SqlParser (parse) ──► Binder (bind) ──► Planner (prune & plan) ──► Volcano Pipeline (open/next/close)
                                             │                               │
                                             ▼                               ▼
                                   StorageEngine (Catalog)             ScanOperator ──► FilterOperator
```

### Modified and Created Components

| Component | Role | Changes |
|---|---|---|
| [`Planner.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/planner/Planner.java) | Query Planner & Pruner | Compares predicate against partition min/max summaries; emits `decision=READ\|PRUNED` log entries with `className=Planner`; builds `ScanOperator` and optional `FilterOperator`; records `ScanStats`. |
| [`StorageEngine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/engine/StorageEngine.java) | Catalog & File Engine | Added `partitions(tableName)`; widened `parseStatistic(...)`; refactored `select(...)` to plan and drain operator pipelines; removed obsolete `readMatchingRows(...)`. |
| [`Executor.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/executor/Executor.java) | Statement Orchestrator | Wires `parse → bind → plan → execute`; tracks `statementNumber` in MDC; formats CSV rows; terminates and logs on error. |
| [`FilterOperator.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/operators/FilterOperator.java) | Row Filter | Added `child()` and `predicate()` getters. |
| [`ScanOperator.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/operators/ScanOperator.java) | Partition Reader | Added `partitionNumbers()` and `tableName()` getters. |
| [`PlannerTest.java`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/PlannerTest.java) | Planner Unit Tests | Validates sorted golden data pruning (§6 Test 3) and plan tree shapes (§6 Test 4). |
| [`ExecutorTest.java`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/ExecutorTest.java) | Executor Unit Tests | Validates multi-statement scripts, CSV output, MDC statement numbers, and halt-on-error behavior. |

---

## 3. Verification Results

### `mvn test` (Surefire Unit Tests)
```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running datasys.semi.EngineTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running datasys.semi.ExecutorTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running datasys.semi.FilterOperatorTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running datasys.semi.PlannerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running datasys.semi.ScanOperatorTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running datasys.semi.SqlParserTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running datasys.semi.SqlPrinterTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running datasys.semi.StorageEngineUnitTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

### `mvn -B verify` (Integration Tests via Failsafe)
```
[INFO] Running datasys.semi.BinderIT
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running datasys.semi.StorageEngineIT
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] --- failsafe:3.5.6:verify (default) @ engine ---
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

---

## 4. Edge Cases & Invariants Tested

1. **Pruning with `maxRowsPerPartition = 2` on Sorted Data**: Tested with `trips_sorted.csv` and selective filter `distance > 200L`. Pruning correctly skipped 3 out of 4 partitions before any reading started (`partitionsTotal = 4`, `partitionsRead = 1`, `partitionsPruned = 3`).
2. **Plan Shape Verification**:
   - `SELECT * ... WHERE ...` produced `FilterOperator` wrapping `ScanOperator`, matching the resolved column index, comparison, and constant.
   - `SELECT * ...` without `WHERE` produced bare `ScanOperator` containing all partition indices `[0, 1, 2, 3]`.
3. **Fully Pruned Query**: Tested `distance > 500L`. Yielded `partitionsRead = 0`, `partitionsPruned = 4`, and `ScanOperator` with an empty partition number list (`[]`), opening 0 data files.
4. **MDC Statement Counter Lifecycle**: Verified in `ExecutorTest` that `statementNumber` is set to `1, 2, ...` during statement execution and restored to `"0"` after normal completion as well as when an exception occurs.
5. **Error Halting**: Verified that a failing statement stops script execution immediately, logs an `ERROR` entry, resets `statementNumber` to `"0"`, and leaves subsequent statements unexecuted.
6. **Exercise 2 Backward Compatibility**: All 9 integration tests in `StorageEngineIT` passed unchanged, verifying that the move of pruning and pipeline draining preserves the engine's public semantics and observable `ScanStats`.

