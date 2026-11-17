# Walkthrough — Exercise 10.4: Planning a Chain of Joins

Plan: [`agents/implementation_plans/exercise10_4_left_deep_join_planning.md`](../implementation_plans/exercise10_4_left_deep_join_planning.md)
Spec: [`exercise_descriptions/Exercise10.md` §4](../../exercise_descriptions/Exercise10.md)

## 1. Summary of Work Accomplished

- Multi-table `SELECT`s now execute through the main query path. The temporary executor guard from §2 is gone.
- `Planner.plan(SelectStatement, List<Catalog>)`:
  1. **One pipeline per table.** Filters are assigned to their owning table with `ColumnResolver`. Each table gets a `Scan` over the partitions that survive pruning, under one `Filter` per filter on that table. Pruning and its decision logs work per table, as in Part 1.
  2. **Left-deep fold in `FROM` order.** Each new table probes a hash table built from the tables joined so far: `HashJoinOperator(joinedSoFar, pipeline(i), joinedSide.joinedIndex, newSide.columnIndexInTable)`. The new table may sit on either side of `=`.
  3. **Optional projection** onto `joinedIndex` positions (column lists over joins).
- **Unconnected `FROM` list is rejected:** `table cities is not connected by a join predicate to the tables before it (orders); reorder the FROM list so that each table joins a table listed before it`. A `FROM` list with no joins at all is rejected the same way.
- **Documented simplification:** one connecting predicate per new table. A second one is **rejected** with `table customers is connected by 2 join predicates (o_custname = c_name and o_prodname = c_city); only one join predicate per table is supported`. A predicate connects table `i` exactly when the later of its two tables is `i`, so every join predicate is either used by one fold step or rejected; none is silently ignored.
- `Planner.lastScanStatsByTable()` gives per-table `ScanStats` in `FROM` order. `lastScanStats()` returns the first table's stats, so single-table behaviour is unchanged.
- The executor logs the existing `statement=SELECT table=... rowsOut=... partitionsRead=... partitionsPruned=... version=...` line once per `FROM` table. Each join logs `operator=HashJoin ... rowsOut=...`. Single-table queries log exactly as before.

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `planner/Planner.java` | `plan(statement, List<Catalog>)` plus helpers `requireMatchingSnapshots`, `assignFiltersToTables`, `planTablePipeline`, `assignJoinsToTables`, `requireOneConnectingJoinPerTable`, `planJoin`, `laterTablePosition`, `describeJoins`, `planProjection` (resolver-based). `plan(statement, Catalog)` delegates. Per-table stats map. |
| `executor/Executor.java` | `bindAndPlanSelect` binds and plans with all snapshots; `logSelectSummaries` writes one line per table; the guard and its `@throws UnsupportedOperationException` are removed. |
| `src/test/resources/joins/*.csv` | Testbed data from §5. |
| `JoinPlannerTest.java` | New (required test 5 plus testbed rows and logs). |
| `ExecutorTest.java`, `PlannerTest.java` | Guard test now expects execution; a `null` snapshot cast for overload resolution. |

Why the keys are right: the fold follows `FROM` order, so the joined-so-far row is exactly the prefix of the resolver's concatenated row. The already-joined column's `joinedIndex` is therefore its index in the build row. The new table's pipeline emits that table's own schema, so its key is `columnIndexInTable`.

## 3. Verification Results

`mvn -B clean verify`:

```
[INFO] Tests run: 147, Failures: 0, Errors: 0, Skipped: 0     (Surefire)
[INFO] Tests run: 201, Failures: 0, Errors: 0, Skipped: 0     (Failsafe)
[INFO] BUILD SUCCESS
```

Manual check through the packaged engine (`java -jar target/engine.jar -f testbed.sql` in a scratch directory): the four-table query returns 4 rows and the Copenhagen query 3 rows. The sorted four-table output is **identical to DuckDB's**. DuckDB needs `COPY ... (HEADER false)`: `customers.csv` is all-`STRING`, so DuckDB's sniffer otherwise treats `Anna,Copenhagen` as a header and drops Anna's two orders (the lecture 2 divergence). Required test 6 (scripted DuckDB comparison for all four queries) is the next step.

## 4. Edge Cases & Invariants Tested

- **Shape (four tables):** `HashJoin(5,0)[cities]` over `HashJoin(2,0)[products]` over `HashJoin(1,0)[customers]` over `Scan(orders)`.
- **Filters below joins + per-table pruning:** `o_orderkey > 101` becomes `Filter` over `Scan(orders, [1])` on the build side, and `c_city = 'Copenhagen'` becomes `Filter` over `Scan(customers)` on the probe side. `ScanStats`: orders 2/1/1, customers 2/2/0, products 1/1/0. Result: 2 rows.
- **Predicate side order:** `c_name = o_custname` gives the same keys as `o_custname = c_name`.
- **Rejections:** unconnected middle table (exact message, and the reordered `FROM` list plans), no joins at all, two connecting predicates.
- **Reject before planning:** a rejected `FROM` list leaves `lastScanStatsByTable()` empty, so no table pipeline was planned and no pruning decisions were logged.
- **Projection over joins:** `SELECT c_city, o_orderkey ...` returns 4 projected rows.
- **Single-table plans** keep the `Filter` over `Scan` shape and one stats entry.
- **Testbed (§5):** queries 1–3 return 4 hand-checked rows each, query 4 returns 3.
- **Logs:** two `operator=HashJoin` lines (`rowsBuild=4 rowsProbe=3 rowsOut=4` and `rowsBuild=4 rowsProbe=2 rowsOut=4`) and one `statement=SELECT table=...` line per table.
- **Pins:** a successful multi-table query leaves no pins.

## 5. Notes

- A binder-accepted cross product (`FROM a, b` without a join) is rejected by the planner, not the binder. Rejecting more than DuckDB is allowed.
- Week 11 replaces the fixed `FROM` order. `planJoin` already works per step, so a different table order only changes the loop and how join predicates are grouped.

## 6. Review Follow-up (PR 93, comment on `Planner.java:131`)

The fold used to call `findConnectingJoin` once per table. Each call scanned every join predicate and resolved both columns, so an unconnected table was only found after the earlier tables' pipelines (with their pruning logs) had been planned. Now `assignJoinsToTables` groups join predicates by their later table once, the same way `assignFiltersToTables` groups filters. `requireOneConnectingJoinPerTable` checks every table before any pipeline is planned, and the loop looks its predicate up by position. The behaviour the exercise asks for is unchanged: left-deep, `FROM` order, the intermediate result builds, the same two error messages, one predicate per table. Note that the spec's "naive" means this join order, not how efficient the planner code is.
