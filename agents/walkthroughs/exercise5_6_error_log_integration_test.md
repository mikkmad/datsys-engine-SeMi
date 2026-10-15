# Walkthrough: Exercise 5 §6 (Required Tests)

## 1. Summary of Work Accomplished
Added the required Exercise 5 integration test: after a failing statement, `logs/engine.log`
contains an `ERROR` line for it. The test drives `Engine.main -f` with a two-statement script whose
second statement selects from a missing table. It then reads the live CSV log and checks that this
run's session has an `ERROR` line tagged with `statementNumber=2`.

Also fixed `EngineIT.singleStatementExecutionProducesCsv`, which still called `Engine.main` with a
bare SQL string instead of `-c "<SQL>"`. Updated the `Engine.main` Javadoc to match.

## 2. Key Changes & Architecture
- **New** `src/test/java/datasys/semi/EngineLogIT.java`
  - `failingStatementLeavesErrorLineInEngineLog()`: runs the script through the front door with a
    `@TempDir` data directory.
  - **Session isolation:** `logs/engine.log` is shared with every other test and manual run. The
    failing statement references `missing_<uuid>`. The `Engine` `ERROR` line contains that name,
    which gives the session id, and the assertion only looks at lines from that session.
  - **Schema check:** each line from the session is parsed against the 7-column schema
    `timestamp,sessionId,statementNumber,threadId,logLevel,className,logMessage`. This also checks
    that `statementNumber` is numeric, so the line can be read by `COPY logs`.
  - Private `LogLine` record with a `parse` factory for one CSV row.
- No production code changes. The `ERROR` line for the statement is the existing `Executor` record
  `Execution failed at statementNumber=<n>`, logged while the statement's MDC number is still set.

## 3. Verification Results
`mvn -B verify`:

| Phase | Tests run | Failures | Errors |
|---|---|---|---|
| Surefire (unit) | 43 | 0 | 0 |
| Failsafe (integration) | 185 | 0 | 0 |
| — of which `EngineLogIT` | 1 | 0 | 0 |
| — of which `EngineIT` | 5 | 0 | 0 |

`BUILD SUCCESS`

Mutation check: temporarily expecting the error on statement 1 made the test fail with
`expected an ERROR line for statement 1 in session ...`. So the test checks which statement the
error is attributed to, not only that some error occurred.

## 4. Edge Cases & Invariants Tested
- **Failure is not the first statement:** the error has to be attributed to statement 2. A
  hard-coded or reset statement number would fail the test.
- **Shared, ever-growing log file:** older sessions, other tests' lines and historical stack-trace
  lines (from before the logging fix) are ignored. Only this run's session is parsed.
- **CSV hygiene for this session:** every line has exactly 7 columns and a numeric
  `statementNumber`.
- **Known limitation:** if the 10 MB `SizeBasedTriggeringPolicy` rolls the file during the test run,
  the session's lines could be split across `engine.log` and `engine-<n>.log`. This is unlikely,
  and the test would fail loudly rather than pass wrongly.
