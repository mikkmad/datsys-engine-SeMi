# Implementation Plan — Exercise 8.1: Validate at Commit (`ConflictException`)

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise8.md` §1 "Validate at commit"](../../exercise_descriptions/Exercise8.md)
- Design decisions: [`docs/transactions-design.md`](../../docs/transactions-design.md) §2 (Commit Atomicity), §7 (Partition Placement), §8 (Validate at commit)
- Prerequisite plans: [`exercise7_2_versioned_catalog_repeated_copy.md`](exercise7_2_versioned_catalog_repeated_copy.md), [`exercise7_4_delete_copy_on_write.md`](exercise7_4_delete_copy_on_write.md)

## 2. Background & Objective

Exercise 7 already gives every DML statement a base version: `copyFile` and `deleteRows` take the pinned
`Catalog` snapshot, and `commitCatalog(base, next)` compares `base.version()` with the current version inside
`catalogs.compute`. A moved version currently throws a generic `IllegalStateException("stale base version …")`,
and partitions written by the failed statement are already deleted in a `finally` block (design §8).

**Objective:** turn the stale-base check into the first-committer-wins conflict check that Exercise 8 §1 specifies:

1. Throw a dedicated `ConflictException` (a `RuntimeException`) whose message is
   `conflict table=<table> base=<base> current=<current>`, so the boundary `ERROR` line carries it verbatim.
2. Keep validate, swap and save serialized in one critical section per table.
3. Keep "no visible change": the version stays put, and the aborted statement's partitions and temporary
   catalog file are deleted immediately.
4. Close the second race §1 hints at: with one file per partition there's no shared data file, but the
   abort cleanup deletes `data/<table>/` when it's empty. That can race with a concurrent writer that has just
   created the directory and is about to open its partition file.

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | New `datasys.semi.engine.ConflictException extends RuntimeException` with `tableName()`, `baseVersion()`, `currentVersion()` and message `conflict table=trips base=3 current=4`. |
| F2 | `commitCatalog(base, next)` throws `ConflictException` when `current.version() != base.version()`. Creating an existing table still throws `IllegalArgumentException`. |
| F3 | A conflicting `COPY` or `DELETE` leaves the table version unchanged, adds no partition files and leaves no `*.tmp-*` catalog file. |
| F4 | The conflict leaves one `ERROR` line through the existing boundaries (`Executor` `statement_failed … reason=conflict table=… base=… current=…`, `StorageEngine.copyFile`'s existing `api=copyFile` boundary). The front door is unchanged (spec: "The front door needs no change at all"). |
| F5 | Creating `data/<table>/` plus a partition's temporary file, and the empty-directory removal in abort cleanup, are mutually exclusive (one engine-wide lock), so an aborting writer never deletes a directory another writer is writing into. |

### Invariants
- **I1 Serialized commit:** validation, atomic rename of the catalog file (the save), in-memory swap and the
  retention-window update all run inside `catalogs.compute(table, …)`, which ConcurrentHashMap executes
  atomically per key: one committer per table at a time. Serializing the temporary catalog file *before* the
  critical section is safe because each writer uses a unique temp name and only a validated writer renames it.
- **I2 No log-and-throw (AGENTS §5.4):** `validateCommitBase` throws plainly; the message holds all identifying values.
- **I3 Retry-safe:** an aborted statement changes nothing, so rerunning it on a fresh snapshot succeeds.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `engine/ConflictException.java` *(new)* | Unchecked exception carrying table, base and current version. |
| `engine/StorageEngine.java` | `validateCommitBase` throws `ConflictException`; Javadoc of `copyFile`, `deleteRows`, `commitCatalog` updated; `tableDirectoryLock` guarding directory creation + temp file creation in `writePartitionFile` and the empty-directory removal in `discardWrittenPartitions`. |
| `ExecutorTest`, `DeleteTest` | Stale-base assertions switch from `IllegalStateException` to `ConflictException`. |
| `ConflictTest` *(new)* | Unit tests for F1–F4 (no threads; a stale snapshot creates the conflict deterministically). |
| `docs/transactions-design.md` | §8 gains the serialization and cleanup rationale. |

The `beforeCommit` hook (§2) and the two-writer tests (§3, §5) aren't part of this sub-exercise.

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

| Test | Asserts |
|------|---------|
| `ConflictTest.staleCopyThrowsConflictExceptionWithBaseAndCurrentVersion` | F1/F2: type, message `conflict table=trips base=0 current=1`, accessors. |
| `ConflictTest.conflictingCopyLeavesNoVisibleChangeAndNoOrphans` | F3 for `COPY`: version, rows, partition files, no temp catalog files. |
| `ConflictTest.conflictingDeleteLeavesNoVisibleChangeAndNoOrphans` | F3 for `DELETE`. |
| `ConflictTest.abortedCopyCanBeRetriedOnFreshSnapshot` | I3. |
| `ConflictTest.conflictLeavesErrorLineWithConflictMessage` | F4: `copyFile` boundary writes an `ERROR` line containing `conflict table=trips base=0 current=1`. |
| `ConflictTest.createExistingTableStillThrowsIllegalArgumentException` | F2 regression. |
| `ExecutorTest.commitWithStaleBaseVersionThrowsConflictException`, `DeleteTest.staleSnapshotDeleteFailsAndLeavesNoNewPartitionFiles` | Updated assertions. |
| Full suite | 0 failures, 0 errors. |
