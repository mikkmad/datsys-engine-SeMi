# Walkthrough — Exercise 8.3: The Two-Writer Tests

## 1. Summary of Work Accomplished

- Added `TwoWriterConflictTest`, which covers Exercise 8 required tests 2, 3 and 4 plus the log story from §3. Two
  writers race on two real threads, and the order is forced with the `beforeCommit` seam and two latches. There are
  no sleeps, and every wait has a timeout.
- No production change was needed. 8.1's validation inside `catalogs.compute` and 8.2's hook outside it were
  enough. The tests confirm that the engine handles the real threaded race the same way `ConflictTest` showed for
  a stale snapshot.
- Plan: [`exercise8_3_two_writer_tests.md`](../implementation_plans/exercise8_3_two_writer_tests.md).

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `src/test/java/datasys/semi/engine/TwoWriterConflictTest.java` *(new)* | Five tests and the shared `raceParkedLoserAgainstWinner` choreography. |

**Placement.** `beforeCommit` is package-private, so the test lives in `datasys.semi.engine`. This is the first
test class in that package. It reuses `datasys.semi.UtilsTest.copyResource`.

**Choreography** (`raceParkedLoserAgainstWinner`), following §3:

1. Install a hook that counts down `parked` and then waits on `winnerDone`.
2. Submit the loser's SQL to a single-thread pool. It pins version 1, writes its partitions, and parks in the hook.
3. The test thread waits on `parked`, resets the hook to a no-op, and runs the winner's SQL, which commits
   version 2.
4. Assert that the loser's future is not done yet, which proves it really was parked. Then count down `winnerDone`.
5. `loser.get(timeout)` throws `ExecutionException`, and the caller checks that its cause is `ConflictException`.

Both writers run SQL through `Executor`, the same path as the CLI. Each thread has its own `Executor` because the
planner keeps per-statement state, and both share one `StorageEngine`. The hook's latch wait throws
`IllegalStateException` on a timeout or an interrupt, so a loser that is never released aborts and never commits.
`@AfterEach` calls `shutdownNow()` on the pool.

**What "equals the winner's result" means.** The test checks version 2, and checks that the sorted rows are exactly
version 1's rows twice, which is the winner's `COPY` of the same CSV applied to v1. It also checks that the `.dat`
files on disk are exactly the ones version 2 references, so the loser's partitions were deleted (design §8). No
`.tmp-` files may remain.

## 3. Verification Results

```
$ mvn -B test -Dtest=TwoWriterConflictTest     (run 5 times)
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0   ×5

$ mvn clean compile
BUILD SUCCESS

$ mvn -B verify
Surefire (unit):        Tests run: 90,  Failures: 0, Errors: 0, Skipped: 0
Failsafe (integration): Tests run: 200, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Console excerpt from the race before the §5 fix (the loser's `ERROR` lines; the `StorageEngine` line is gone now):

```
ERROR Executor - statement_failed operation=DELETE reason=conflict table=trips base=1 current=2
ERROR StorageEngine - api=copyFile table=trips error=conflict table=trips base=1 current=2
ERROR Executor - statement_failed operation=COPY reason=conflict table=trips base=1 current=2
```

## 4. Edge Cases & Invariants Tested

| Test | Scenario |
|------|----------|
| `parkedDeleteAbortsWhenCopyCommitsFirst` (req. 2) | `DELETE … distance = 95` parked; `COPY` commits v2; the cause is `ConflictException` `conflict table=trips base=1 current=2`. |
| `parkedCopyAbortsWhenCopyCommitsFirst` (req. 3) | Two `COPY`s; the parked one aborts with the same message. |
| `abortedDeleteLeavesWinnerResultAndSucceedsOnRetry` (req. 4) | After the race the table is the winner's v2: 16 rows, only v2's partition files, no temp files. Rerunning the `DELETE` commits v3 with 14 rows and no `distance = 95`. |
| `abortedCopyLeavesWinnerResultAndSucceedsOnRetry` (req. 4) | Same winner-state checks; rerunning the `COPY` commits v3 with 24 rows. |
| `raceLogShowsWinnerVersionAndLoserErrorOnDifferentThreads` | Both threads share a `sessionId`. In `logs/engine.log` the winner's `statement=COPY … version=2` line and the loser's conflict `ERROR` line have different `threadId`s, and the loser's thread never logs `version=2`. |

| `abortedCopyLeavesExactlyOneErrorLineAtTheBoundary` | A failed `COPY` leaves exactly one `ERROR` line in its session: `Executor`'s `statement_failed operation=COPY reason=conflict …`. |
| `ConflictTest.conflictIsThrownWithoutErrorLineAtTheThrowSite` *(renamed, was `conflictLeavesErrorLineWithConflictMessage`)* | Calling `engine.copyFile` directly on a stale base throws `ConflictException` and writes no `ERROR` line. |

## 5. Follow-up Fix: One ERROR Line per Failed COPY

The race showed that a conflicting `COPY` left two `ERROR` lines. One came from a catch block in
`StorageEngine.copyFile` that logged and rethrew (`api=copyFile table=… error=…`); the other came from the
`Executor` boundary. That is the log-and-throw pattern AGENTS §5.4 forbids. The engine line added no context the
exception message didn't already carry: the conflict message names the table, and CSV read errors name the file.
It would also make week 9's `logLevel = 'ERROR'` analysis count every failed `COPY` twice. `DELETE` already
logged once.

- `StorageEngine.copyFile`: removed the `catch (RuntimeException)` that logged and rethrew. The `finally` that
  discards uncommitted partitions is unchanged.
- `ConflictTest`: the test that expected the engine-level `ERROR` line now asserts the opposite, as §5.4.5
  allows. The boundary line is checked by `abortedCopyLeavesExactlyOneErrorLineAtTheBoundary`.
- The 8.1 walkthrough still mentions the old `api=copyFile … error=conflict` line; it is left as a historical
  record.

Verification after the fix:

```
$ mvn clean compile
BUILD SUCCESS

$ mvn -B verify
Surefire (unit):        Tests run: 91,  Failures: 0, Errors: 0, Skipped: 0
Failsafe (integration): Tests run: 200, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
