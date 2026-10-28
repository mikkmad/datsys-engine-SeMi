# Walkthrough — Exercise 7.4: DELETE as Copy-on-Write, Reader Pinning & Partition GC

## 1. Summary of Work Accomplished

- `DELETE FROM t [WHERE p]` now runs end to end. It rewrites exactly the partitions whose min/max summaries say they may contain a match, carries every other partition over by reference, and commits the new partition list together with version + 1 in one atomic swap. No partition file is ever modified in place.
- The rewrite set comes from `Planner.partitionsThatMayMatch`, the same helper `SELECT` prunes with.
- Every statement that reads a snapshot (`COPY`, `SELECT`, `DELETE`, `executeQuery`) now pins its version with a reader reference count and releases it in a `finally` block.
- Partition files are garbage-collected once no retained version (newest 10) and no pinned version lists them.
- At startup, the engine sweeps orphaned partition files and leftover `*.tmp-*` files left by a crash.
- `docs/transactions-design.md` §4, §5 and §7 are updated to describe these decisions.

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `planner/Planner.java` | New public `partitionsThatMayMatch(Catalog, Predicate)`. Both `planWithPredicate` (SELECT) and the new `planDelete(DeleteStatement, Catalog)` call it. Without `WHERE`, `planDelete` returns every partition and `row -> true`. |
| `planner/DeletePlan.java` *(new)* | Record holding `rewriteSet` (`List<Integer>`) and `rowMatcher` (`Predicate<Object[]>`, the bound predicate's `matches`). |
| `engine/DeleteResult.java` *(new)* | Record holding `rowsDeleted`, `partitionsRewritten`, `partitionsCarried` and `version`. |
| `engine/StorageEngine.java` | Adds `deleteRows(snapshot, rewriteSet, rowMatcher)`, `Catalog.withPartitions`, `pinSnapshot`/`releaseSnapshot`/`pinnedReaderCount`, an in-memory retention window (`retainedVersions`), reader counts (`pinnedVersions`), garbage collection in `commitCatalog` and `releaseSnapshot`, and the startup sweep in `loadCatalogs`. |
| `executor/Executor.java` | `executeDelete` plans the delete, calls `deleteRows`, and logs the summary line. `runPinned` wraps snapshot statements in pin/release. |

**Copy-on-write algorithm (`rewriteAndCommit`).** For each partition of the pinned snapshot:
- **Not in the rewrite set:** carried over (`decision=CARRIED reason=pruned`).
- **In the rewrite set:** the partition is read and its rows are filtered with `rowMatcher.negate()`. Then:
  - **0 rows deleted:** the original entry is carried over (`reason=noMatch`, design §5).
  - **Some rows survive:** they are written to a new `partition-<uuid>.dat`, with statistics computed as in `COPY` (`decision=REWRITTEN`).
  - **All rows deleted:** the partition is dropped.
- The new list is committed with `commitCatalog(snapshot, snapshot.withPartitions(...))`.
- If the commit fails, `discardWrittenPartitions` deletes the new files.

**Pinning & GC protocol.** `pinSnapshot`, `releaseSnapshot`, and the validate/publish/advance-window steps of `commitCatalog` all run inside `catalogs.compute(table, …)`, so they are serialized per table.
- `collectUnreachablePartitions` takes the candidate paths (those of versions that left the window, or of a version whose last reader just left) and subtracts the paths of every retained and every pinned version.
- Files are deleted after the lock is released. GC failures are logged at `error` and swallowed.

**Summary log line:**
```
statement=DELETE table=trips rowsDeleted=6 partitionsRewritten=2 partitionsCarried=0 version=3 durationMs=5
```

## 3. Verification Results

`mvn -B clean verify`:

```
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.ExecutorTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.DeleteTest
[INFO] Tests run: 77, Failures: 0, Errors: 0, Skipped: 0          (Surefire)
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.StorageEngineIT
[INFO] Tests run: 164, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.SqlCorpusIT
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.BinderIT
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.EngineIT
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.EngineLogIT
[INFO] Tests run: 200, Failures: 0, Errors: 0, Skipped: 0         (Failsafe)
[INFO] BUILD SUCCESS
```

A manual run of `./engine -f` with `CREATE`, `COPY trips.csv`, `COPY trips_sorted.csv`, `DELETE … WHERE distance > 150` and `SELECT *` printed 10 rows (16 loaded − 6 deleted) and logged `version=0..3` on the summary lines.

| Required test (Exercise 7 §5) | Test |
|---|---|
| 1. Parser | `SqlParserTest.deleteWithAndWithoutWhereParsesToExpectedAst` (added in 7.4); printer round-trip in `SqlPrinterTest.roundTripDeleteStatementsWithoutWhere` / `roundTripDeleteStatementsWithWhereComparisonsAndTypes` (from 7.3) |
| 2. Binder | `BinderIT.deleteUnknownTableThrowsIllegalArgumentException`, `deleteUnknownColumnInWhereThrowsIllegalArgumentException`, `deleteTypeMismatchedConstantInWhereThrowsIllegalArgumentException` (from 7.3) |
| 3. Versions | `ExecutorTest.createCopyCopyDeleteProduceVersionsZeroThroughThree` |
| 4. Rewrite set | `DeleteTest.selectiveDeleteRewritesExactlyPartitionsThatCanMatch` |
| 5. Restart | `DeleteTest.freshEngineSeesPostDeleteVersion` |
| 6. Integration | `EngineIT.deleteScriptReturnsCopiedRowsMinusDeletedRows` |

## 4. Edge Cases & Invariants Tested

- **Shared helper:** for the same predicate, the DELETE rewrite set equals the SELECT scan's partition numbers (`rewriteSetMatchesSelectPruningForSamePredicate`).
- **Carried partitions:** they keep their exact catalog entries and positions, and the replaced partition's original file bytes are unchanged (`selectiveDeleteRewritesExactlyPartitionsThatCanMatch`).
- **Candidate without a match:** it is carried over, no new file is written, and a new version is still committed (`candidateWithoutMatchIsCarriedOverAndStillCommitsNewVersion`).
- **Fully matched partition:** it is dropped (`fullyMatchedPartitionIsDroppedFromCatalog`).
- **`DELETE` without `WHERE`:** it empties the table (`deleteWithoutWhereRemovesEveryRow`).
- **Stale snapshot:** the `DELETE` throws `IllegalStateException`, the version is kept, and no new partition files remain (`staleSnapshotDeleteFailsAndLeavesNoNewPartitionFiles`).
- **Replaced partition:** it survives while version 1 is retained and is deleted when version 11 pushes version 1 out of the window (`replacedPartitionIsDeletedOnceItLeavesRetentionWindow`).
- **Pinned version outside the window:** its files stay on disk and readable while two readers pin it, and are deleted only when the second reader releases (`pinnedVersionKeepsItsPartitionsUntilLastReaderReleases`).
- **Unpinned snapshot:** releasing it throws `IllegalStateException` (`releasingUnpinnedSnapshotThrowsIllegalStateException`).
- **Startup sweep:** it removes a planted orphan `.dat` file and `*.tmp-*` files in `data/trips/` and `catalogs/`, and keeps every referenced partition (`startupSweepRemovesOrphanedAndTemporaryFiles`).
- **DELETE output:** it writes nothing to stdout and logs `statement=DELETE … version=N` with the statement's `statementNumber` (`deleteLogsSummaryLineWithVersionAndWritesNothingToStdout`).
- **Readers released:** after a script, `pinnedReaderCount` is 0, so no pin leaks.
