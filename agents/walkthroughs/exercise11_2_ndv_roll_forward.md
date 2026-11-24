# Walkthrough — Exercise 11.2 (follow-up): NDV roll forward at engine startup

Plan: [`agents/implementation_plans/exercise11_2_ndv_roll_forward.md`](../implementation_plans/exercise11_2_ndv_roll_forward.md)

## 1. Summary of Work Accomplished

Partitions written before NDV was collected now get their exact NDVs when the engine starts. Before, they kept
`ndv = null` forever and the estimator always used their `rowCount`, which treats every column as unique. Now the
constructor reads each such partition once after the catalogs are loaded, counts and logs its NDVs like `COPY` does,
and commits the table's next version with them filled in. From the first statement on, the estimator uses the real
NDV.

This addresses the PR #102 item "the document and the code should agree". Rolling forward on the first scan, as the
design document words it, was considered and rejected. It would commit while statements are running, so a concurrent
`COPY` or `DELETE` could abort with `ConflictException`, and the first plan would still use the fallback. Startup has
neither problem, needs less code, and leaves `ScanOperator` unchanged.

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `engine/StorageEngine.java` | The constructor calls `rollForwardMissingNdvs()` after `loadCatalogs()`. `rollForwardTable` maps every partition through `fillMissingNdvs` and commits through `commitCatalog` only if something changed. `fillMissingNdvs` skips partitions without a missing NDV (no read), otherwise reads the partition and counts the missing columns with `countMissingNdvs` / `countColumnNdv`, which logs each count. New `Partition.hasMissingNdv()`, `Partition.withRolledForwardNdvs(Map)` (never overwrites a stored NDV, rejects NDVs outside `[1, rowCount]`) and `Statistics.withNdv(long)`. |
| `optimizer/CardinalityEstimator.java` | The `partitionNdv` Javadoc now says what the fallback is for: a partition whose startup roll forward failed. The old text pointed to a fallback description that does not exist in the design document. |
| `NdvCollectionTest` | `partitionWithoutNdvFallsBackToRowCount` builds the old-style `Partition` directly, because a restart now fills the NDV. It also checks that the JSON leaves out a `null` NDV and does not serialize `hasMissingNdv`. |
| `NdvRollForwardTest` *(new)* | 7 tests, see §4. |

Design points:
- **No writer conflicts.** The commit runs inside the constructor, before any statement can pin a snapshot.
- **Idempotent and cheap after the first run.** Only `null` NDVs are filled in. A table with nothing missing is not read
  and gets no new version, so later startups only scan the statistics already in memory.
- **Best effort, per partition.** A partition that cannot be read keeps its fallback and the others are still filled
  in. A failed commit leaves the table at its version. Each failure leaves one `ERROR` line, and the engine still opens;
  the next startup tries again.
- **Cost.** The first startup after the upgrade reads every old partition once.
- **Open item for the design document.** `docs/optimizer-design.md` still says "on the first scan" and should say "at
  engine startup". It is left unchanged because it is the user's document.

## 3. Verification Results

```
$ mvn clean compile
BUILD SUCCESS

$ mvn -B verify
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.NdvRollForwardTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.NdvCollectionTest
[INFO] Tests run: 209, Failures: 0, Errors: 0, Skipped: 0          (Surefire)
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.JoinTestbedIT
[INFO] Tests run: 208, Failures: 0, Errors: 0, Skipped: 0          (Failsafe)
[INFO] BUILD SUCCESS
```

## 4. Edge Cases & Invariants Tested

| Test | Scenario |
|------|----------|
| `restartCommitsExactNdvsForLegacyPartitions` | A restart on an old catalog commits version 2 with exact NDVs (2/3/2 and 1/1/1). |
| `rolledForwardNdvIsPersistedAndNotCommittedAgain` | `ndv` is in the v2 catalog file, and a second restart stays at version 2. |
| `tableWithNdvsIsNotCommittedAtStartup` | A table written by a current `COPY` is not touched. |
| `estimatorUsesRolledForwardNdvBeforeAnyScan` | `CardinalityEstimator.columnNdv` returns 3 distinct cities before any scan, not the 7-row fallback. |
| `unreadablePartitionKeepsFallbackAndEngineStillOpens` | A deleted partition file keeps `null` and its `rowCount` fallback, the other partition is filled in, and the engine opens. |
| `rolledForwardNdvIsLogged` | One `table=… partition=… column=… rows=… ndv=…` DEBUG line per column (6) and one commit line. |
| `rollForwardRejectsNdvAboveRowCount` | The invariant `ndv <= rowCount` is enforced. |
| `NdvCollectionTest.partitionWithoutNdvFallsBackToRowCount` | The fallback still works on an old-style record, and its JSON omits `ndv` and `hasMissingNdv`. |
