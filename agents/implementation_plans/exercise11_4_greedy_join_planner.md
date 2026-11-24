# Implementation Plan — Exercise 11.4: The Greedy Planner

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise11.md` §4 "The greedy planner"](../../exercise_descriptions/Exercise11.md), required tests §8.3, §8.4, §8.5
- Design decisions: [`docs/optimizer-design.md`](../../docs/optimizer-design.md) ("The estimator", "The heuristic")
- Prerequisites: [`exercise11_3_cardinality_estimator.md`](exercise11_3_cardinality_estimator.md) (`CardinalityEstimator`),
  [`exercise10_4_left_deep_join_planning.md`](exercise10_4_left_deep_join_planning.md) (week 10 left-deep plan)

## 2. Background & Objective

Week 10 joins tables left-deep in `FROM`-list order: the tables joined so far always build, the next table probes.
Exercise 11.3 added estimates for base tables, filters and equi-joins. This sub-exercise uses them to choose the
join order.

**Objective:**

1. Estimate every table's size after pruning and filters.
2. Start with the join predicate whose estimated output is smallest.
3. Repeatedly add the connected table whose estimated join output is smallest; never a cross product. Log every
   combination checked.
4. The input with the smaller estimated size builds the hash table.
5. Output columns stay in `FROM`-list order, whatever the plan.
6. A flag `-j optimized` (default) / `-j syntax`; `-j syntax` reproduces week 10's plan exactly.

Out of scope: passing `estRows` to `HashJoinOperator.close()` and the per-statement `joinOrder` line (§5), and the
metamorphic integration test (§6). Every planned join step carries its estimate, so §5 only has to hand it on.

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | Table estimate = `CardinalityEstimator.filteredTableRows(surviving partitions, schema, filters)`. |
| F2 | Join predicate NDV per side = `CardinalityEstimator.columnNdv(surviving partitions of that side's table, column)`. For an intermediate result, the NDV of a column is the NDV of the base table that contributed it (design doc). |
| F3 | Greedy step 1: among all join predicates, the one with the smallest `equiJoinRows`. Ties: the first in `WHERE` order. |
| F4 | Greedy step k: among the tables connected by exactly one predicate to the joined set, the one with the smallest `equiJoinRows(joinedRows, tableRows, ndv, ndv)`. Ties: the earlier `FROM` position. Unconnected tables are never candidates. |
| F5 | One `debug` line per combination checked, one per choice. No commas in the message (AGENTS.md §5.5): table lists are joined with `+`. |
| F6 | Build side: step 1 — the smaller table builds (tie: earlier `FROM` position). Step k — the new table builds only if its estimate is strictly smaller than the joined set's; otherwise the joined set builds, as in week 10. |
| F7 | `SELECT *` emits columns in `FROM`-list order: if the physical column layout of the joined row differs from `FROM` order, a `ProjectOperator` permutes it back. A column list is resolved through the same layout. Identity layouts add no operator, so `-j syntax` plans are identical to week 10. |
| F8 | `-j syntax` reproduces week 10: tables in `FROM` order, the joined set builds, same error messages for unconnected tables and for a table connected by more than one predicate. |
| F9 | Cross products remain unsupported: if no remaining table is connected to the joined set, planning fails with `IllegalArgumentException`. A table connected to the joined set by more than one predicate is rejected, as in week 10 (otherwise the second predicate would be silently dropped). |
| F10 | CLI: `-j optimized` or `-j syntax` may precede or follow `-c`/`-f`; the default is `optimized`; an unknown mode or flag is rejected. |

### Non-functional
- The ordering is a pure function over hand-made numbers (`JoinOrderer`), unit-testable without data files.
- Planner still logs pruning per table in `FROM` order and keeps `lastScanStatsByTable` in `FROM` order.
- Throw sites only throw (AGENTS.md §5.4); failures are logged at the CLI/executor boundary as before.
- No package cycles: `optimizer` must not import `planner`; `planner` imports `optimizer`.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `optimizer/JoinOrderMode.java` *(new)* | Enum `SYNTAX`, `OPTIMIZED`; `fromFlag(String)` parses `syntax`/`optimized`. |
| `optimizer/TableEstimate.java` *(new)* | Record `(tableName, estimatedRows)`, one per `FROM` table. |
| `optimizer/JoinSide.java` *(new)* | Record `(tablePosition, columnName, ndv)`: one side of an equi-join predicate. |
| `optimizer/JoinEdge.java` *(new)* | Record `(left, right)`: a join predicate as an edge of the join graph; helpers `connects`, `sideOf`, `otherSideOf`, `describe`. |
| `optimizer/JoinStep.java` *(new)* | Record `(newTablePosition, edge, newTableBuilds, estimatedRows)`. |
| `optimizer/JoinOrder.java` *(new)* | Record `(firstTablePosition, steps)`; `tableOrder()`. |
| `optimizer/JoinOrderer.java` *(new)* | `orderBySyntax(tables, edges)` (week 10 order and errors) and `orderGreedily(tables, edges)` (F3–F6, F9), logging every candidate. |
| `planner/Planner.java` | New constructor `Planner(engine, mode)`; `Planner(engine)` = `OPTIMIZED`. `plan(...)` builds every table pipeline with its estimate, turns join predicates into `JoinEdge`s, asks `JoinOrderer`, folds the steps into `HashJoinOperator`s while tracking the physical column layout, and projects back to `FROM` order (F7). `findConnectingJoin`/`planJoin` move into `JoinOrderer`/the fold. |
| `executor/Executor.java` | New constructor `Executor(engine, output, joinOrderMode)`. |
| `Engine.java` | Parses `-j <mode>` next to `-c`/`-f`; usage text. |
| `test/.../JoinOrdererTest.java` *(new)* | §8.3 (FROM order is the worst; no cross product while a connected table exists), §8.4 (build side), ties, syntax order, error cases. |
| `test/.../JoinPlannerTest.java` | Week 10 plan-shape tests run under `JoinOrderMode.SYNTAX`; new §8.5 tests: a rearranged plan still emits `SELECT *` and column lists in `FROM` order, identical to `-j syntax`. |
| `test/.../EngineTest.java` | `-j` flag parsing (valid modes, unknown mode). |

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

Expected: `JoinOrdererTest`, updated `JoinPlannerTest`, `EngineTest` green; `JoinTestbedIT` and `SqlCorpusIT` (now
running under the optimized default) still match DuckDB; `BUILD SUCCESS`.
