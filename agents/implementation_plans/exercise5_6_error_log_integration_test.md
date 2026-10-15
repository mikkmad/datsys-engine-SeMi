# Implementation Plan: Exercise 5 §6 (Required Tests)

## 1. Sub-Exercise Reference
Exercise 5, Section 6: Required tests (JUnit 6).
See [`exercise_descriptions/Exercise5.md`](../../exercise_descriptions/Exercise5.md).

## 2. Background & Objective
Exercise 5 makes the engine analyze its own log (`COPY logs FROM 'logs/engine.log'`). That analysis
is only useful if failures are reliably recorded. The required test is:

> Integration: after a failing statement, `logs/engine.log` contains an `ERROR` line for it.

The objective is an integration test that drives the real front door (`Engine.main`), lets a
statement fail, and then reads the live CSV log written by the Log4j2 `RollingFile` appender to
prove that an `ERROR` line exists for that specific failing statement.

## 3. Requirements & Invariants
- The test goes through `Engine.main` with a `-f` script, exactly as a user would.
- The failing statement must not be the first statement, so the test proves the `ERROR` line is
  attributed to the correct `statementNumber` (not just "some error happened").
- `logs/engine.log` is shared by every test and every manual run. The test must identify *its own*
  session without relying on line positions:
  - The failing statement references a uniquely named table (`missing_<uuid>`).
  - The `ERROR` line from `Engine` contains that table name, which yields the session's `sessionId`.
  - Within that session, an `ERROR` line with `statementNumber` equal to the failing statement's
    number must exist.
- Every log line must parse as the 7-column CSV schema
  `timestamp,sessionId,statementNumber,threadId,logLevel,className,logMessage`.
- The data directory is a JUnit `@TempDir` (via the `semi.data.dir` system property), so the test
  leaves no table state behind.

## 4. Proposed Changes
- **New** `src/test/java/datasys/semi/EngineLogIT.java` (Failsafe, `*IT.java`):
  - Test `failingStatementLeavesErrorLineInEngineLog()`.
  - Private record `LogLine` for one parsed CSV log row, with a `parse(String)` factory.
  - Private helpers to write the script, read and parse `logs/engine.log`, and look up the session id.
- No production code changes: `Executor` already logs
  `Execution failed at statementNumber=<n>` at `ERROR` with the statement's MDC number, and `Engine`
  logs `Engine execution failed: <message>` at `ERROR`.

## 5. Verification Plan
```bash
mvn clean compile
mvn -B verify
```
Expected: `EngineLogIT` runs 1 test with 0 failures and 0 errors; the full suite stays green.
