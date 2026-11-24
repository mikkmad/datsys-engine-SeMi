# Walkthrough — Exercise 11.6: The Metamorphic Test

Implementation plan: [`exercise11_6_metamorphic_test.md`](../implementation_plans/exercise11_6_metamorphic_test.md)
Specification: [`exercise_descriptions/Exercise11.md` §6, §8.6](../../exercise_descriptions/Exercise11.md)

## 1. Summary of Work Accomplished

`JoinTestbedIT` gained the metamorphic test: the testbed queries with three and four tables run through the
packaged engine twice, as `./engine -j syntax -f …` and `./engine -j optimized -f …`. The test asserts that the
sorted rows are identical, that the join orders read from the two logs differ, and that every executed join left
one log line with `estRows` and `rowsOut`. No production code changed.

What the two modes actually do on the testbed (same script, default partition size, read from each run's log):

| Query | Rows | `-j syntax` joinOrder | `-j optimized` joinOrder | est/actual per join (optimized) |
|-------|------|-----------------------|--------------------------|---------------------------------|
| query2 (3 tables) | 4 | `orders+customers+products` | `customers+orders+products` | 4/4, 4/4 |
| query3 (4 tables) | 4 | `orders+customers+products+cities` | `cities+customers+orders+products` | 3/3, 4/4, 4/4 |
| query4 (3 tables, filter) | 3 | `orders+customers+products` | `customers+orders+products` | 2/3, 2/3 |

## 2. Key Changes & Architecture

| Component | Change |
|-----------|--------|
| `JoinTestbedIT.optimizedAndSyntaxPlansDifferButReturnIdenticalRows` *(new, parameterized)* | `query2`, `query3`, `query4` (the §6 three- and four-table queries; `query1` has two tables). Runs both modes, then asserts rows equal and non-empty, `joinOrder` different, and one join line per join under each mode. |
| `runInMode` / `ModeRun` *(new helpers)* | Each mode runs in its own subdirectory of the test's `@TempDir`, so it has its own data directory and its own `logs/engine.log`; no other test's log lines can interfere. Reads exactly one `statement=SELECT joinOrder=… mode=…` line, checks `mode=` matches the flag, and collects the `operator=HashJoin` lines with the same `statementNumber`. |
| `assertEveryJoinLogged` | Every join line must match `join=N tables=… estRows=… … rowsOut=…`, and the join numbers must be exactly `1..tables-1`. |
| `runEngine(workDirectory, script, engineArguments...)` | Generalized to pass extra CLI arguments (`-j <mode>`); the DuckDB oracle test calls it without any. `readQuery` is shared by both tests, so both read the same `joins/queries/*.sql` files as the oracle script. |

Design choices:
- **The plan difference is asserted, not assumed.** A metamorphic test that compares a plan with itself passes
  trivially; if a future estimator change made both modes choose the `FROM` order, this test fails and the query
  set must be revisited.
- **Separate processes, separate logs.** The plan is read from the real log of a real CLI run, through the same
  `-j` flag week 12's benchmark uses, rather than from planner objects.
- Helpers were added to `JoinTestbedIT` instead of a new class, so the script and process code is not duplicated.

## 3. Verification Results

```
mvn -B clean verify -> Surefire: Tests run: 202, Failures: 0, Errors: 0, Skipped: 0
                       JoinTestbedIT: Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 (4 DuckDB + 3 metamorphic)
                       Failsafe: Tests run: 208, Failures: 0, Errors: 0, Skipped: 0
                       BUILD SUCCESS
```

## 4. Edge Cases & Invariants Tested

| Case | How it is covered |
|------|-------------------|
| Same rows under both plans | Sorted stdout of the two runs compared line by line; an empty result fails, so two failing runs cannot agree vacuously. |
| Plans really differ | `assertNotEquals` on the two `joinOrder=` values; all three queries reorder (table above). |
| Log attributes the plan to the statement | Exactly one `joinOrder` line per run, its `mode=` equals the flag, and join lines are selected by that line's `statementNumber`. |
| Every executed join logged | Join numbers `1..n-1`, each line carrying `estRows` and `rowsOut` (§8.6). |
| Filtered query | `query4` (`c_city = 'Copenhagen'`) reorders too and returns 3 rows under both modes; its estimate (2) undershoots the actual (3), the kind of error week 12 analyses. |
