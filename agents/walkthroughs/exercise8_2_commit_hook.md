# Walkthrough — Exercise 8.2: Commit Hook (Test Seam) & Snapshot Read

## 1. Summary of Work Accomplished

- Added the `beforeCommit` seam to `StorageEngine`: a package-private `volatile Runnable` that defaults to a no-op
  and runs before every commit's validation, outside the commit critical section.
- Added `SnapshotReadTest`, Exercise 8 required test 1: a plan opened on a pinned version and drained after a
  `DELETE` returns the pinned version's rows, with no threads.
- Plan: [`exercise8_2_commit_hook.md`](../implementation_plans/exercise8_2_commit_hook.md).

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `engine/StorageEngine.java` | New `// --- Test Seams ---` group with `volatile Runnable beforeCommit = () -> { };`. `commitCatalog` calls `beforeCommit.run()` after `writeTemporaryCatalog(...)`, before `catalogs.compute(...)`. Javadoc updated. |
| `src/test/java/datasys/semi/SnapshotReadTest.java` *(new)* | Required test 1. |

**Hook placement.** The hook runs after the temporary catalog file is written and before validation, outside
`catalogs.compute`.

- If it ran inside `compute`, a parked writer would hold the table's per-key lock. The winning writer in §3
  could then never commit, so the test would hang until its latch timeout.
- A commit started from inside the hook would also fail with `ConcurrentHashMap`'s "Recursive update".

**Visibility.** A test thread assigns the field while writer threads read it, so it's `volatile`. It's the one
non-`final` field here, a deliberate exception to AGENTS §3.2.4. For the §3 choreography `volatile` is a
safeguard rather than a necessity: `executor.submit` and the latches already order the test's writes before the
writer's reads. It keeps the seam safe for tests that hand off differently, at the cost of one volatile read per
commit. `beforeCommit.run()` reads the field once and then invokes what it read, so a writer parked inside the
hook keeps the hook it started with even after the test resets the field (§3: "the parked writer holds the old
hook").

**Failure path.** A hook that throws goes through the same cleanup as any other commit failure. `commitCatalog`'s
`finally` deletes the temporary catalog file, and `copyFile`/`deleteRows` delete their partitions through the
`committed` flag. No new cleanup code was needed.

**Snapshot read.** No production change was needed. `ScanOperator` holds its own immutable `Catalog` and resolves
partition indices against `snapshot.partitions()`, so the scan carries its snapshot's partitions and row counts
rather than indices into the live catalog. The test pins the version, so the replaced partition file can't be
garbage-collected mid-scan.

**Deviations from the plan.**

- The planned "Test seam" paragraph in `docs/transactions-design.md` §8 was left out. That document holds the
  authors' own design answers and was deliberately trimmed after 8.1; this walkthrough records the reasoning
  instead.
- The planned `CommitHookTest` was written and then deleted by the authors' decision. The hook is a no-op field
  that production never sets, so a dedicated test adds little. It is covered indirectly by the existing commit
  tests (default no-op) and by the §3 two-writer tests, which park a writer in it.

## 3. Verification Results

```
$ mvn clean compile
BUILD SUCCESS

$ mvn -B verify
Surefire (unit):        Tests run: 85,  Failures: 0, Errors: 0, Skipped: 0
  SnapshotReadTest      Tests run: 1,   Failures: 0, Errors: 0, Skipped: 0
Failsafe (integration): Tests run: 200, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 4. Edge Cases & Invariants Tested

| Test | Scenario |
|------|----------|
| `SnapshotReadTest.planOpenedBeforeDeleteReturnsPinnedVersionRows` | Plan opened on pinned v1 and one row read; `DELETE … distance = 95` commits v2; the drained plan returns all 8 v1 rows including `Odense,95,120.75`; a new query returns 7 rows without it. |
