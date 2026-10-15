# Exercise 4, Part 2 — The Planner and Executor

## 1. Sub-Exercise Reference

[`exercise_descriptions/Exercise4.md`](file:///workspaces/datsys-engine-SeMi/exercise_descriptions/Exercise4.md) §2 *The planner and executor*, along with the required unit tests 3 and 4 and integration test 5 from §6.

## 2. Background & Objective

Exercise 4 Part 1 implemented `ScanOperator` and `FilterOperator`, but partition pruning remained in `StorageEngine.select(...)`. 

In Part 2, partition pruning moves into a dedicated `Planner`. The planner turns a bound `SelectStatement` into an operator plan before any data file is opened. If a `WHERE` predicate exists, the planner evaluates min/max partition summaries from the catalog, emits `decision=READ|PRUNED` log lines, and constructs `FilterOperator` over a `ScanOperator` with the surviving partitions. Without `WHERE`, all partitions survive and the plan is a bare `ScanOperator`.

`StorageEngine.select(...)` is refactored to plan and drain the operator pipeline internally, retaining its Exercise 2 public signature so that existing integration tests pass unchanged.

An `Executor` is introduced to orchestrate the pipeline `parse → bind → plan → execute` statement-by-statement, stopping at the first error and keeping the statement counter in the SLF4J MDC (`statementNumber`).

## 3. Requirements & Invariants

1. **Planner Pruning**: For `SELECT ... WHERE`, partition pruning occurs in `Planner` before data files are opened, using min/max summaries and `StorageEngine.shouldPrune(...)`.
2. **Log Migration**: The `decision=READ|PRUNED` log lines move from `StorageEngine` to `Planner`, showing `className=Planner` in the CSV log.
3. **ScanStats Population**: `Planner` calculates and records `ScanStats` (`partitionsTotal`, `partitionsRead`, `partitionsPruned`).
4. **Plan Shapes**:
   - `SELECT` with `WHERE` yields `FilterOperator(ScanOperator(table, survivingPartitions), boundPredicate)`.
   - `SELECT` without `WHERE` yields bare `ScanOperator(table, allPartitions)`.
5. **StorageEngine.select Refactor**: Keeps its week 2 signature, delegates to `Planner`, drains the pipeline, and updates `lastScanStats`. All Exercise 2 integration tests pass untouched.
6. **Executor Orchestration**: `Executor` runs `parse → bind → plan → execute`, statement by statement.
7. **Statement Counting**: `Executor` increments `statementNumber` in MDC (first statement is 1) and restores it to `"0"` when the script finishes or halts on error.
8. **Error Halting**: First error halts execution immediately, logging `LOGGER.error(...)`.

## 4. Proposed Changes

### `datasys.semi.planner.Planner`
- Construct with `StorageEngine`.
- Method `public Operator plan(SelectStatement statement)`.
- Method `public ScanStats lastScanStats()`.

### `datasys.semi.executor.Executor`
- Construct with `StorageEngine` and optional `PrintStream` (default `System.out`).
- Method `public void execute(String sql)`.
- Method `public void execute(List<Statement> statements)`.
- Method `public List<Object[]> executeQuery(String sql)`.
- Handles `CreateTableStatement`, `CopyStatement`, `SelectStatement`.

### `datasys.semi.engine.StorageEngine`
- Add `public List<Partition> partitions(String tableName)`.
- Widen `parseStatistic(Object, ColumnType)` to `public static`.
- Refactor `select(String, String, Comparison, Object)` to plan and drain an operator pipeline.
- Add `public List<Object[]> select(SelectStatement statement)`.
- Remove now-unused partition scan loop and `readMatchingRows(...)`.

### `datasys.semi.operators.FilterOperator` & `ScanOperator`
- Add public accessors (`FilterOperator.child()`, `FilterOperator.predicate()`, `ScanOperator.partitionNumbers()`, `ScanOperator.tableName()`) to inspect plan shape in unit tests.

### Tests
- `PlannerTest`: §6 test 3 (pruning on sorted golden data) and §6 test 4 (plan shapes).
- `ExecutorTest`: multi-statement execution, MDC statement counting, error stopping.

## 5. Verification Plan

```bash
mvn clean test
mvn verify
```
- All unit tests and integration tests pass with 0 errors and 0 failures.
- Pre-completion checklist in `AGENTS.md` fully satisfied.

