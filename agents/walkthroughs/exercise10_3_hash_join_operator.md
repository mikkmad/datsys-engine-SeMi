# Walkthrough — Exercise 10.3: The Hash Join Operator

Plan: [`agents/implementation_plans/exercise10_3_hash_join_operator.md`](../implementation_plans/exercise10_3_hash_join_operator.md)
Spec: [`exercise_descriptions/Exercise10.md` §3](../../exercise_descriptions/Exercise10.md)

## 1. Summary of Work Accomplished

- New `HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey)`: an inner equi-join in the Volcano model.
  - `open()` opens both children and drains the build child into a `HashMap<Object, List<Object[]>>` from key to rows.
  - `next()` streams probe rows. A cursor (current probe row, its match list, the next match index) survives between calls, so a probe row matching `k` build rows yields `k` output rows.
  - Output row = build columns followed by probe columns, always as a new array.
  - Unmatched rows are dropped, and a `null` key never matches (SQL semantics).
  - `close()` logs `operator=HashJoin buildKey=.. probeKey=.. rowsBuild=.. rowsProbe=.. rowsOut=..`, releases the hash table and closes both children.
- Not wired into the planner yet; that is §4. The executor's temporary multi-table guard from §2 is unchanged.

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `operators/HashJoinOperator.java` | New operator. |
| `HashJoinOperatorTest.java` | New unit tests (required test 4). |

Design decisions:
- **Public constructor**, unlike the package-private sketch in the spec, because the planner in `datasys.semi.planner` builds it in §4. This matches `FilterOperator`.
- **Same lifecycle guards as `FilterOperator`:** `next()`/`close()` before `open()` and a second `open()` throw `IllegalStateException`.
- **Both children are closed in `close()`**, not right after draining the build side, so the `Operator` contract (close propagates to children once) holds.
- **The probe side is drained even when the build side is empty.** It's simpler, and `rowsProbe` stays an honest count of the work done. Week 11/12 could short-circuit this.
- **Key compared with `equals`/`hashCode`.** The binder (§2) guarantees both key columns have the same type.
- **Accessors** `build()`, `probe()`, `buildKey()`, `probeKey()` for the §4 planner shape tests, and `rowsBuild()`, `rowsProbe()`, `rowsOut()` for metrics.

## 3. Verification Results

`mvn -B clean compile`: success.

`mvn -B verify`:

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.HashJoinOperatorTest
[INFO] Tests run: 137, Failures: 0, Errors: 0, Skipped: 0     (Surefire)
[INFO] Tests run: 201, Failures: 0, Errors: 0, Skipped: 0     (Failsafe)
[INFO] BUILD SUCCESS
```

## 4. Edge Cases & Invariants Tested

- **Duplicates multiply:** 2 build × 3 probe rows with one key → 6 rows, in probe order, in build order within a probe row.
- **Unmatched rows dropped** on both sides.
- **Empty build side:** no output; probe still pulled (`rowsProbe=2`).
- **Empty probe side:** no output; build fully drained (`rowsBuild=2`).
- **Testbed shape:** orders ⋈ customers on name (keys at positions 1 and 0) → 4 rows with build columns first; counters 4/3/4.
- **`null` keys** never match, not even each other.
- **No aliasing:** mutating an output row leaves the input rows untouched.
- **`next()` stays `null`** after exhaustion.
- **Log line:** `close()` writes `operator=HashJoin buildKey=0 probeKey=0 rowsBuild=4 rowsProbe=3 rowsOut=3` at `DEBUG` with the session id.
- **Errors:** lifecycle violations; null children; negative keys; key index beyond the row width on the build side (on `open`) and on the probe side (on `next`).

## 5. Notes for §4

- Fold the `FROM` list left to right: `new HashJoinOperator(joinedSoFar, nextTablePipeline, buildKey, probeKey)`. `buildKey` is the `ColumnResolver.joinedIndex` of the already-joined side's column. `probeKey` is the `columnIndexInTable` of the new table's column. Because the fold follows `FROM` order, the joined-so-far prefix of the row matches the resolver's concatenated layout.
- Replace the executor guard in `bindAndPlanSelect` with the multi-table plan.
