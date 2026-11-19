# Implementation Plan — Exercise 11.2: NDV during COPY

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise11.md` §2 "NDV during COPY"](../../exercise_descriptions/Exercise11.md), required test §8.1
- Design decisions: [`docs/optimizer-design.md`](../../docs/optimizer-design.md) ("Statistics", "Where they live and how they merge", "Roll forward")
- Prerequisite: [`unknown_json_rollback_safety_guard.md`](unknown_json_rollback_safety_guard.md) (a rolled-back engine ignores `ndv`)

## 2. Background & Objective

The join estimator in §3 needs the number of distinct values (NDV) per column. Today every partition stores only
min/max per column, computed in `StorageEngine.computeStatistics` when `writePartitionFile` writes the partition.

**Objective:**

1. Count the exact NDV per column per partition with a `HashSet` next to min/max, store it in the catalog, and log
   it on its own line.
2. Add the merge rule from the design document as a pure, unit-tested helper: the table NDV is the sum of the
   partition NDVs.
3. Give partitions written before this release (no `ndv`) a well-defined value: their `rowCount`. Every partition
   has `ndv <= rows`, so the summed table NDV stays an upper bound.

Roll forward (computing a missing NDV on first scan) is **out of scope** and gets its own sub-exercise and branch.

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | Every partition written by `COPY` stores the exact NDV of every column (`STRING`, `LONG`, `DOUBLE`) in `Statistics.ndv`. |
| F2 | Partitions rewritten by `DELETE` get a freshly computed NDV (same `writePartitionFile` path). |
| F3 | Each NDV gets its own `DEBUG` line when it is created: `table=<t> partition=<n> column=<c> rows=<r> ndv=<d>`. |
| F4 | `NdvMerger.tableNdv(List<Long> partitionNdvs, long tableRowCount)` returns `min(sum, tableRowCount)`. The cap never applies when `ndv_i <= rows_i`; it is kept as a safety check. Invalid input (`null` list or element, negative values) throws `IllegalArgumentException`. |
| F5 | `Partition.columnNdv(column)` returns the stored NDV, or the partition's `rowCount` when the NDV is missing. |
| F6 | A catalog without `ndv` (written before this release) still loads; its `ndv` is `null`, and is not written back as `"ndv": null`. |

### Invariants
- **I1** `1 <= ndv <= rowCount` for every column of every non-empty partition.
- **I2** `optimizer` depends on no other engine package (pure functions over numbers); no cyclic dependencies.
- **I3** Only `debug` logging; the merge helper does not log.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `engine/StorageEngine.java` | `Statistics(String min, String max, Long ndv)` with `@JsonInclude(NON_NULL)`; new public `distinctCount(List<Object>)` next to `minMax`; `computeStatistics` split into a per-column helper that also counts NDV and logs it; `Partition.columnNdv(String)`. |
| `optimizer/NdvMerger.java` *(new)* | Final utility class with `tableNdv`. |
| `NdvMergerTest` *(new)* | Sum, cap, empty list, invalid input. |
| `NdvCollectionTest` *(new)* | §8.1: known duplicates give the exact per-partition NDV for all three types; NDV persisted in the catalog and logged; DELETE rewrite recomputes it; old catalog without `ndv` loads with the `rowCount` fallback. |
| `docs/optimizer-design.md` | Merge rule wording (cap holds by construction), meaning of a missing NDV, and what to use at a billion rows (HyperLogLog). |

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

Expected: the new tests pass, the existing suites are unchanged, 0 failures and 0 errors.
