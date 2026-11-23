# Implementation Plan — Exercise 11.3: The Estimator

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise11.md` §3 "The estimator"](../../exercise_descriptions/Exercise11.md), required test §8.2
- Design decisions: [`docs/optimizer-design.md`](../../docs/optimizer-design.md) ("The estimator")
- Prerequisite: [`exercise11_2_ndv_during_copy.md`](exercise11_2_ndv_during_copy.md) (per-partition NDV, `NdvMerger.tableNdv`)

## 2. Background & Objective

The greedy join planner (§4) needs the estimated size of every table and every join. Exercise 11.2 stores per
partition a row count, min/max and NDV per column. This sub-exercise turns those numbers into estimates.

**Objective:** three small pure functions over catalog numbers, unit-testable without data files:

1. **Base table:** the sum of the row counts of the partitions that survive pruning.
2. **Filter:** equality keeps `|R| / ndv(col)`; `<` and `>` interpolate linearly between each surviving partition's
   min and max, with Selinger's `1/3` as the fallback for `STRING` columns and missing statistics.
3. **Equi-join:** `|R ⋈ S| ≈ |R| · |S| / max(ndv(R.a), ndv(S.b))`.

Wiring the estimator into the `Planner` is **out of scope** (§4, next sub-exercise).

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | `baseTableRows(survivingPartitions)` returns the sum of their `rowCount`s. |
| F2 | `columnNdv(survivingPartitions, column)` merges the partition NDVs with `NdvMerger.tableNdv`; a partition without an NDV (or without statistics for the column) counts with its `rowCount`. |
| F3 | Equality selectivity is `1 / ndv(col)` with `ndv` over the **surviving** partitions, so `|R|` and `ndv` have the same scope. |
| F4 | Range selectivity: per surviving partition, the fraction `(x - min) / (max - min)` for `<` and `(max - x) / (max - min)` for `>`, clamped to `[0, 1]`, weighted by the partition's row count. When `min == max`, the fraction is 1 or 0 depending on whether the single value satisfies the predicate. `STRING` columns and partitions without statistics use `1/3`. |
| F5 | Several filters on one table multiply their selectivities (independence assumption). |
| F6 | `equiJoinRows(leftRows, rightRows, leftNdv, rightNdv)` = `leftRows · rightRows / max(leftNdv, rightNdv)`; 0 when both NDVs are 0 (an empty input). |

### Non-functional
- Pure, stateless, no logging: the planner logs the estimates it uses (§4, §5).
- Estimates are `double`, so a chain of joins is not distorted by rounding each step; callers round when logging.
- Invalid arguments (null lists, negative counts, unknown column, non-numeric constant on a numeric column) throw
  `IllegalArgumentException` without logging (AGENTS.md §5.4.3).
- No new package cycle: `optimizer` reads `engine` and `schema` records; neither imports `optimizer`.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `optimizer/CardinalityEstimator.java` *(new)* | `baseTableRows`, `columnNdv`, `filterSelectivity`, `filteredTableRows`, `equiJoinRows`, constant `SELINGER_RANGE_SELECTIVITY = 1/3`. `baseTableRows` sums with a stream (`mapToLong(...).sum()`); `filteredTableRows` multiplies the selectivities with `mapToDouble(...).reduce(1.0, ...)` via a private helper `selectivityOf`, matching the formula `\|R\| · ∏ selᵢ` (AGENTS.md §3.2.7). Sequential streams only: a table has at most dozens of partitions and a few filters, so `parallel()` would cost more than it saves. |
| `test/.../CardinalityEstimatorTest.java` *(new)* | §8.2: hand-made `Partition`s produce the expected base, filtered and join estimates. |
| `docs/optimizer-design.md` | Range rule decided (interpolate, 1/3 fallback); NDV scope = surviving partitions; equi-join row with its source; independence of filters; intermediate-result NDV assumption. |

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

Expected: `CardinalityEstimatorTest` green, all existing Surefire and Failsafe tests still green, `BUILD SUCCESS`.
