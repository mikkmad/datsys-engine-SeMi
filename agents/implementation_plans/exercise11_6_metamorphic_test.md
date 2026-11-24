# Implementation Plan — Exercise 11.6: The Metamorphic Test

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise11.md` §6 "The metamorphic test"](../../exercise_descriptions/Exercise11.md),
  required test §8.6
- Prerequisites: [`exercise11_4_greedy_join_planner.md`](exercise11_4_greedy_join_planner.md) (`-j syntax` /
  `-j optimized`), [`exercise11_5_estimates_and_actuals_in_log.md`](exercise11_5_estimates_and_actuals_in_log.md)
  (`joinOrder` line and one `estRows`/`rowsOut` line per join),
  [`exercise10_5_testbed_duckdb_integration.md`](exercise10_5_testbed_duckdb_integration.md) (`JoinTestbedIT`)

## 2. Background & Objective

The optimizer rearranges the plan; the result must not change. A metamorphic test checks exactly that relation
without needing a new oracle: the same query under two plans must return the same rows. Because a test that
compares a plan with itself would pass trivially, it must also show, from the log, that the two plans differ.

**Objective:** run the week 10 testbed queries with three and four tables through the packaged engine
(`./engine -j syntax -f …` and `./engine -j optimized -f …`) and assert

1. the sorted result rows are identical,
2. the plans, read from each run's log, are different,
3. every executed join left a log line with `estRows` and `rowsOut` (§8.6).

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | Queries: the testbed's `query2` (3 tables), `query3` (4 tables) and `query4` (3 tables with a filter), read from the same `joins/queries/*.sql` files as the DuckDB oracle test. `query1` has two tables and is out of the §6 scope. |
| F2 | Each mode runs as its own child process (`./engine -j <mode> -f script`) in its own working directory, so each run has its own `logs/engine.log` and data directory; no log lines from other tests can interfere. |
| F3 | Result relation: sorted stdout under `syntax` equals sorted stdout under `optimized`, and is non-empty. |
| F4 | Plan from the log: the SELECT's `statement=SELECT joinOrder=… mode=…` line. Each run must carry exactly one, with its own `mode=`; the two `joinOrder=` values must differ. |
| F5 | Join lines: for the SELECT's `statementNumber`, one `operator=HashJoin` line per join (tables − 1), numbered `join=1..n`, each with `estRows=` and `rowsOut=`. |
| F6 | The plan difference is a precondition the test asserts, not assumes: if a future estimator change made both modes pick the `FROM` order, the test fails and the query set must be revisited. |

### Non-functional
- Reuse `JoinTestbedIT`'s script and process helpers instead of copying them; `runEngine` gains the extra
  `-j <mode>` arguments.
- Log lines are parsed by the 7-column CSV schema (Exercise 1 §3); the message is the last field.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `test/.../JoinTestbedIT.java` | `runEngine(workDirectory, script, engineArguments...)` passes extra CLI arguments. New parameterized test `optimizedAndSyntaxPlansDifferButReturnIdenticalRows` (F1–F6) with small helpers to run one mode, read its log, and extract the `joinOrder` and HashJoin lines of the SELECT statement. Class Javadoc covers Exercise 11 Required Test 6. |

No production code changes.

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

Expected: `JoinTestbedIT` runs 4 oracle cases and 3 metamorphic cases, all green; `BUILD SUCCESS`.
