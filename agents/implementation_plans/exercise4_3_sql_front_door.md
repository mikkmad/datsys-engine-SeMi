# Exercise 4, Part 3 — The SQL Front Door

## 1. Sub-Exercise Reference

[`exercise_descriptions/Exercise4.md`](file:///workspaces/datsys-engine-SeMi/exercise_descriptions/Exercise4.md) §3 *The SQL front door*, §4 *statementNumber and the MDC*, and §6 Test 6 *Front door end-to-end*.

## 2. Background & Objective

With the Volcano execution pipeline (`ScanOperator` → `FilterOperator`), `Planner`, and `Executor` in place from Parts 1 and 2, [`Engine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/Engine.java) must now be converted from a demo program into the primary command-line SQL front door.

The front door provides a clean CLI interface supporting:
1. **Interactive Inspection**: Running without arguments outputs the team name (`Team SeMi`) and usage information.
2. **Single-Statement Query Execution**: Running with one argument executes a single SQL statement.
3. **Script Execution**: Running with two arguments (`-f <script.sql>`) reads and executes an entire multi-statement SQL script.
4. **Stream Separation**: Query results from `SELECT` statements are written as headerless CSV to standard output (`stdout`). Operational logs, diagnostic information, and error messages are directed strictly to standard error (`stderr`), keeping `stdout` completely clean for downstream piping and differential testing (e.g. against DuckDB).
5. **Session & Statement MDC Tracking**: Each invocation establishes an execution session with a unique `sessionId` in SLF4J MDC, tracks statement execution counts via `statementNumber`, and resets the counter to `"0"` outside SQL statements.
6. **Data Directory Default & Git Ignore**: Storage files default to `data/` in the working directory, and `data/` is added to `.gitignore`.

## 3. Requirements & Invariants

1. **Argument Parsing & Modes in `Engine.main`**:
   - `args.length == 0`: Print team name (`Team SeMi`) and usage information to `stdout`.
   - `args.length == 1`: Argument is a single SQL statement string; execute it against the storage engine.
   - `args.length == 2 && "-f".equals(args[0])`: Argument is `-f` followed by a script file path; read the file and execute all statements.
   - Any invalid argument combination (e.g. unknown flags, wrong number of arguments): Log error, write error message and usage to `stderr`, and leave `stdout` empty.
2. **Stream Separation & Clean Output**:
   - `SELECT` output rows are formatted as headerless CSV and sent strictly to `stdout`.
   - All logging is directed to `stderr` (console appender in `log4j2.xml` is already targeted to `SYSTEM_ERR`) and `logs/engine.log`.
   - In error conditions (failing scripts, bad SQL syntax, missing files, invalid arguments), the error message is written to `stderr` and `stdout` remains clean (0 bytes).
3. **Session & Statement MDC Tracking**:
   - At startup, generate `sessionId` via `UUID.randomUUID().toString()` and set into MDC.
   - Set `statementNumber` to `"0"` in MDC initially.
   - Log `LOGGER.debug("engine started")` at startup.
   - Delegate SQL execution to `Executor`, which manages per-statement numbering (`1, 2, ...`) and resets `statementNumber` to `"0"`.
   - In `finally`, ensure `statementNumber` is `"0"`, log `LOGGER.debug("engine stopped")`, and clear MDC.
4. **Default Data Directory & `.gitignore`**:
   - Default storage engine directory to `Path.of("data")` (configurable via `semi.data.dir` system property for test isolation).
   - Add `data/` to `.gitignore`.
5. **Quality & Architecture Standards (AGENTS.md)**:
   - Methods limited to 10–20 lines with single responsibility and guard clauses.
   - Full Javadoc (`@param`, `@return`, `@throws`) on all classes, methods, and constructors.
   - Group class fields with section comments separated by blank lines.
   - Four-space indentation throughout.

## 4. Proposed Changes

### Configuration
#### [MODIFY] [.gitignore](file:///workspaces/datsys-engine-SeMi/.gitignore)
- Append `data/` to the ignore list.

---

### Core Front Door
#### [MODIFY] [Engine.java](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/Engine.java)
- Update `main(String[] args)` to delegate to `run(args, DEFAULT_DATA_DIRECTORY, System.out, System.err)`.
- Provide `public static void run(String[] args, Path dataDirectory, PrintStream out, PrintStream err)`:
  - Initialize session MDC (`sessionId`, `statementNumber="0"`).
  - Log `engine started`.
  - Check for zero arguments and invoke `printUsage(out)`.
  - Initialize `StorageEngine` on `dataDirectory` and `Executor` with `out`.
  - Dispatch 1-arg SQL or 2-arg `-f <file>` execution.
  - Catch exceptions, log with `LOGGER.error(...)`, and write error description to `err`.
  - In `finally`, log `engine stopped` with `statementNumber="0"` and clean up MDC.
- Private helper `dispatchArguments(String[] args, Executor executor, PrintStream err)`:
  - Guard clauses for 1-arg and 2-arg `-f`.
  - Handles reading SQL script from path via `Files.readString(Path.of(args[1]))`.
  - Rejects other argument combinations by throwing `IllegalArgumentException`.
- Private helper `printUsage(PrintStream out)`:
  - Prints `teamName()` and usage guide.
- Retain `public String teamName()` returning `"Team SeMi"`.

---

### Tests
#### [NEW] [EngineIT.java](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/EngineIT.java)
- Integration test suite satisfying §6 Test 6:
  - `executesScriptEndToEndAndMatchesCsvByteForByte(@TempDir Path tempDir)`:
    - Runs a multi-statement `.sql` script (`CREATE TABLE`, `COPY`, `SELECT`).
    - Captures `stdout` and compares byte-for-byte against expected CSV bytes.
  - `failingScriptWritesToStderrAndLeavesStdoutClean(@TempDir Path tempDir)`:
    - Runs a `.sql` script containing an invalid query (e.g. unknown table).
    - Asserts `stdout` contains 0 bytes and `stderr` contains the error message.
  - `noArgumentsPrintsTeamNameAndUsage()`:
    - Asserts `stdout` includes `"Team SeMi"` and usage information.
  - `singleStatementExecutionProducesCsv(@TempDir Path tempDir)`:
    - Executes a single `SELECT` statement via 1-argument mode and verifies CSV output.
  - `invalidArgumentsWritesToStderrAndLeavesStdoutClean()`:
    - Runs with unsupported flags and verifies error reporting on `stderr` with clean `stdout`.

#### [MODIFY] [EngineTest.java](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/EngineTest.java)
- Retain `teamName()` unit test.
- Add unit tests for `Engine.run(...)` using custom streams to verify stream routing and usage text.

## 5. Verification Plan

### Automated Tests
1. **Unit Tests**:
   ```bash
   mvn clean test
   ```
   - Verify `EngineTest`, `PlannerTest`, `ExecutorTest`, and all operator tests pass.
2. **Integration Tests & Full Verification**:
   ```bash
   mvn -B verify
   ```
   - Verify `EngineIT` (end-to-end script byte-for-byte CSV comparison, error on stderr, stdout clean), `StorageEngineIT` (Exercise 2 integration tests untouched and passing), and `BinderIT` all pass with 0 errors and 0 failures.

### Quality Invariants
- Verify that `data/` is present in `.gitignore`.
- Verify `git status` shows all files properly formatted and documented.

