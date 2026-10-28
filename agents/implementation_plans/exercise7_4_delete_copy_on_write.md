# Implementation Plan — Exercise 7.4: DELETE as Copy-on-Write, Reader Pinning & Partition GC

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise7.md` §4 "DELETE as copy-on-write"](../../exercise_descriptions/Exercise7.md) and [§5 "Required tests (JUnit 6)" (tests 3–6)](../../exercise_descriptions/Exercise7.md)
- Design decisions: [`docs/transactions-design.md`](../../docs/transactions-design.md) (§3 Snapshot, §4 Retention, §5 Rewrite Set, §6 New Version, §7 Partition Placement)
- Prerequisite plans: [`exercise7_2_versioned_catalog_repeated_copy.md`](exercise7_2_versioned_catalog_repeated_copy.md), [`exercise7_3_delete_front_end.md`](exercise7_3_delete_front_end.md)

## 2. Background & Objective

After 7.3, `DELETE FROM t [WHERE p]` parses, prints, and binds, but `Executor.executeDelete` throws
`UnsupportedOperationException`. The 7.2 plan also deferred design §4's per-version reader counts to this
sub-exercise: once `DELETE` replaces partitions, old partition files lose their last catalog reference and can
be reclaimed, but only once no running statement still reads them.

**Objective:**

1. Execute `DELETE` as copy-on-write: rewrite exactly the partitions that may contain matches (the complement
   of SELECT pruning, from the same `Planner` helper), carry every other partition over by reference, and
   commit the new partition list with an incremented version in one atomic swap.
2. Pin catalog versions with reader reference counts for the whole duration of every statement.
3. Garbage-collect partition files that no retained catalog version and no pinned version references.
4. Sweep orphaned partition files and temporary files left by a crash at engine startup.

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | `Planner.partitionsThatMayMatch(Catalog, Predicate)` is the single public pruning helper. SELECT reads the partitions it returns; DELETE rewrites them. |
| F2 | `Planner.planDelete(DeleteStatement, Catalog)` returns a `DeletePlan(rewriteSet, rowMatcher)`. Without `WHERE`, every partition is in the rewrite set and every row matches. |
| F3 | `StorageEngine.deleteRows(snapshot, rewriteSet, rowMatcher)`: partitions outside the rewrite set are carried over unchanged (same `Partition` entry, same path, same position). For each rewritten partition the non-matching rows are written to a new `partition-<uuid>.dat` summarized like `COPY`. |
| F4 | A candidate partition that turns out to hold no matching row is carried over, not copied (design §5). A partition whose rows all match is dropped from the new list (no empty partition file is written). |
| F5 | Every `DELETE` commits a new version, including one that deletes nothing (design §6). |
| F6 | Executor logs `statement=DELETE table=… rowsDeleted=… partitionsRewritten=… partitionsCarried=… version=… durationMs=…`. `DELETE` writes nothing to stdout. |
| F7 | `StorageEngine.pinSnapshot(table)` returns the current catalog and increments that version's reader count; `releaseSnapshot(catalog)` decrements it. The Executor pins at statement start and releases in `finally` for `COPY`, `SELECT`, `DELETE` and `executeQuery`. |
| F8 | The engine keeps the retained catalogs (newest 10 versions) of each table in memory. When a commit pushes a version out of the window, its partitions that no retained and no pinned version references are deleted. |
| F9 | When the last reader of a version outside the retention window releases it, that version's no-longer-referenced partitions are deleted. |
| F10 | On startup, for each table, `partition-*.dat` files referenced by no retained catalog and all `*.tmp-*` files in `data/<table>/` and `catalogs/` are deleted (crash orphans). |

### Invariants
- **I1 Immutability:** no partition file is ever modified. Rewritten partitions are new files; originals stay on disk until GC proves them unreachable.
- **I2 Atomic commit:** unchanged from 7.2: temp file + atomic rename on disk, then one reference swap inside `catalogs.compute`.
- **I3 Safe GC:** pinning, releasing and the retention-window update all run inside `catalogs.compute(table, …)`, so GC never sees a reader count of zero for a version a reader is about to pin. Only the current version can be newly pinned, and its files are never GC candidates. Runtime GC only deletes files it learned about from a catalog, never by listing the directory, so uncommitted files of an in-flight `COPY`/`DELETE` are never touched.
- **I4 Failed DELETE is harmless:** new partition files written by a failed `DELETE` are discarded; the table stays at its previous version.
- **I5 Logging:** only `debug`/`error`; no log-and-throw; GC deletion failures are logged at `error` and swallowed so housekeeping never rolls back a commit.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `planner/Planner.java` | Make pruning public as `partitionsThatMayMatch`; add `planDelete`. |
| `planner/DeletePlan.java` *(new record)* | `rewriteSet` (`List<Integer>`) and `rowMatcher` (`java.util.function.Predicate<Object[]>`). |
| `engine/DeleteResult.java` *(new record)* | `rowsDeleted`, `partitionsRewritten`, `partitionsCarried`, `version`. |
| `engine/StorageEngine.java` | `deleteRows`; `Catalog.withPartitions`; `pinSnapshot`/`releaseSnapshot`/`pinnedReaderCount`; in-memory retention window; GC on commit and on last release; startup sweep in `loadCatalogs`. |
| `executor/Executor.java` | Implement `executeDelete`; wrap snapshot-based statements in `runPinned`. |

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

| Test | Asserts (spec ref) |
|------|--------------------|
| `ExecutorTest.createCopyCopyDeleteProduceVersionsZeroThroughThree` | Versions 0..3; SELECT after each commit sees that commit's rows (§5 test 3). |
| `ExecutorTest.deleteLogsSummaryLineWithVersionAndWritesNothingToStdout` | `statement=DELETE … version=3` summary line, empty stdout. |
| `DeleteTest.selectiveDeleteRewritesExactlyPartitionsThatCanMatch` | Sorted data, `maxRowsPerPartition = 2`: rewrite set `[1]`, carried partitions keep their entries and positions, original file untouched (§5 test 4). |
| `DeleteTest.rewriteSetMatchesSelectPruningForSamePredicate` | Shared helper: DELETE rewrite set equals SELECT read set. |
| `DeleteTest.candidateWithoutMatchIsCarriedOverAndStillCommitsNewVersion` | Design §5/§6. |
| `DeleteTest.fullyMatchedPartitionIsDroppedFromCatalog`, `deleteWithoutWhereRemovesEveryRow` | F2/F4. |
| `DeleteTest.freshEngineSeesPostDeleteVersion` | §5 test 5. |
| `DeleteTest.staleSnapshotDeleteFailsAndLeavesNoNewPartitionFiles` | I4. |
| `DeleteTest.replacedPartitionIsDeletedOnceItLeavesRetentionWindow` | F8. |
| `DeleteTest.pinnedVersionKeepsItsPartitionsUntilLastReaderReleases` | F7/F9/I3. |
| `DeleteTest.startupSweepRemovesOrphanedAndTemporaryFiles` | F10. |
| `EngineIT.deleteScriptReturnsCopiedRowsMinusDeletedRows` | `-f` script with CREATE, COPY, COPY, DELETE, SELECT (§5 test 6). |
| `ExecutorTest.deleteExecutionThrowsUnsupportedOperationException…` | Removed: the stub no longer exists. |
