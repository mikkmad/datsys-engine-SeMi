# Storage Engine Engineering Guidelines (`AGENTS.md`)

Mandatory rules for AI agents working on the SeMi analytical storage engine (ITU course *"How to Build Data Systems"*).

## 1. Stack & Layout

- Java 25, Maven 3.9+, JUnit Jupiter 6.1.2 (Surefire: `*Test.java`, Failsafe: `*IT.java`), Jackson Databind, SLF4J 2.0.18 + Log4j2 2.26.1.
- Production code: `src/main/java/datasys/semi/` | Tests: `src/test/java/datasys/semi/`
- Exercise specs: `exercise_descriptions/` | Design docs: `docs/`
- Plans: `agents/implementation_plans/` | Walkthroughs: `agents/walkthroughs/`
- Logs: `logs/` (one monolithic CSV log file, split automatically by the Log4j2 configuration)

## 2. Artifact Workflow (per sub-exercise)

1. **Plan** → 2. **Implement + verify** → 3. **Walkthrough**.

Both documents are named `exercise<X>_<Y>_<summary_in_snake_case>.md` (e.g. `exercise2_1_schema_persistence.md`), and the walkthrough name must match its plan.

| Artifact | When | Required sections |
|---|---|---|
| Plan | Before touching production code | Sub-exercise reference (link to `exercise_descriptions/`), background & objective, requirements & invariants, proposed changes, verification plan (exact commands, expected outcomes) |
| Walkthrough | Right after the build passes | Summary of work, key changes & architecture, verification results (raw `mvn` output), edge cases & invariants tested |

## 3. Code Rules

- **Design**: clear over clever; methods of about 10–20 lines with a single responsibility; guard clauses instead of deep nesting; composition over inheritance.
- **Naming**: no single-letter variables (except `i`/`j` and obvious lambda parameters); verb-based method names; units in names (`timeoutInMs`, `bufferSizeInBytes`); data-systems terms (`partitionId`, `rowOffset`, `predicate`).
- **Modern Java**: `switch` expressions and pattern matching; `record`s for immutable data; `final` wherever possible; collections over arrays (`byte[]` only for binary I/O and magic headers); streams and lambdas for transformations.
- **Resources**: open every `AutoCloseable` in try-with-resources.
- **Exceptions**: never swallow them. Log a single-line `LOGGER.error` (see §5) and rethrow or wrap, or return a documented fallback.
- **Architecture**: no cyclic package or class dependencies. Persist state crash-safely: write `<file>.tmp`, then `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`.
- **Javadoc**: required on every class, record, interface, enum, method, and constructor, including private ones. Class docs state role and threading assumptions. Method docs include `@param` (valid domain), `@return`, and `@throws` for intentionally thrown exceptions.
- **Formatting**: 4-space indentation, no tabs. Group fields under `// --- Section ---` comments, one blank line between groups and between methods.
- **Tests**: unit tests for logic, integration tests for end-to-end flows. Test names are sentences describing condition and outcome (e.g. `duplicateTableThrowsIllegalArgumentException()`).

## 4. Front Door I/O

- `stdout`: only headerless CSV rows from `SELECT`. The one exception is the team name and usage text printed when the engine runs with no arguments (Exercises 1 and 4). Nothing else.
- `stderr`: all human-readable errors and diagnostics.
- `System.out` / `System.err` are used only in `Engine.java`, never in the storage engine, planner, operators, or catalogs.
- On failure, `Engine.main` prints `e.getMessage()` to `stderr`.
- Error messages contain no commas or newlines. Example: `constant type mismatch for column <column>: expected <TYPE> got <TYPE>`.

## 5. Logging

Specs: `exercise_descriptions/Exercise1.md`, `Exercise3.md`, `Exercise4.md`, and `Exercise5.md`. The engine later queries its own log via `COPY` / `SELECT`, so every log line must be a valid CSV row.

**Format** (Log4j2 pattern):
```
%d{yyyy-MM-dd HH:mm:ss.SSS},%X{sessionId},%X{statementNumber},%tid,%level,%logger{1},%m%n
```
Columns: `timestamp, sessionId, statementNumber, threadId, logLevel, className, logMessage`.

**Rules**
- Only `LOGGER.debug` (normal flow) and `LOGGER.error` (failures). No `info`, `warn`, or `trace`.
- Use parameterized messages in `key=value` form, with no commas, double quotes, or line breaks (Exercise 5).
- Never pass a `Throwable` to SLF4J, because the stack trace breaks the CSV layout.
- Every thrown exception leaves at least one error line, in the form `LOGGER.error("statement={} error={}", statementType, LogSanitizer.sanitize(e.getMessage()))`. Exception: parse failures log `failed line={} col={} durationMs={}` (Exercise 3) and need no message text.
- Exception messages are not guaranteed to be single-line or comma-free (they can be `null` or come from the JDK or libraries). Always pass them and any other free text, such as file paths or SQL fragments, through `LogSanitizer.sanitize(...)` (`datasys.semi.util`; do not write your own) before logging.

**MDC ownership**

| Key | Meaning | Owner |
|---|---|---|
| `sessionId` | One engine process run. Set once at startup (`UUID.randomUUID().toString()`) and removed at shutdown. | `Engine.main` |
| `statementNumber` | Number of the SQL statement being executed. Starts at `"0"`. | `Engine.main` initializes it to `"0"`. The SQL executor sets it to `n` (first statement is `"1"`) before each statement and resets it to `"0"` in a `finally` block. |

- `statementNumber` is never null or empty, and `0` means "not inside a statement".
- Parsing happens before statements are numbered, so parse errors are logged with `statementNumber=0`. Binding, planning, and execution carry the statement's number.
- `statementNumber` restarts for every session. To get the bind, plan, and execute lines of one statement, filter on both columns: `WHERE sessionId = '<id>' AND statementNumber = <n>`.
- No other component may modify or clear MDC. This covers the storage engine, planner, operators, catalogs, and codecs, and it includes `MDC.clear()`.

**Standard messages**
```java
LOGGER.debug("statement={} table={} rowsOut={} durationMs={}", statementType, tableName, rowsOut, durationInMs); // statement summary
LOGGER.debug("rowsIn={} rowsOut={}", rowsIn, rowsOut);               // FilterOperator.close()
LOGGER.debug("decision={} partitionId={}", decision, partitionId);   // Planner, before opening data files (READ|PRUNED)
```

## 6. Verification & Done Checklist

```bash
mvn clean compile
mvn -B verify      # Surefire + Failsafe
```

Before reporting a sub-exercise complete:
- [ ] Plan and walkthrough exist with matching names.
- [ ] Javadoc, section comments, and 4-space formatting are in place.
- [ ] Try-with-resources, guard clauses, and short methods are used.
- [ ] Logging follows §5 (MDC, only `debug`/`error`, single-line CSV-safe).
- [ ] `mvn -B verify` passes with 0 failures and 0 errors.