# Walkthrough — Exercise 11.5: Estimates and Actuals, Side by Side in the Log

Implementation plan: [`exercise11_5_estimates_and_actuals_in_log.md`](../implementation_plans/exercise11_5_estimates_and_actuals_in_log.md)
Specification: [`exercise_descriptions/Exercise11.md` §5](../../exercise_descriptions/Exercise11.md)

## 1. Summary of Work Accomplished

Every executed hash join now writes **one** log line with both the planner's estimate and the actual row counts,
and every multi-table `SELECT` logs its chosen join order and mode once. All lines carry the statement's
`statementNumber` through the MDC, so `estRows` vs. `rowsOut` (the Part 3 estimate error) is recoverable per join
and per statement from the CSV log alone.

```
statementNumber,level,className,logMessage
1,DEBUG,Planner,statement=SELECT joinOrder=products+orders+customers+cities mode=optimized estRows=1
1,DEBUG,HashJoinOperator,operator=HashJoin join=3 tables=products+orders+customers+cities estRows=1 buildKey=7 probeKey=0 rowsBuild=4 rowsProbe=2 rowsOut=4
1,DEBUG,HashJoinOperator,operator=HashJoin join=2 tables=products+orders+customers estRows=2 buildKey=3 probeKey=0 rowsBuild=4 rowsProbe=3 rowsOut=4
1,DEBUG,HashJoinOperator,operator=HashJoin join=1 tables=products+orders estRows=2 buildKey=0 probeKey=2 rowsBuild=2 rowsProbe=4 rowsOut=4
```

(The join lines appear root-first because `close()` runs top-down; `join=` restores plan order.)

## 2. Key Changes & Architecture

| Component | Change |
|-----------|--------|
| `operators/JoinEstimate` *(new record)* | `(joinNumber, tableNames, estimatedRows)`. Validates (join number ≥ 1, at least two named tables, finite non-negative estimate), copies the names, `describeTables()` joins them with `+` (the log is CSV, AGENTS.md §5.5), `roundedRows()` rounds only for the log (design doc: estimates stay `double` through a chain). Lives in `operators` so the operator package does not depend on `optimizer`. |
| `operators/HashJoinOperator` | Constructor takes a mandatory fifth argument `JoinEstimate` — a join without an estimate cannot be built, so every executed join logs one. `close()` logs `operator=HashJoin join tables estRows buildKey probeKey rowsBuild rowsProbe rowsOut`; accessor `estimate()`. |
| `planner/Planner` | `planJoins` creates join `n`'s `JoinEstimate` from step `n`'s `estimatedRows` and the first `n + 1` tables of the join order (via a small `JoinInputs` record to keep `planJoin` short). `plan` calls `logJoinOrder`, which logs `statement=SELECT joinOrder=… mode=… estRows=…` (final join's estimate) for multi-table statements only; single-table statements keep their old log. |

Design choices:
- **`tables=` in join order**, not build/probe order, so each join line's tables are a prefix of the `joinOrder=`
  line of the same statement.
- **Both modes log the same lines.** Under `-j syntax` the estimates are the ones 11.4 already computes for the
  `FROM`-list order, so week 12 can compare forced and optimized plans from the log.
- Plan shapes are unchanged; the estimate is metadata only.

## 3. Verification Results

```
mvn clean compile        -> BUILD SUCCESS
mvn -B clean verify      -> Surefire: Tests run: 201, Failures: 0, Errors: 0, Skipped: 0
                            Failsafe: Tests run: 205, Failures: 0, Errors: 0, Skipped: 0
                            BUILD SUCCESS
```

## 4. Edge Cases & Invariants Tested

| Test | What it shows |
|------|---------------|
| `HashJoinOperatorTest.closeLogsEstimateNextToRowsBuildRowsProbeRowsOut` | The close line carries `join=1 tables=build+probe estRows=3` (2.6 rounded) next to `rowsBuild=4 rowsProbe=3 rowsOut=3`. |
| `HashJoinOperatorTest.joinEstimateRoundsForTheLogAndRejectsInvalidValues` | Exact value kept (1.5), rounded to 2 for the log, `+`-joined names; join number 0, one table, null/blank names, negative and NaN estimates rejected. |
| `HashJoinOperatorTest.invalidArgumentsAndOutOfRangeKeysThrow` | A `null` estimate is rejected like a `null` child. |
| `JoinPlannerTest.plannedJoinsCarryTheOrdererEstimates` | Syntax four-table chain: joins 1–3 carry 3.0, 1.5, 1.0 (hand-computed from sum-rule NDVs) and their table prefixes, unrounded. |
| `JoinPlannerTest.optimizedJoinsLogEstimateAndActualPerStatement` (§8.6 log part) | Three-statement script under `optimized`: statements 1 and 2 log one `joinOrder` line and one line per executed join with `estRows` and `rowsOut`, each under its own `statementNumber`; statement 3 (single table) logs neither. |
| `JoinPlannerTest.syntaxJoinsLogEstimateAndActualPerStatement` (§8.6 log part) | Same under `syntax`: `FROM`-list order and the syntax plan's estimates (3, 2, 1 vs. actual 4, 4, 4). |
| `JoinPlannerTest.joinedQueryLogsEveryJoinAndOneSummaryPerTable` | Updated to the new line format; the per-table `statement=SELECT table=` summaries are unchanged. |

Not covered here (§6, next sub-exercise): the metamorphic test that runs the testbed under both flags and reads
from the log that the plans differ.
