# Implementation Plan — Exercise 10.3: The Hash Join Operator

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise10.md` §3 "The hash join operator"](../../exercise_descriptions/Exercise10.md) and §7 Required test 4.
- Builds on: [`exercise10_2_multi_table_binding.md`](exercise10_2_multi_table_binding.md). Used by §4 (left-deep planning).

## 2. Background & Objective

The engine needs an operator that joins two row streams on equal keys. `HashJoinOperator` is an inner equi-join in the Volcano model:

- `open()` drains the **build** child into a `HashMap<Object, List<Object[]>>` (key → rows; a list because keys repeat).
- `next()` pulls **probe** rows, looks up the key, and emits one output row per matching build row. A probe row that matches `k` build rows yields `k` rows, so the operator keeps a cursor into the current match list between `next()` calls. Output row = build columns followed by probe columns.
- Unmatched rows on either side are dropped (inner join).
- `close()` logs `rowsBuild`, `rowsProbe`, `rowsOut`. `rowsOut` is the intermediate result size that weeks 11–12 optimize and measure.

This sub-exercise delivers the operator and its unit tests only; the planner wires it in §4.

## 3. Requirements & Invariants

| ID | Requirement |
|----|-------------|
| F1 | `public final class HashJoinOperator implements Operator` in `datasys.semi.operators`, constructor `HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey)`. Public, so the planner in another package can build it. Rejects null children and negative key indices. |
| F2 | `open()` opens both children, drains the build child into the hash table, and counts `rowsBuild`. Re-opening throws `IllegalStateException` (same lifecycle as `FilterOperator`). |
| F3 | `next()` returns the next joined row or `null` when the probe side is exhausted; stays `null` afterwards. Uses a cursor (current probe row + match list + index). |
| F4 | Output row is a new `Object[]` of length `build.length + probe.length`: build columns, then probe columns. Input rows are never mutated. |
| F5 | Keys are compared with `equals`/`hashCode` (the binder guarantees equal types). A `null` key never matches (SQL semantics), on either side. |
| F6 | `close()` logs `operator=HashJoin buildKey=.. probeKey=.. rowsBuild=.. rowsProbe=.. rowsOut=..` at `DEBUG`, closes both children and releases the hash table. |
| F7 | Accessors `build()`, `probe()`, `buildKey()`, `probeKey()` for planner tests (§4), and `rowsBuild()`, `rowsProbe()`, `rowsOut()` for metrics. |
| F8 | A key index beyond a row's width fails with `IllegalStateException` naming the side and index. |

### Invariants
- Not thread-safe; one pipeline per statement per thread (as the `Operator` contract states).
- Only `debug` logging; no log-and-throw.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `src/main/java/datasys/semi/operators/HashJoinOperator.java` | New. |
| `src/test/java/datasys/semi/HashJoinOperatorTest.java` | New: required test 4 over `TestListOperator` stubs. |

### Tests
1. Duplicates multiply: 2 build × 3 probe rows with one key → 6 rows, in probe order, build order within a probe row.
2. Unmatched rows on both sides are dropped.
3. Empty build side → no rows; probe side is still drained (`rowsProbe` counts it).
4. Empty probe side → no rows.
5. Output layout: build columns then probe columns; key positions other than 0.
6. Counters `rowsBuild/rowsProbe/rowsOut` and the `close()` summary line in the log.
7. `null` keys never match; `next()` after exhaustion keeps returning `null`.
8. Lifecycle and argument errors (`next` before `open`, double `open`, null child, negative key, key out of range).
9. Testbed shape: orders ⋈ customers on name gives 4 rows.

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

Expected: all tests green including `HashJoinOperatorTest`.
