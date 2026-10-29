# Implementation Plan — Exercise 8.3: The Two-Writer Tests

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise8.md` §3 "The two-writer test"](../../exercise_descriptions/Exercise8.md) and [§5 "Required tests" (tests 2–4)](../../exercise_descriptions/Exercise8.md)
- Design decisions: [`docs/transactions-design.md`](../../docs/transactions-design.md) §2 (Commit Atomicity), §3 (Snapshot), §8 (Validate at commit)
- Prerequisite plans: [`exercise8_1_validate_at_commit.md`](exercise8_1_validate_at_commit.md), [`exercise8_2_commit_hook.md`](exercise8_2_commit_hook.md)

## 2. Background & Objective

8.1 made a commit on a moved base abort with `ConflictException`, inside a per-table critical section
(`catalogs.compute`). 8.2 added the `beforeCommit` seam, which runs before validation and outside that section.
The race itself has only been tested without threads so far (`ConflictTest` uses a stale `snapshot`).

8.3 runs the real race on two threads and forces the interleaving with two latches and the hook, no sleeps:

1. The loser starts on a worker thread, takes its snapshot, writes its partitions, and parks in `beforeCommit`.
2. The test thread resets the hook and runs the winner, which commits and moves the version.
3. The test releases the loser into validation. It must fail with `ConflictException`.

**Objective:** add required tests 2, 3 and 4 as one deterministic test class. No production change is expected:
the engine already validates, serializes the commit and discards the loser's partitions.

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | Test 2: `DELETE` parked, `COPY` commits, the `DELETE`'s future fails with an `ExecutionException` whose cause is `ConflictException`. |
| F2 | Test 3: the same race with two `COPY` statements; the parked one aborts the same way. |
| F3 | Test 4: after the abort the table equals exactly the winner's result (version, rows, partition files on disk, no `.tmp-` files), and rerunning the loser's statement on the fresh base succeeds. |
| F4 | The log tells the story: within the test's session, the winner's `statement=COPY … version=2` line and the loser's `ERROR` line carry two different `threadId`s, and no `version=2` line comes from the loser's thread. |

### Invariants
- **I1 Determinism:** no `Thread.sleep`; every `await` (in the hook and in the test) has a timeout, so a broken
  engine fails the test instead of hanging CI.
- **I2 Statements, not engine calls:** both writers run SQL through `Executor`, the same path as the CLI. Each
  thread gets its own `Executor` (the planner keeps per-statement state), sharing one `StorageEngine`.
- **I3 Seam access:** `beforeCommit` is package-private, so the test lives in `src/test/java/datasys/semi/engine/`.
- **I4 Hook reset:** the test resets the hook to a no-op before the winner runs; the parked loser keeps the hook
  it already read (8.2, I2).
- **I5 Cleanup:** the worker pool is shut down in `@AfterEach`; the hook's latch wait throws on timeout so a stuck
  loser never commits by accident.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `src/test/java/datasys/semi/engine/TwoWriterConflictTest.java` *(new)* | Setup: `CREATE TABLE trips` + `COPY` (v1, 8 rows, 4 partitions of 2). Helper `raceParkedLoserAgainstWinner(loserSql, winnerSql)` performs the §3 choreography and returns the loser's `ExecutionException`. Helpers to read the table's sorted rows, list partition and temp files, and await a latch with a timeout. |
| `docs/transactions-design.md` | No change; §8 already records the abort-and-delete decision. |
| Production code | No change expected. If the tests expose a race, fix it in `StorageEngine` and record it in the walkthrough. |

### Test cases
| Test | Scenario & assertions |
|------|-----------------------|
| `parkedDeleteAbortsWhenCopyCommitsFirst` (test 2) | Loser `DELETE FROM trips WHERE distance = 95;`, winner `COPY trips FROM …`. Cause is `ConflictException` with `base=1 current=2`. |
| `parkedCopyAbortsWhenCopyCommitsFirst` (test 3) | Loser and winner both `COPY`. Cause is `ConflictException` with `base=1 current=2`. |
| `abortedDeleteLeavesWinnerResultAndSucceedsOnRetry` (test 4) | After the test 2 race: version 2, rows equal v1's rows twice (exactly the winner's `COPY`), partition files equal those right after the winner, no temp files. Rerunning the `DELETE` commits v3 with 14 rows and no `distance = 95`. |
| `abortedCopyLeavesWinnerResultAndSucceedsOnRetry` (test 4) | After the test 3 race: same winner-state checks. Rerunning the `COPY` commits v3 with 24 rows. |
| `raceLogShowsWinnerVersionAndLoserErrorOnDifferentThreads` (F4) | Both threads set the same `sessionId` in MDC. In `logs/engine.log`: a `statement=COPY` line with `version=2` and an `ERROR` line with `conflict table=trips base=1 current=2`, on different thread ids; no `version=2` debug line from the loser's thread. |

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

Expected: the five new tests pass, all existing tests stay green, 0 failures and 0 errors. Running the new class
repeatedly (`mvn -B test -Dtest=TwoWriterConflictTest -Dsurefire.rerunFailingTestsCount=0` a few times) stays
green, confirming determinism.
