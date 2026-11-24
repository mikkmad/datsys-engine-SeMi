# Walkthrough — Exercise 11.2: NDV during COPY

Plan: [`agents/implementation_plans/exercise11_2_ndv_during_copy.md`](../implementation_plans/exercise11_2_ndv_during_copy.md)

## 1. Summary of Work Accomplished

Every partition now stores the exact number of distinct values (NDV) of every column next to min/max. The NDV is
counted with a `HashSet` when `COPY` writes the partition, and when `DELETE` rewrites it. Each NDV is logged on its
own line. The merge rule from the design document (sum of partition NDVs) is a pure helper,
`NdvMerger.tableNdv`. A partition written before this release has no NDV and counts with its row count, which
keeps the table NDV an upper bound. Roll forward (filling in a missing NDV) is left for its own sub-exercise.

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `engine/StorageEngine.java` | `Statistics(String min, String max, Long ndv)` with `@JsonInclude(NON_NULL)`, so a missing NDV is left out of the JSON instead of written as `null`. New public `distinctCount(List<Object>)` next to `minMax`. `computeStatistics` now delegates to `computeColumnStatistics`, which logs min/max and then NDV. New `Partition.columnNdv(String)` returns the NDV or falls back to `rowCount`. |
| `optimizer/NdvMerger.java` *(new)* | `tableNdv(List<Long> partitionNdvs, long tableRowCount)` = `min(sum, tableRowCount)`. Pure, no logging, no dependency on other engine packages. |
| `docs/optimizer-design.md` | Exact counting now and HyperLogLog at a billion rows; the merge rule's cap holds by construction; what a missing NDV means. |
| `CatalogRollbackCompatibilityTest` | Uses `futureStatistic` instead of `ndv` as its unknown field, since `ndv` is now a known field. It still guards against the next version's fields. |

New log line, one per column per partition:

```
DEBUG,StorageEngine,table=ndvtrips partition=0 column=city rows=4 ndv=2
```

## 3. Verification Results

`mvn clean compile` and `mvn -B verify`:

| Phase | Tests run | Failures | Errors | Skipped |
|-------|-----------|----------|--------|---------|
| Surefire (unit) | 161 | 0 | 0 | 0 |
| Failsafe (integration) | 205 | 0 | 0 | 0 |
| — `NdvCollectionTest` | 7 | 0 | 0 | 0 |
| — `NdvMergerTest` | 5 | 0 | 0 | 0 |
| — `CatalogRollbackCompatibilityTest` | 3 | 0 | 0 | 0 |

`BUILD SUCCESS`

## 4. Edge Cases & Invariants Tested

| Test | Scenario |
|------|----------|
| `copyStoresExactPerPartitionNdvForEveryColumnType` | 7 rows with known duplicates over 2 partitions: partition 0 has NDV 2/3/2 for `STRING`/`LONG`/`DOUBLE`, partition 1 has 1/1/1 (§8.1). |
| `ndvIsPersistedInCatalog` | The NDVs survive an engine restart. |
| `eachNdvIsLoggedWhenCreated` | One `DEBUG` line per NDV with table, partition, column, rows and ndv; 6 lines for 2 partitions × 3 columns. |
| `deleteRewriteRecomputesNdv` | Deleting the only `distance=30` row rewrites partition 0 with `distance` NDV 2; the untouched partition is carried over unchanged. |
| `partitionWithoutNdvFallsBackToRowCount` | A catalog without `ndv` loads with `ndv == null` and `columnNdv == rowCount`. The next commit does not write `"ndv": null` for the old partitions, while new partitions get their NDV. |
| `columnNdvOfUnknownColumnThrows`, `distinctCountOfEmptyValuesThrows` | Invalid input throws `IllegalArgumentException`. |
| `NdvMergerTest` | Disjoint partitions sum exactly; shared values give an upper bound (3 + 3 = 6); the cap at the row count; empty table is 0; null list, null element, negative NDV and negative row count throw. |
