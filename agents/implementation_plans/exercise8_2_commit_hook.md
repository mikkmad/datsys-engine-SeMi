# Implementation Plan — Exercise 8.2: Commit Hook (Test Seam) & Snapshot Read

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise8.md` §2 "A hook for the test"](../../exercise_descriptions/Exercise8.md) and [§5 "Required tests" (test 1)](../../exercise_descriptions/Exercise8.md)
- Design decisions: [`docs/transactions-design.md`](../../docs/transactions-design.md) §3 (Snapshot), §8 (Validate at commit)
- Prerequisite plan: [`exercise8_1_validate_at_commit.md`](exercise8_1_validate_at_commit.md)

## 2. Background & Objective

Exercise 8.1 made a commit on a moved base version abort with `ConflictException`. To test the race
deterministically, a test must be able to park a writer between its snapshot and its commit, with no
`Thread.sleep`. §2 asks for a **seam**: a package-private `Runnable beforeCommit = () -> { };` that runs right
before a commit's validation. Production never touches it. §3's two-writer tests depend on it.

§2 also describes the **snapshot read test** (required test 1): open a `SELECT` plan, run a whole `DELETE` from
the same thread, then drain the scan. It must return the pinned version's rows. The design already supports this:
`ScanOperator` keeps its own immutable `Catalog` snapshot and resolves partition indices against
`snapshot.partitions()`, never against the live catalog (`operators/ScanOperator.java`, `bufferNextPartition`).
So the test only has to prove it with `DELETE`. The existing `ExecutorTest.pinnedSnapshotIsUnaffectedByLaterCommit`
covers `COPY` only.

**Objective:**

1. Add the `beforeCommit` seam to `StorageEngine`, running before validation and outside the commit critical section.
2. Prove the seam with single-threaded tests (injected commit, throwing hook, default no-op).
3. Add required test 1: snapshot read across a `DELETE`, with no threads.

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | `StorageEngine` has a package-private field `volatile Runnable beforeCommit = () -> { };` that defaults to a no-op. |
| F2 | `commitCatalog` reads the field **once** into a local and runs it after writing the temporary catalog file and **before** `catalogs.compute(...)`. That puts it before validation and **outside** the per-table critical section. |
| F3 | A hook that throws aborts the commit like any other failure: the temp catalog file is deleted by the existing `finally`, and `copyFile` and `deleteRows` discard their partitions through the existing `committed` flag. |
| F4 | Snapshot read: a plan opened on version N and drained after a `DELETE` commits N+1 returns exactly the rows of version N. |

### Invariants
- **I1 Outside the lock:** if the hook ran inside `catalogs.compute`, a parked writer would hold the table's
  lock. The winner in §3 could then never commit (deadlock until the latch timeout). A nested commit from the hook
  would also hit `ConcurrentHashMap`'s "Recursive update". Running it before `compute` avoids both.
- **I2 Visibility:** the test thread assigns the field while writer threads read it, so it is `volatile`. It
  can't be `final`; this is the one deliberate exception to AGENTS §3.2.4. Reading it once per commit gives §3's
  guarantee that "the parked writer holds the old hook".
- **I3 Zero production cost:** the no-op lambda isn't logged and changes no behavior.
- **I4 Scope:** the hook runs for every commit, `CREATE TABLE` included, matching "runs before a commit's
  validation". Tests set it only after `createTable`.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `engine/StorageEngine.java` | New field group `// --- Test Seams ---` with `volatile Runnable beforeCommit = () -> { };` and the spec's Javadoc plus notes on threading and placement. In `commitCatalog`, after `writeTemporaryCatalog(...)` and before `catalogs.compute(...)`: `Runnable hook = beforeCommit; hook.run();`. `commitCatalog` Javadoc updated. |
| `src/test/java/datasys/semi/engine/CommitHookTest.java` *(new)* | Same package as the engine so it can reach the package-private field; first test class in this package. Single-threaded seam tests (below). |
| `src/test/java/datasys/semi/SnapshotReadTest.java` *(new)* | Required test 1. Reuses `UtilsTest.copyResource`, `Planner`, `Executor` and the drain loop pattern from `ExecutorTest.pinnedSnapshotIsUnaffectedByLaterCommit`. |
| `docs/transactions-design.md` §8 | "Test seam" paragraph: placement outside the lock and why. |

Out of scope: the latch-based two-writer tests and the post-abort retry test (§3; §5 tests 2–4).

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

| Test | Asserts |
|------|---------|
| `CommitHookTest.hookRunsBeforeEveryCommit` | A counting hook reaches 1 after one `copyFile` (F1/F2). |
| `CommitHookTest.commitInjectedIntoHookMakesOuterCommitConflict` | The hook resets itself to a no-op, then commits a `copyFile` on the current version. The outer `copyFile` throws `ConflictException` (`base=0 current=1`); the table holds only the injected copy's rows; no orphan or `.tmp-` files. The nested commit succeeding proves I1. |
| `CommitHookTest.throwingHookAbortsCommitWithoutVisibleChange` | The hook throws `IllegalStateException`; the version is unchanged; no partition or temp files remain (F3). |
| `CommitHookTest.defaultHookIsNoOp` | A fresh engine commits normally (I3). |
| `SnapshotReadTest.planOpenedBeforeDeleteReturnsPinnedVersionRows` | No threads: `CREATE` + `COPY` (v1, 8 rows), `pinSnapshot`, plan `SELECT *`, `open()`, read the first row. Then `DELETE FROM trips WHERE distance = 95;` through `Executor` (v2). Drain the rest: 8 rows including `Odense,95`, while a fresh `executeQuery` returns 7. Pin released in `finally` (F4, required test 1). |
| Full suite | All existing tests stay green; 0 failures, 0 errors. |
