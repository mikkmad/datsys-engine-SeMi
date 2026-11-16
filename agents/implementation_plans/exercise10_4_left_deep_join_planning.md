# Implementation Plan — Exercise 10.4: Planning a Chain of Joins

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise10.md` §4 "Planning a chain of joins"](../../exercise_descriptions/Exercise10.md) and §7 Required test 5 (planner).
- Builds on: [`exercise10_2_multi_table_binding.md`](exercise10_2_multi_table_binding.md) (`ColumnResolver`, one snapshot per table) and [`exercise10_3_hash_join_operator.md`](exercise10_3_hash_join_operator.md) (`HashJoinOperator`).

## 2. Background & Objective

Joins of several tables must execute through the main query path. The planner is deliberately naive this week:

1. **One pipeline per table**, exactly as in Part 1: a `Scan` over the partitions that survive pruning, under one `Filter` per filter on that table's columns. Pruning works per table because each filter binds to exactly one table.
2. **Fold the `FROM` list left to right.** For table `i`, find the join predicate that connects it to tables `0..i-1` and build `HashJoinOperator(joinedSoFar, pipeline(i), buildKey, probeKey)`. The intermediate result builds, and the new table probes.
3. Output rows are build columns followed by probe columns, so `SELECT *` emits columns in `FROM`-list order, which matches DuckDB.

Week 11 replaces the fixed order with a cost-based one, and week 12 measures it.

## 3. Requirements & Invariants

| ID | Requirement |
|----|-------------|
| F1 | `Planner.plan(SelectStatement, List<Catalog> snapshots)`: one snapshot per `FROM` table, in order. `plan(SelectStatement, Catalog)` delegates for single-table statements, so existing callers keep working. |
| F2 | Filters are assigned to their owning table via `ColumnResolver` and bound to `columnIndexInTable`; each table pipeline is `Filter*` over `Scan(surviving partitions)`, with per-table pruning and partition decision logs as before. |
| F3 | Left-deep fold in `FROM` order: `buildKey` = `joinedIndex` of the already-joined side (the joined-so-far row is the resolver's concatenated prefix), `probeKey` = `columnIndexInTable` of the new table's side. Either side of `=` may be the new table. |
| F4 | A table with **no** join predicate to the tables before it is rejected with `IllegalArgumentException`: `table cities is not connected by a join predicate to the tables before it (orders); reorder the FROM list so that each table joins a table listed before it`. A cross product is never built. |
| F5 | **Simplification (documented):** exactly one connecting predicate per new table. A second predicate connecting the same table to earlier tables is rejected with a clear `IllegalArgumentException` naming both predicates. Every join predicate is either used by exactly one fold step or rejected. |
| F6 | Column lists over joins: `ProjectOperator` on top using `joinedIndex`. |
| F7 | `Planner.lastScanStatsByTable()` returns per-table `ScanStats` in `FROM` order; `lastScanStats()` returns the first table's stats (unchanged for single-table queries). |
| F8 | `Executor` passes all snapshots to the planner. The temporary §2 guard (`UnsupportedOperationException`) and its Javadoc are removed. The SELECT summary log line keeps its existing format and is written once per `FROM` table (`table=X`, that table's partitions and version, and the statement's `rowsOut`), so log analysis over `table=` keeps working; single-table queries log exactly as before. Each join adds its `operator=HashJoin ... rowsOut=` line. |

### Invariants
- Single-table plans are unchanged in shape (`Filter*` over `Scan`, optional `Project`).
- No log-and-throw: planner throws, executor boundary logs.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `planner/Planner.java` | Multi-snapshot `plan`, per-table pipelines with recorded `ScanStats`, left-deep fold, connecting-predicate search, projection via resolver. |
| `executor/Executor.java` | Remove guard; plan with all snapshots; one summary line per table. |
| `src/test/resources/joins/{orders,customers,products,cities}.csv` | Testbed data (§5). |
| `JoinPlannerTest.java` | New: required test 5 (shape, keys, filters below joins, per-table pruning in `ScanStats`, unconnected / second-predicate rejection, projection) plus the testbed result rows. |
| `ExecutorTest.java` | Replace the guard test: multi-table SELECT now executes; HashJoin log lines present. |

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

Expected: all tests green. The four testbed queries return 4, 4, 4 and 3 rows with the hand-checked contents.
