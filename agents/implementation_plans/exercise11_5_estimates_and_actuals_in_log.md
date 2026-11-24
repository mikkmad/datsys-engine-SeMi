# Implementation Plan — Exercise 11.5: Estimates and Actuals, Side by Side in the Log

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise11.md` §5 "Estimates and actuals, side by side in the log"](../../exercise_descriptions/Exercise11.md),
  log part of required test §8.6
- Design decisions: [`docs/optimizer-design.md`](../../docs/optimizer-design.md) ("The estimator": estimates are kept
  as `double` through a chain of joins and rounded only when logged)
- Prerequisite: [`exercise11_4_greedy_join_planner.md`](exercise11_4_greedy_join_planner.md) (every `JoinStep`
  already carries its `estimatedRows`, in both join order modes)

## 2. Background & Objective

Exercise 11.4 chooses the join order from estimates, but the estimates only reach the log in the `JoinOrderer`
candidate lines at plan time. The actual sizes reach the log later, in `HashJoinOperator.close()`. Week 12's
benchmark and report need both numbers for the same join on one line, so the estimate error `estRows` vs.
`rowsOut` can be read (and queried) directly.

**Objective:**

1. The planner hands each `HashJoinOperator` its estimate, its join number and the tables it joins.
2. `HashJoinOperator.close()` writes one line carrying both the estimate and the actual counts.
3. The planner logs the chosen join order and mode once per `SELECT` statement.
4. Every line carries the statement's `statementNumber` through the MDC, as every engine line already does.

Out of scope: the metamorphic integration test (§6) and comparing plans across modes from the log.

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | A new record `operators/JoinEstimate(joinNumber, tableNames, estimatedRows)` describes a planned join: its one-based position in the plan, the tables of its output in join order, and the estimated output rows. |
| F2 | `HashJoinOperator` requires a `JoinEstimate`; there is no join without an estimate, so every executed join logs one. |
| F3 | `close()` logs `operator=HashJoin join=<n> tables=<t1+t2+...> estRows=<rounded> buildKey=<i> probeKey=<j> rowsBuild=<b> rowsProbe=<p> rowsOut=<o>`. `estRows` is `Math.round(estimatedRows)` (design doc: round only when logged). |
| F4 | For a multi-table `SELECT`, the planner logs `statement=SELECT joinOrder=<t1+t2+...> mode=<optimized|syntax> estRows=<rounded>` once, after choosing the order; `estRows` is the final join's estimate. Single-table statements have no join order and log nothing new, so their log is unchanged. |
| F5 | `join=<n>` numbers the joins in plan order (`join=1` is the first, deepest join), and `tables=` lists the tables in join order, the same order as `joinOrder=`, so each join line can be matched to the order line. |
| F6 | Table lists are joined with `+`, never `,` (AGENTS.md §5.5 / CSV log), consistent with the 11.4 `JoinOrderer` lines. |
| F7 | Both modes log the same lines; under `-j syntax` the estimates are those 11.4 already computes for the `FROM`-list order. |

### Non-functional
- `operators` does not import `optimizer` or `planner`; the planner translates a `JoinStep` into a `JoinEstimate`.
- No new log levels: `debug` only. No log-and-throw: the record's constructor throws plainly.
- Plan shapes are unchanged: the estimate is metadata, it does not change keys, children or column order.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `operators/JoinEstimate.java` *(new)* | Record `(int joinNumber, List<String> tableNames, double estimatedRows)`; validation; `describeTables()` joins names with `+`; `roundedRows()`. |
| `operators/HashJoinOperator.java` | Constructor takes a `JoinEstimate` as fifth argument; `close()` logs F3; accessor `estimate()`. |
| `planner/Planner.java` | `planJoins` numbers the steps and passes a `JoinEstimate` per join; `plan` logs F4 for multi-table statements. |
| `test/.../HashJoinOperatorTest.java` | Helper passes an estimate; close-line test asserts `join`, `tables`, `estRows`, `rowsOut`; invalid estimates rejected. |
| `test/.../JoinPlannerTest.java` | Log test updated to the new line; new tests: under both modes every executed join leaves a line with `estRows` and `rowsOut`, one `joinOrder` line per statement, all with the statement's `statementNumber`; planned joins carry the 11.4 estimates; single-table statements log no `joinOrder`. |

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

Expected: `HashJoinOperatorTest`, `JoinPlannerTest` green with the new assertions; all other suites unchanged;
`BUILD SUCCESS`.
