# Exercise 4, Part 3 — The SQL Front Door Walkthrough

## 1. Summary of Work Accomplished

Implemented **Exercise 4 §3 (The SQL Front Door)** and **§6 Test 6 (Front door end-to-end)**:
- Converted [`Engine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/Engine.java) from a static demo into the analytical engine's command-line front door.
- Supported 3 CLI operational modes:
  1. `0` arguments: prints the team name (`Team SeMi`) and CLI usage guide to standard output.
  2. `1` argument: executes a single SQL statement; emitted `SELECT` rows are formatted as headerless CSV to standard output.
  3. `2` arguments (`-f <script.sql>`): executes an entire multi-statement SQL script file, emitting query results to standard output.
- Separated stdout and stderr streams: query result CSV data is strictly routed to `stdout`, while engine operational logs and diagnostic errors land exclusively on `stderr`.
- Maintained per-session SLF4J MDC tracking (`sessionId` UUID, `statementNumber` sequencing `1, 2, ...` during statements and `"0"` outside statements).
- Configured storage data directory to default to `data/` and added `data/` to `.gitignore`.
- Created comprehensive integration test suite [`EngineIT.java`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/EngineIT.java) verifying byte-for-byte CSV output comparisons and stderr error routing.

## 2. Key Changes & Architecture

### Front Door CLI: [`Engine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/Engine.java)
- `main(String[] args)`: entry point delegating to `run(...)`.
- `run(String[] args, Path dataDirectory, PrintStream out, PrintStream err)`:
  - Generates session UUID and puts `sessionId` and `statementNumber = "0"` in MDC.
  - Logs `LOGGER.debug("engine started")`.
  - Dispatches CLI arguments:
    - 0 args: calls `printUsage(out)`.
    - 1 arg: executes single SQL statement via `Executor.execute(sql)`.
    - 2 args (`-f`): reads SQL script file via `Files.readString(Path.of(args[1]))` and executes via `Executor.execute(script)`.
    - Invalid args: logs error and emits user-facing message to `err`.
  - Catches execution errors, logs with `LOGGER.error(...)`, and outputs error description to `err` while leaving `out` clean.
  - Ensures `statementNumber = "0"` and logs `LOGGER.debug("engine stopped")` in `finally`.
- `defaultDataDirectory()`: resolves `Path.of(System.getProperty("semi.data.dir", "data"))`.
- `teamName()`: preserves `"Team SeMi"`.

### Configuration: [`.gitignore`](file:///workspaces/datsys-engine-SeMi/.gitignore)
- Added `data/` directory to prevent local database tables and partition data from entering source control.

### Integration Testing: [`EngineIT.java`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/EngineIT.java)
- Tests §6 Test 6:
  - `executesScriptEndToEndAndMatchesCsvByteForByte`: runs full `.sql` script and verifies byte-for-byte equality against expected CSV.
  - `failingScriptWritesToStderrAndLeavesStdoutClean`: verifies that a failing query emits diagnostic text to `stderr` and leaves `stdout` with 0 bytes.
  - `singleStatementExecutionProducesCsv`: executes single-statement query via CLI and asserts CSV row outputs.
  - `noArgumentsPrintsTeamNameAndUsage`: checks team name and CLI help message.
  - `invalidArgumentsWritesToStderrAndLeavesStdoutClean`: asserts error handling for unknown CLI arguments.

### Unit Testing: [`EngineTest.java`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/EngineTest.java)
- Extended unit tests verifying stream handling, null argument robustness, and CLI error behavior with custom in-memory streams.

## 3. Verification Results

### 3.1 Unit Tests (`mvn clean test`)
```text
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.103 s -- in datasys.semi.EngineTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.081 s -- in datasys.semi.ExecutorTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.021 s -- in datasys.semi.FilterOperatorTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.111 s -- in datasys.semi.PlannerTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.068 s -- in datasys.semi.ScanOperatorTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.054 s -- in datasys.semi.SqlParserTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.034 s -- in datasys.semi.SqlPrinterTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.051 s -- in datasys.semi.StorageEngineUnitTest
[INFO] 
[INFO] Results:
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 3.2 Full Verification Pipeline (`mvn -B verify`)
```text
[INFO] --- surefire:3.5.6:test (default-test) @ engine ---
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] --- failsafe:3.5.6:integration-test (default) @ engine ---
[INFO] Running datasys.semi.BinderIT
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.407 s -- in datasys.semi.BinderIT
[INFO] Running datasys.semi.EngineIT
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.439 s -- in datasys.semi.EngineIT
[INFO] Running datasys.semi.StorageEngineIT
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.233 s -- in datasys.semi.StorageEngineIT
[INFO] 
[INFO] Results:
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] --- failsafe:3.5.6:verify (default) @ engine ---
[INFO] BUILD SUCCESS
```

## 4. Edge Cases & Invariants Tested

| Scenario | Input | Expected Output / Invariant | Verified By |
|---|---|---|---|
| End-to-end `.sql` script | `-f query.sql` | `stdout` matches expected CSV byte-for-byte | `EngineIT.executesScriptEndToEndAndMatchesCsvByteForByte` |
| Failing query script | `-f failing.sql` | `stdout` contains 0 bytes; error message printed to `stderr` | `EngineIT.failingScriptWritesToStderrAndLeavesStdoutClean` |
| Single interactive query | `"SELECT * FROM trips WHERE ..."` | Only matching rows emitted to `stdout` as CSV | `EngineIT.singleStatementExecutionProducesCsv` |
| No arguments | Empty `String[]` | Prints `Team SeMi` and CLI usage guide to `stdout` | `EngineIT.noArgumentsPrintsTeamNameAndUsage` |
| Invalid flags | `-x foo.sql` | `stdout` contains 0 bytes; error printed to `stderr` | `EngineIT.invalidArgumentsWritesToStderrAndLeavesStdoutClean` |
| Missing script file | `-f missing.sql` | `stdout` clean; error logged and reported on `stderr` | `EngineTest.runWithNonExistentScriptWritesToErrAndLeavesOutEmpty` |
| Exercise 2 regression | DDL, COPY, SELECT | All 9 original integration tests pass untouched | `StorageEngineIT` |

