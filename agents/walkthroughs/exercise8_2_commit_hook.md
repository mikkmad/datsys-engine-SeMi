# Walkthrough — Exercise 8.2: Commit Hook (Test Seam) & Snapshot Read

## 1. Summary of Work Accomplished

- Added the `beforeCommit` seam to `StorageEngine`: a package-private `volatile Runnable` that defaults to a no-op
  and runs before every commit's validation, outside the commit critical section.
- Added `CommitHookTest`, four single-threaded tests that prove the seam's placement and failure behavior.
- Added `SnapshotReadTest`, Exercise 8 required test 1: a plan opened on a pinned version and drained after a
  `DELETE` returns the pinned version's rows, with no threads.
- Plan: [`exercise8_2_commit_hook.md`](../implementation_plans/exercise8_2_commit_hook.md).

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `engine/StorageEngine.java` | New `// --- Test Seams ---` group with `volatile Runnable beforeCommit = () -> { };`. `commitCatalog` reads it once into a local and runs it after `writeTemporaryCatalog(...)`, before `catalogs.compute(...)`. Javadoc updated. |
| `src/test/java/datasys/semi/engine/CommitHookTest.java` *(new)* | First test class in the engine package, so it can reach the package-private field. |
| `src/test/java/datasys/semi/SnapshotReadTest.java` *(new)* | Required test 1. |

**Hook placement.** The hook runs after the temporary catalog file is written and before validation, outside
`catalogs.compute`.

- If it ran inside `compute`, a parked writer would hold the table's per-key lock. The winning writer in §3
  could then never commit, so the test would hang until its latch timeout.
- A commit started from inside the hook would also fail with `ConcurrentHashMap`'s "Recursive update".
  `commitInjectedIntoHookMakesOuterCommitConflict` proves neither happens: the injected commit succeeds from
  inside the hook.

**Visibility.** A test thread assigns the field while writer threads read it, so it's `volatile`. It's the one
non-`final` field here, a deliberate exception to AGENTS §3.2.4. `commitCatalog` copies it into a local before
running it, so a writer parked inside the hook keeps the hook it started with even after the test resets the
field (§3: "the parked writer holds the old hook").

**Failure path.** A hook that throws goes through the same cleanup as any other commit failure. `commitCatalog`'s
`finally` deletes the temporary catalog file, and `copyFile`/`deleteRows` delete their partitions through the
`committed` flag. No new cleanup code was needed.

**Snapshot read.** No production change was needed. `ScanOperator` holds its own immutable `Catalog` and resolves
partition indices against `snapshot.partitions()`, so the scan carries its snapshot's partitions and row counts
rather than indices into the live catalog. The test pins the version, so the replaced partition file can't be
garbage-collected mid-scan.

**Deviation from the plan.** The planned "Test seam" paragraph in `docs/transactions-design.md` §8 was left out.
That document holds the authors' own design answers and was deliberately trimmed after 8.1; this walkthrough
records the reasoning instead.

## 3. Verification Results

```
$ mvn clean compile
BUILD SUCCESS

$ mvn -B verify
Surefire (unit):        Tests run: 89,  Failures: 0, Errors: 0, Skipped: 0
  CommitHookTest        Tests run: 4,   Failures: 0, Errors: 0, Skipped: 0
  SnapshotReadTest      Tests run: 1,   Failures: 0, Errors: 0, Skipped: 0
Failsafe (integration): Tests run: 200, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 4. Edge Cases & Invariants Tested

| Test | Scenario |
|------|----------|
| `CommitHookTest.hookRunsBeforeEveryCommit` | A counting hook runs exactly once for one `COPY`. |
| `CommitHookTest.commitInjectedIntoHookMakesOuterCommitConflict` | The hook resets itself (no recursion), then commits a `COPY`. The outer `COPY` fails with `conflict table=trips base=0 current=1`; the table holds only the injected rows; the partition files on disk equal exactly the catalog's; no `.tmp-` files. |
| `CommitHookTest.throwingHookAbortsCommitWithoutVisibleChange` | The hook's exception propagates unchanged (`assertSame`); version 0; no partitions in the catalog or on disk; no temp files. |
| `CommitHookTest.defaultHookIsNoOp` | Two commits with the default hook reach version 2 with 16 rows. |
| `SnapshotReadTest.planOpenedBeforeDeleteReturnsPinnedVersionRows` | Plan opened on pinned v1 and one row read; `DELETE … distance = 95` commits v2; the drained plan returns all 8 v1 rows including `Odense,95,120.75`; a new query returns 7 rows without it. |
