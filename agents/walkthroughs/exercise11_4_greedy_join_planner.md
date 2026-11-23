# Walkthrough — Exercise 11.4: The Greedy Planner

Plan: [`agents/implementation_plans/exercise11_4_greedy_join_planner.md`](../implementation_plans/exercise11_4_greedy_join_planner.md)

## 1. Summary of Work Accomplished

The `FROM` list no longer fixes the join order. The planner estimates every table after pruning and filters,
turns every join predicate into an edge with the NDVs of both columns, and asks `optimizer/JoinOrderer` for a
left-deep order:

- **`-j optimized`** (the default): greedy smallest-intermediate-first. Step 1 takes the predicate with the smallest
  estimated output; each later step adds the *connected* table with the smallest estimated join output. Every
  combination checked and every choice is logged. The smaller estimated input builds.
- **`-j syntax`**: week 10's plan, unchanged: `FROM`-list order, the tables joined so far build, same error messages.

Whatever the order, output columns are in `FROM`-list order: the planner tracks which table's columns sit where in
the joined row and adds a `ProjectOperator` only when that layout differs from `FROM` order.

Out of scope (§5): `estRows` in `HashJoinOperator.close()` and the per-statement `joinOrder` line. Every `JoinStep`
already carries its `estimatedRows`, in both modes, so §5 only has to hand it on.

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `optimizer/JoinOrderMode.java` *(new)* | `SYNTAX`, `OPTIMIZED`; `fromFlag("syntax"/"optimized")`, `flagValue()`. |
| `optimizer/TableEstimate`, `JoinSide`, `JoinEdge`, `JoinStep`, `JoinOrder` *(new records)* | Pure inputs and outputs of the orderer: table estimates, the join graph (`JoinEdge` = two `JoinSide`s with position, column, NDV), and the chosen order (`firstTablePosition` + steps with new table, edge, build side, estimate). |
| `optimizer/JoinOrderer.java` *(new)* | `orderBySyntax` and `orderGreedily`. Join estimates use `CardinalityEstimator.equiJoinRows` with the base-table NDV of each join column, also for intermediate results. Logging only at `debug`, table lists joined with `+` (no commas in the CSV log). |
| `planner/Planner.java` | `Planner(engine, mode)`; `Planner(engine)` = `OPTIMIZED`. `plan` → `planTable` (pipeline + surviving partitions + estimate, per table in `FROM` order) → `chooseJoinOrder` → `planJoins` (fold with column layout) → `planOutputColumns`. Week 10's `findConnectingJoin`/`planJoin` logic moved into `JoinOrderer` and the fold. |
| `operators/ProjectOperator.java` | Accessors `child()`, `columnIndices()` for plan-shape tests. |
| `executor/Executor.java` | `Executor(engine, output, joinOrderMode)`; the old constructors default to `OPTIMIZED`. |
| `Engine.java` | Flags parsed as pairs in any order: exactly one of `-c`/`-f`, optional `-j optimized\|syntax`; usage text. |

### Rules the orderer applies

| Decision | Rule |
|----------|------|
| Step 1 | Smallest `\|R\|·\|S\|/max(ndv)` over all predicates; tie → earlier predicate in `WHERE` order. |
| Step k | Among tables connected to the joined set by a predicate, smallest `joinedRows·\|T\|/max(ndv)`; tie → earlier `FROM` table. Unconnected tables are never candidates. |
| Build side, step 1 | Smaller table builds; tie → earlier `FROM` table. |
| Build side, step k | New table builds only if strictly smaller than the joined set; otherwise the joined set builds (week 10). |
| Cross product needed | `IllegalArgumentException` (`... cross products are not supported`). |
| Table with 2+ predicates to the joined set (cycle) | `IllegalArgumentException`, as in week 10: the hash join applies one predicate; the other would be dropped silently. |

### Log lines (example, testbed `orders, customers, cities`)

