# Implementation Plan — Exercise 11.2 (follow-up): NDV roll forward at engine startup

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise11.md` §1 "Roll forward"](../../exercise_descriptions/Exercise11.md)
- Design decision: [`docs/optimizer-design.md`](../../docs/optimizer-design.md), "Roll forward": a missing NDV is
  calculated so that every table has the statistic after it has been used once.
- Deferred by: [`exercise11_2_ndv_during_copy.md`](exercise11_2_ndv_during_copy.md) ("Roll forward (computing a missing
  NDV on first scan) is out of scope and gets its own sub-exercise")
- Raised in: [PR #102](https://github.com/mikkmad/datsys-engine-SeMi/pull/102), "Before merging": the "Roll forward"
  section says a missing NDV is computed, but the code only uses the partition's row count. The document and the code
  should agree.

## 2. Background & Objective

Exercise 11.2 collects the NDV of every column during `COPY` and `DELETE` rewrites. A partition written before that
release has `Statistics.ndv == null`, and `Partition.columnNdv` answers with the partition's `rowCount`. That is a safe
upper bound, but it is also the worst case for the estimator: the column looks unique, so an equality filter is
estimated at one row and an equi-join at the smaller input. Nothing ever replaces the fallback.

This change fills the missing NDVs **when the engine starts**. After the catalogs are loaded, every partition of a
table's current version that has a missing NDV is read once. Its exact NDVs are counted, and the table's next catalog
version is committed with them filled in.

### Why startup and not the first scan
The design document says "on the first scan". Doing it at startup instead is better for three reasons:

1. **No conflict with writers.** A roll forward is a commit. At runtime, a `COPY` or `DELETE` whose snapshot predates
   that commit would abort with `ConflictException` (first-committer-wins). At startup no statement holds a snapshot yet,
   so this cannot happen.
2. **The first plan is right too.** The planner estimates *before* a scan runs, so with the scan-time approach the first
   statement on an old table was still planned with the `rowCount` fallback.
3. **All partitions, less code.** Partitions that every query prunes are filled as well. A scan-time version would need
   a retry loop on conflict, merging into a newer catalog version, and changes to `ScanOperator`; startup needs none of
   these.

The cost is a one-time slower startup after the upgrade: every old partition is read once. After that, startup only
checks statistics that are already in memory.

`docs/optimizer-design.md` still says "on the first scan". It is the user's document, so this plan does not change it.
For the document and the code to agree (PR #102), its wording should be updated to "at engine startup".

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | At construction, after `loadCatalogs()`, the engine checks every table's current version for column statistics with a `null` NDV. |
| F2 | Only partitions with a missing NDV are read. A table without missing NDVs is not read and gets no new version. |
| F3 | Each rolled-forward NDV gets its own DEBUG line in the same format as during `COPY`: `table=… partition=… column=… rows=… ndv=…`. |
| F4 | One commit per table with missing NDVs, through `commitCatalog`. Schema, partition paths, row counts and min/max are unchanged. Stored NDVs are never overwritten. |
| F5 | Best effort: if a partition cannot be read, it keeps its `null` NDV (and the `rowCount` fallback) while the others are still filled in. If the commit fails, the table keeps its version. Each failure leaves one `ERROR` line, and the engine still opens. |

### Non-functional
- **I1** `1 <= ndv <= rowCount` holds for every rolled-forward NDV (`Partition.withRolledForwardNdvs` rejects anything
  else).
- **I2** Crash safety comes from `commitCatalog`, which already writes the catalog to a `.tmp` file and renames it
  atomically. A crash before the rename leaves the old version, and the next startup tries again.
- **I3** Roll back is unaffected. An older engine ignores `ndv` (`@JsonIgnoreProperties(ignoreUnknown = true)`).
- **I4** Only `debug` and `error` logging, and no log-and-throw.
- **I5** Two engine processes on the same data directory remain unprotected, as for every other commit; conflict
  detection is per process.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `engine/StorageEngine.java` | The constructor calls the new private `rollForwardMissingNdvs()` after `loadCatalogs()`. Helpers `rollForwardTable`, `fillMissingNdvs`, `countMissingNdvs` and `countColumnNdv`. New `Partition.hasMissingNdv()` and `Partition.withRolledForwardNdvs(Map)`, and `Statistics.withNdv(long)`. The `columnNdv` Javadoc says the fallback lasts until the next startup. |
| `optimizer/CardinalityEstimator.java` | The `partitionNdv` Javadoc now says what the fallback is for: a partition whose startup roll forward failed. |
| `NdvCollectionTest` | `partitionWithoutNdvFallsBackToRowCount` builds the old-style record directly, because a restart now fills the NDV. It also checks that the JSON leaves out a `null` NDV and `hasMissingNdv`. |
| `NdvRollForwardTest` *(new)* | See §5. |

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

New `NdvRollForwardTest` cases (an old catalog is simulated by removing `ndv` from the catalog JSON before a restart):

| Test | Expectation |
|------|-------------|
| `restartCommitsExactNdvsForLegacyPartitions` | Version 2 with exact NDVs (2/3/2 and 1/1/1). |
| `rolledForwardNdvIsPersistedAndNotCommittedAgain` | `ndv` is in the v2 catalog file, and a second restart stays at version 2. |
| `tableWithNdvsIsNotCommittedAtStartup` | A current table stays at version 1. |
| `estimatorUsesRolledForwardNdvBeforeAnyScan` | `CardinalityEstimator.columnNdv` returns 3, not the 7-row fallback. |
| `unreadablePartitionKeepsFallbackAndEngineStillOpens` | A deleted partition file keeps `null` and its `rowCount`; the other partition is filled in. |
| `rolledForwardNdvIsLogged` | 6 `ndv=` DEBUG lines and one commit line. |
| `rollForwardRejectsNdvAboveRowCount` | The invariant `ndv <= rowCount` is enforced. |

Expected: the new tests pass, the existing suites are unchanged, 0 failures and 0 errors.
