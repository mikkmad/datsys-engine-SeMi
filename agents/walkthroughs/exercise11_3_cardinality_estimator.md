# Walkthrough — Exercise 11.3: The Estimator

Plan: [`agents/implementation_plans/exercise11_3_cardinality_estimator.md`](../implementation_plans/exercise11_3_cardinality_estimator.md)

## 1. Summary of Work Accomplished

`optimizer/CardinalityEstimator` turns catalog numbers into the three estimates of Exercise 11 §3. They are pure
functions over `Partition` records, so they can be tested without data files:

- **Base table**: the sum of the row counts of the surviving partitions.
- **Filter**: equality keeps `|R| / ndv(col)`. `<`/`>` interpolate between each surviving partition's own min/max,
  weighted by its row count. `STRING` columns and partitions without statistics fall back to Selinger's `1/3`.
  Several filters multiply (independence).
- **Equi-join**: `|R| · |S| / max(ndv(R.a), ndv(S.b))`, and 0 when both inputs are empty.

`|R|` and `ndv` are both computed over the surviving partitions. The planner is unchanged; it starts using the
estimator in §4.

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `optimizer/CardinalityEstimator.java` *(new)* | `baseTableRows`, `columnNdv` (via `NdvMerger.tableNdv`; a missing NDV or missing column statistics count as `rowCount`), `filterSelectivity`, `filteredTableRows`, `equiJoinRows`, `SELINGER_RANGE_SELECTIVITY`. Stateless, no logging, `double` estimates. |
| `CardinalityEstimatorTest.java` *(new)* | 20 unit tests on hand-made statistics (§8.2). |
| `docs/optimizer-design.md` | Estimator table now includes per-partition interpolation, the 1/3 fallback, independence and the equi-join row; NDV scope = surviving partitions; intermediate-result NDV assumption; sources (Selinger et al. 1979, Lecture 10). |

Dependencies: `optimizer` → `engine`, `models`, `schema`. None of these imports `optimizer`, so there is no cycle.

## 3. Verification Results

`mvn -B clean compile` and `mvn -B verify`:

| Phase | Tests run | Failures | Errors | Skipped |
|-------|-----------|----------|--------|---------|
| Surefire (unit) | 181 | 0 | 0 | 0 |
| — `CardinalityEstimatorTest` | 20 | 0 | 0 | 0 |
| Failsafe (integration) | 205 | 0 | 0 | 0 |

`BUILD SUCCESS`

## 4. Edge Cases & Invariants Tested

| Test | Scenario |
|------|----------|
| `baseTableIsSumOfSurvivingPartitionRows`, `baseTableWithoutSurvivingPartitionsIsZero` | 100 + 250 = 350; everything pruned = 0. |
| `columnNdvMergesSurvivingPartitionNdvs`, `missingNdvCountsAsRowCount` | Sum rule over surviving partitions; a pre-11.2 partition counts with its row count. |
| `equalityFilterKeepsRowsDividedByNdv` | 400 rows, NDV 20 → 20. |
| `lessThanInterpolatesBetweenMinAndMax`, `greaterThanInterpolatesBetweenMinAndMax` | `[0, 100]`, `< 25` → ¼, `> 25` → ¾. |
| `rangeFilterInterpolatesPerPartition` | `[0, 40]` fully kept and `[40, 60]` halved for `< 50` → 150 of 200. |
| `rangeFilterOnSingleValuePartitionKeepsAllOrNothing`, `rangeFractionIsClampedToZeroAndOne` | `min == max`; constants outside the range. |
| `doubleColumnInterpolates` | `DOUBLE` statistics are parsed and interpolated. |
| `stringRangeFilterUsesSelingerConstant`, `partitionWithoutColumnStatisticsFallsBack` | 1/3 fallback; equality on missing stats uses `1/rowCount`. |
| `multipleFiltersMultiplySelectivities`, `noFiltersKeepsBaseTableRows`, `filtersOnEmptyInputEstimateZero` | Independence (1000 · 1/10 · 1/2 = 50); no filter; no division by zero. |
| `equiJoinDividesByLargerNdv`, `equiJoinWithEmptyInputIsZero` | Symmetric formula; both NDVs 0 → 0. |
| `filteredTableJoinComposesEstimates` | Filtered table (50) joined with 20 rows on NDV 10 → 100. |
| `invalidInputThrowsIllegalArgumentException` | Null list, unknown column, non-numeric constant, negative or NaN inputs. |