```
mode=optimized step=1 candidate=customers+cities on=c_city=ct_city inputRows=3/2 ndv=3/2 estRows=2
mode=optimized step=1 chosen=cities+customers estRows=2 build=cities probe=customers
mode=optimized step=2 candidate=cities+customers+orders on=o_custname=c_name inputRows=2/4 ndv=3/4 estRows=2
mode=optimized step=2 chosen=cities+customers+orders estRows=2 build=cities+customers probe=orders
mode=syntax step=2 chosen=orders+customers+cities estRows=2 build=orders+customers probe=cities
```

`inputRows` and `ndv` are `joined/new`; estimates are rounded only when logged.

## 3. Verification Results

`mvn -B clean verify`:

| Phase | Tests run | Failures | Errors | Skipped |
|-------|-----------|----------|--------|---------|
| Surefire (unit) | 197 | 0 | 0 | 0 |
| — `JoinOrdererTest` *(new)* | 10 | 0 | 0 | 0 |
| — `JoinPlannerTest` (+4) | 13 | 0 | 0 | 0 |
| — `EngineTest` (+2) | 7 | 0 | 0 | 0 |
| Failsafe (integration) | 205 | 0 | 0 | 0 |
| — `JoinTestbedIT` (now under `optimized`, vs DuckDB) | 4 | 0 | 0 | 0 |

`BUILD SUCCESS`

The six week 10 plan-shape tests in `JoinPlannerTest` now run with `JoinOrderMode.SYNTAX`: they describe week 10's
plan, which is exactly what `-j syntax` must reproduce.

## 4. Edge Cases & Invariants Tested

| Test | Scenario |
|------|----------|
| `greedyOrderReversesFromListWhenFromListOrderIsWorst` (§8.3) | `orders(10000), customers(1000), cities(1)`: syntax 10000 → 100, greedy 10 → 100 with order `cities, customers, orders`. |
| `greedyOrderNeverPicksCrossProductWhileConnectedTableExists` (§8.3) | A 1-row unconnected table would be the cheapest cross product; the connected 10000-row join is chosen; every step is connected. |
| `greedyOrderBreaksTiesByWhereOrderAndFromPosition` | Equal estimates → first predicate; equal sizes → earlier `FROM` table builds. |
| `smallerTableBuildsTheFirstJoin`, `smallerInputBuildsLaterJoins` (§8.4) | Smaller table builds even when listed second; later steps let a smaller new table build, a larger one probe. |
| `syntaxOrderKeepsFromListOrderAndJoinedTablesBuild`, `syntaxOrderRejectsUnconnectedTablesAndSecondPredicates` | Week 10 behaviour and messages. |
| `greedyOrderRejectsCrossProductsAndSecondPredicates` | Disconnected graph, no predicates, cyclic join graph. |
| `singleTableHasNoJoinSteps`, `invalidInputThrowsIllegalArgumentException` | Degenerate and invalid input. |
| `rearrangedPlanStillEmitsSelectStarColumnsInFromListOrder` (§8.5) | `customers` builds, a `ProjectOperator` restores `orders, customers` column order; rows equal the syntax plan. |
| `rearrangedPlanResolvesColumnListThroughJoinedLayout` (§8.5) | Column list over a reordered 3-table plan equals the syntax plan. |
| `testbedQueriesPrintIdenticalRowsUnderBothJoinOrderModes` (§8.5) | 2-, 3-, 4-table and filtered testbed queries via `Executor`, identical sorted output in both modes. |
| `optimizedPlannerReordersUnconnectedFromListButRejectsCrossProducts` | `FROM orders, cities, customers` plans under `optimized` (rejected under `syntax`); no predicates → error. |
| `joinOrderFlagIsAcceptedBeforeOrAfterStatementFlag`, `invalidJoinOrderArgumentsAreRejected` | CLI `-j` parsing: both positions, unknown mode, repeated flag, `-c` with `-f`, missing value. |
