# Walkthrough — Exercise 8.1: Validate at Commit (`ConflictException`)

## 1. Summary of Work Accomplished

- A commit whose base version is no longer current now aborts with the new `ConflictException`. Its message is
  `conflict table=<table> base=<base> current=<current>`. Before this change it threw a generic
  `IllegalStateException("stale base version …")`.
- The commit critical section (validate, atomic rename, in-memory swap, retention update) was already serialized
  per table by `catalogs.compute`. It's now documented as the Exercise 8 commit section.
- Aborted statements still delete their partition files and temporary catalog file immediately (design §8). The
  empty-directory cleanup that runs on abort could race with a concurrent writer, so it's now guarded by a lock.
- Plan: [`exercise8_1_validate_at_commit.md`](../implementation_plans/exercise8_1_validate_at_commit.md).

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `engine/ConflictException.java` *(new)* | `RuntimeException` with `tableName()`, `baseVersion()`, `currentVersion()`. |
| `engine/StorageEngine.java` | `validateCommitBase` throws `ConflictException` on a moved version (and `IllegalStateException` only if the table vanished). New `tableDirectoryLock`: `reserveTemporaryFile` creates `data/<table>/` and the partition's temp file atomically; `discardWrittenPartitions` (now an instance method) checks for and removes an empty directory under the same lock via `removeDirectoryIfEmpty`. Javadoc of `copyFile`, `deleteRows`, `commitCatalog` and the class updated. |
| `ExecutorTest`, `DeleteTest` | Stale-base assertions now expect `ConflictException`. |
| `ConflictTest` *(new)* | Six deterministic, thread-free tests (below). |
| `docs/transactions-design.md` §8 | Conflict signal, commit serialization and the second race. |

**Logging.** Following AGENTS §5.4, the throw site doesn't log. The exception message is the conflict line, and
the existing boundaries record it once per boundary they already had:
`Executor` → `statement_failed operation=DELETE reason=conflict table=trips base=3 current=4` (carrying the
statement's `statementNumber`), and for `COPY` additionally `StorageEngine.copyFile`'s pre-existing
`api=copyFile … error=conflict …` line. The front door didn't change.

**Why the directory lock.** Writer A aborts and finds `data/trips/` empty. Meanwhile writer B has just run
`createDirectories` but hasn't created its file yet. A deletes the directory, and B's write fails with
`NoSuchFileException`. With the lock, B's directory creation and temp-file creation happen together, so A either
runs before (and B recreates the directory) or after (and sees a non-empty directory).

## 3. Verification Results

```
$ mvn clean compile
BUILD SUCCESS

$ mvn -B verify
Surefire (unit):       Tests run: 84,  Failures: 0, Errors: 0, Skipped: 0
  ConflictTest         Tests run: 6,   Failures: 0, Errors: 0, Skipped: 0
  DeleteTest           Tests run: 11,  Failures: 0, Errors: 0, Skipped: 0
  ExecutorTest         Tests run: 16,  Failures: 0, Errors: 0, Skipped: 0
Failsafe (integration): Tests run: 200, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 4. Edge Cases & Invariants Tested

| Test | Scenario |
|------|----------|
| `staleCopyThrowsConflictExceptionWithBaseAndCurrentVersion` | Exact message `conflict table=trips base=0 current=1` and accessor values. |
| `conflictingCopyLeavesNoVisibleChangeAndNoOrphans` | Version and rows remain the winner's; partition file set unchanged; no `*.tmp-*` file in `catalogs/` or `data/`. |
| `conflictingDeleteLeavesNoVisibleChangeAndNoOrphans` | Same for a `DELETE` whose rewritten partitions were already written before the conflict. |
| `abortedCopyCanBeRetriedOnFreshSnapshot` | A clean failure is retryable: the rerun commits v2 with both loads. |
| `conflictLeavesErrorLineWithConflictMessage` | Session log has an `ERROR` line carrying the conflict message. |
| `createExistingTableStillThrowsIllegalArgumentException` | Version-0 creation path isn't misreported as a conflict. |
| `ExecutorTest.commitWithStaleBaseVersionThrowsConflictException`, `DeleteTest.staleSnapshotDeleteFailsAndLeavesNoNewPartitionFiles` | Existing stale-base tests updated to the new type. |

Not tested here: the directory race is fixed by construction but has no deterministic test, because it would
need a seam inside `writePartitionFile`. The `beforeCommit` hook and the two-writer tests belong to §2–§3.
