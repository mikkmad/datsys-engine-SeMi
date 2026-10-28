# Storage Engine Engineering Guidelines (`AGENTS.md`)

This document defines the strict engineering standards, architectural patterns, artifact workflows, and coding conventions for all AI agents working on the `datasys-engine-SeMi` codebase. Every rule in this document is mandatory.

---

## 1. Project Context & Technology Stack

- **Domain**: High-performance analytical storage engine for the ITU course *"How to Build Data Systems"*.
- **Language**: Java 25 (`maven.compiler.release = 25`).
- **Build System**: Apache Maven 3.9+.
- **Testing**: JUnit Jupiter 6.1.2 with Surefire (unit tests: `*Test.java`) and Failsafe (integration tests: `*IT.java`).
- **Serialization & JSON**: Jackson Databind.
- **Logging API**: SLF4J 2.0.18.
- **Logging Backend**: Log4j2 2.26.1.

### Key Project Directories
- Production Code: [`src/main/java/datasys/semi/`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi)
- Test Code: [`src/test/java/datasys/semi/`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi)
- Exercise Specifications: [`exercise_descriptions/`](file:///workspaces/datsys-engine-SeMi/exercise_descriptions)
- Design Documentations: [`docs/`](file:///workspaces/datsys-engine-SeMi/docs)
- Implementation Plans: [`agents/implementation_plans/`](file:///workspaces/datsys-engine-SeMi/agents/implementation_plans)
- Walkthroughs: [`agents/walkthroughs/`](file:///workspaces/datsys-engine-SeMi/agents/walkthroughs)
- Engine Logs: [`logs/`](file:///workspaces/datsys-engine-SeMi/logs) (contains one dedicated log file per session)

---

## 2. Mandatory Artifact Workflow (Sub-Exercises)

Every assignment in this project is organized into exercises and sub-exercises (e.g., Exercise 2, Task 1). To maintain a clear audit trail and development history, agents must adhere to the two-phase artifact workflow.

```
Sub-Exercise Assigned
         │
         ▼
[1] Draft & Save Implementation Plan (agents/implementation_plans/exerciseX_Y_<summary>.md)
         │
         ▼
[2] Implement Code & Pass Tests (mvn test, mvn verify)
         │
         ▼
[3] Document in Walkthrough (agents/walkthroughs/exerciseX_Y_<summary>.md)
```

### Rule 2.1: Implementation Plan for Every Sub-Exercise
- **Location**: `agents/implementation_plans/`
- **Naming Convention**: `exercise<ExerciseNumber>_<SubExerciseNumber>_<summary_in_snake_case>.md`
  - *Example for Exercise 2, Part 1*: `agents/implementation_plans/exercise2_1_schema_persistence.md`
  - *Example for Exercise 3, Part 2*: `agents/implementation_plans/exercise3_2_hash_index_lookup.md`
- **When**: Create this document **before** writing or modifying production code.
- **Required Sections**:
  1. **Sub-Exercise Reference**: Link to the relevant file in [`exercise_descriptions/`](file:///workspaces/datsys-engine-SeMi/exercise_descriptions).
  2. **Background & Objective**: What problem is being solved and why.
  3. **Requirements & Invariants**: Functional and non-functional requirements.
  4. **Proposed Changes**: Affected classes, methods, records, or file structures.
  5. **Verification Plan**: Exact commands (`mvn test`, `mvn verify`) and expected test outcomes.

### Rule 2.2: Walkthrough for Every Implementation Plan
- **Location**: `agents/walkthroughs/`
- **Naming Convention**: Matches the implementation plan's filename exactly:
  - `agents/walkthroughs/exercise<ExerciseNumber>_<SubExerciseNumber>_<summary_in_snake_case>.md`
- **When**: Create this document **immediately after** code implementation and test verification succeed.
- **Required Sections**:
  1. **Summary of Work Accomplished**: Concise overview of what was implemented.
  2. **Key Changes & Architecture**: Highlights of classes, algorithms, or schemas modified.
  3. **Verification Results**: Raw output or verification table from running `mvn test` and `mvn verify`.
  4. **Edge Cases & Invariants Tested**: Specific boundary scenarios validated.

---

## 3. Java Coding Best Practices (JetBrains + Google Synthesis)

All Java code must follow **JetBrains' Java Best Practices** for clean design, architecture, and modern idioms, combined with standard Java/Google naming conventions and **standard 4-space indentation**.

### 3.1 General Design Principles
1. **Be Clear, Not Clever**: Write simple, readable, and intention-revealing code. Avoid convoluted one-liners, bitwise tricks (e.g., XOR variable swap), or obscure ternary nesting.
2. **Keep Methods Short and Focused**:
   - Aim for **10–20 lines of code** per method.
   - Each method must adhere to the Single Responsibility Principle (SRP).
   - Decompose long methods into well-named private helper methods.
3. **Careful & Intentional Naming**:
   - **No single-letter variables** (except standard loop indices `i`, `j` or single-parameter lambdas where types are obvious).
   - **Action-oriented method names**: Use verbs expressing intent (e.g., `calculatePartitionBounds()`, `deserializeCatalogRecord()`, never vague names like `calc()` or `process()`).
   - **Explicit units in variable names**: Always append time or size units (e.g., `timeoutInMs`, `bufferSizeInBytes`, `maxRowsPerPartition`).
   - **Domain alignment**: Use data systems terminology consistently (`partitionId`, `rowOffset`, `predicate`, `scanStats`).
4. **Test, Test, Test**:
   - Write comprehensive unit tests for business logic and integration tests for end-to-end flows.
   - Use descriptive, sentence-like test method names that declare the condition and expected outcome (e.g., `duplicateTableThrowsIllegalArgumentException()`, `partitionPruningSkipsIrrelevantBlocks()`).

### 3.2 Language-Specific Idioms (Modern Java 25)
1. **Switch Expressions**: Prefer modern `switch` expressions and pattern matching over cascading `else-if` blocks for multi-condition branching.
2. **No Empty Catch Blocks**:
   - Never swallow exceptions silently.
   - Catch blocks must do one of the following:
     - Rethrow, or wrap in an unchecked exception that keeps the original as its cause (`new IllegalStateException("...", e)`). Log here only if the catch block is a logging boundary or adds context the exception does not carry (see §5.4).
     - Handle the failure and continue (e.g. best-effort cleanup). The catch block is then the only record of the failure, so it **must** log it with `LOGGER.error(...)`.
     - Return an explicit, documented fallback value if the failure is gracefully recoverable.
3. **Collections Over Raw Arrays**:
   - Prefer `List<T>`, `Set<T>`, and `Map<K, V>` (`ArrayList`, `HashMap`) over fixed-size raw arrays for general data manipulation.
   - Reserve primitive arrays (`byte[]`) strictly for raw I/O, binary page serialization, and fixed-size magic byte headers (e.g., `MAGIC = { 'S', 'E', 'M', 'I' }`).
4. **Embrace Immutability**:
   - Mark classes, fields, and parameters `final` wherever possible.
   - Avoid mutable state and unnecessary setters. Prefer immutable constructor initialization or builders.
   - Use Java 25 `record`s for immutable data carriers (e.g., `ColumnSpec`, `ScanStats`, metadata headers).
5. **Favor Composition Over Inheritance**:
   - Use "has-a" composition with decoupled collaborator classes instead of fragile, deep "is-a" inheritance hierarchies.
6. **Streamline with Lambdas & Method References**:
   - Use lambdas (`row -> row.getValue(col)`) and method references (`this::evaluatePredicate`) instead of anonymous inner classes.
7. **Enhanced Loops & Streams**:
   - Use enhanced `for` loops (`for (var item : list)`) for sequential iteration without index overhead.
   - Use the Stream API (`.filter()`, `.map()`, `.toList()`) for declarative data transformations.
8. **Safeguard Resources with Try-with-Resources**:
   - Always open `AutoCloseable` resources (streams, readers, channels, file handles) inside `try (...)` blocks to prevent resource and memory leaks.
9. **Untangle Nested Logic with Guard Clauses**:
   - Avoid deep indentation ("arrow anti-pattern").
   - Validate preconditions early and exit or throw immediately, keeping the primary execution path at the lowest indentation level.

### 3.3 Project-Specific Architecture
1. **Circular Dependencies**: Forbid cyclic package or class dependencies.
2. **Crash-Safe File Writes**: When persisting state (catalogs, partition indexes, data files), always write to a `.tmp` file first and commit via atomic file move:
   ```java
   Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
   ```

---

## 4. Code Readability, Documentation & Formatting Rules

### 4.1 Documentation Comments (Javadoc)
- **Mandatory Javadoc (`/** ... */`) for Every Class, Record, Interface, and Enum**:
  Must explain the role, threading assumptions, and responsibilities within the storage engine.
- **Mandatory Javadoc for Every Method and Constructor**:
  - Applies to **public, protected, package-private, AND private** methods.
  - Must include:
    - Clear description of purpose.
    - `@param` tags for all parameters explaining valid domains/bounds.
    - `@return` tag for non-void returns explaining output semantics.
    - `@throws` tags for all checked and unchecked exceptions thrown intentionally.

### 4.2 Spacing & Visual Grouping
- **Variable Grouping**:
  - Class member variables must be grouped by domain relevance.
  - Each group must be preceded by a distinct section comment (e.g., `// --- Constants ---`, `// --- Directory Paths ---`, `// --- Partition State ---`).
  - Exactly **one blank line** must separate each variable group.
- **Method & Class Spacing**:
  - Exactly **one blank line** must separate method definitions.
  - Exactly **one blank line** must precede and follow nested inner classes.
- **Indentation**:
  - Use **4 spaces** per indentation level (never tabs, never 2-space Google indent).

*Example Layout:*
```java
public final class PartitionManager {

    // --- Constants ---
    private static final Logger LOGGER = LoggerFactory.getLogger(PartitionManager.class);
    private static final int DEFAULT_BUFFER_SIZE_IN_BYTES = 8192;

    // --- Directory Paths ---
    private final Path rootDirectory;
    private final Path partitionsDirectory;

    // --- Partition Sizing & State ---
    private final int maxRowsPerPartition;
    private final Map<Integer, PartitionMetadata> partitionIndex = new HashMap<>();

    /**
     * Constructs a new PartitionManager.
     *
     * @param rootDirectory the base directory for partition storage
     * @param maxRowsPerPartition maximum row capacity per partition file
     * @throws IllegalArgumentException if directory is null or maxRows is non-positive
     */
    public PartitionManager(Path rootDirectory, int maxRowsPerPartition) {
        if (rootDirectory == null || maxRowsPerPartition <= 0) {
            throw new IllegalArgumentException("invalid directory or partition size");
        }
        this.rootDirectory = rootDirectory;
        this.partitionsDirectory = rootDirectory.resolve("data");
        this.maxRowsPerPartition = maxRowsPerPartition;
    }

    /**
     * Scans partitions matching the provided predicate.
     *
     * @param predicate filter criteria
     * @return list of matching rows
     */
    public List<Row> scan(Predicate<Row> predicate) {
        // Implementation with guard clauses and try-with-resources...
    }
}
```

---

## 5. Logging Standards (Exercise 1 §3 Specification)

All logging in the engine must adhere strictly to the format defined in [`exercise_descriptions/Exercise1.md`](file:///workspaces/datsys-engine-SeMi/exercise_descriptions/Exercise1.md). The engine will subsequently analyze its own log files using `COPY` and `SELECT` commands; the log file is a machine-readable CSV.

### 5.1 Per-Session Log Files & CSV Schema
- **Dedicated Session Logs**: The engine must **not** log to a single monolithic log file. Instead, each engine execution session writes to its own dedicated log file (e.g., `logs/engine-<sessionId>.log` or configured dynamically per session).
- **Mandatory CSV Schema**:
  Every log entry written to a session log file conforms to:
  ```
  timestamp, sessionId, statementNumber, threadId, logLevel, className, logMessage
  ```

- **Log4j2 Pattern Configuration**:
  ```xml
  <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSS},%X{sessionId},%X{statementNumber},%tid,%level,%logger{1},%m%n"/>
  ```

### 5.2 Contextual MDC Requirements
- **`sessionId`** (`STRING`): Identifies an engine execution run. Generated once at startup using `UUID.randomUUID().toString()` and set into `MDC.put("sessionId", id)`.
- **`statementNumber`** (`LONG`): Sequence counter for SQL statements within a session.
  - Must be initialized to `"0"` at startup.
  - Zero indicates the log line does not belong to a specific SQL statement.
  - **Invariant**: Must never be null or empty, ensuring numeric parsing during subsequent log analysis.
- **`className`** (`STRING`): Extracted automatically via `%logger{1}` from the SLF4J logger instance.

### 5.3 Engine Log Levels (Strictly Two Levels)
1. **`LOGGER.debug(...)`**: Used for normal operational flow (engine startup/shutdown, partition scans, cache lookups, statistics recording).
2. **`LOGGER.error(...)`**: Used for failures.
   - **Invariant** (Exercise 1 §3, Exercise 5 §6 test 1): every exception the engine throws must leave at least one `ERROR` line in the active session's log file. A failing statement must leave an `ERROR` line carrying that statement's `statementNumber`. The invariant counts *failures*, not `throw` statements: it is met by logging at the boundary the failure passes through (§5.4), not by logging next to every `throw`.
   - The course uses *only* `debug` and `error` in engine code. Avoid `info`, `warn`, or `trace`.

### 5.4 Log Failures Once, at the Boundary (No Log-and-Throw)
Logging an error and then throwing an exception with the same message is an anti-pattern: every frame the exception passes through adds another copy of the same line, and the log no longer tells you how many failures happened. A failure is logged **once**, by the code that handles it or by the boundary it crosses.

1. **Throw sites throw plainly.** Guard clauses and validation throw an exception with a descriptive message and do **not** log. Put the identifying values (table name, column, version) into the exception message so the boundary line is self-contained.
   ```java
   // Wrong: log-and-throw duplicates the line
   LOGGER.error("unknown table: {}", tableName);
   throw new IllegalArgumentException("unknown table: " + tableName);

   // Right
   throw new IllegalArgumentException("unknown table: " + tableName);
   ```
   Never add helpers that log and return an exception (e.g. `throw fail(message)`); they are the same anti-pattern behind a method name.
2. **Log at the source only to add context that would otherwise be lost.** This is allowed when wrapping a checked exception whose cause message the boundary will not see (for example an `IOException` with the file path), or when the catch block swallows the failure (for example best-effort cleanup):
   ```java
   } catch (IOException exception) {
       LOGGER.error("api=writePartitionFile table={} partition={} error={}", tableName, partitionNumber,
               LogSanitizer.sanitize(exception.getMessage()));
       throw new IllegalStateException("Could not write partition " + partitionNumber, exception);
   }
   ```
   If the log line would only repeat the exception message, do not log.

   Exception: `SqlParser` logs `failed line=… col=… durationMs=…` before rethrowing, as required by Exercise 3 §6. Keep it.
3. **Argument-contract violations by Java callers** (e.g. `null` passed to a constructor or public method) throw `IllegalArgumentException` without logging. The caller owns that failure; through the CLI it is still logged where the failure is handled.
4. **`statementNumber` stays numeric at boundaries.** Public entry points that can log before a statement starts (e.g. `Executor.execute(String)`) default a missing MDC `statementNumber` to `"0"` (§5.2).
5. **Tests.** Unit tests assert on the exception type and message. Tests that check `ERROR` lines assert on the line written where the failure is handled, not on throw sites; a test may assert that a component does *not* log at its throw site.

### 5.5 CSV Message Hygiene
- **Never include unescaped commas or line breaks** in `logMessage`.
- Always use SLF4J parameterized logging:
  ```java
  LOGGER.debug("Scanned partition {} with {} matching rows", partitionId, matchCount);
  ```
- **Standard Output Restrictions**:
  - **Prohibited in Core Engine**: Never use `System.out.println` or `System.err.println` inside internal storage engine components (`StorageEngine.java`, partition managers, codecs, catalogs, etc.). All operational diagnostics, status, and errors must be recorded through SLF4J loggers.
  - **Permitted in `Engine.java`**: `System.out.println` is explicitly permitted within the CLI entry point (`Engine.java`) to print query results and user-facing terminal output for future exercises.

---

## 6. Verification & Build Protocol

Before completing any task or sub-exercise, the agent must execute and verify the Maven build and test pipeline:

```bash
# 1. Clean and compile the codebase
mvn clean compile

# 2. Run both unit tests (Surefire) and integration tests (Failsafe) in batch mode
mvn -B verify
```

### Pre-Completion Checklist
Before reporting a sub-exercise as complete:
- [ ] Implementation plan created in `agents/implementation_plans/exercise<X>_<Y>_<summary>.md`.
- [ ] Walkthrough created in `agents/walkthroughs/exercise<X>_<Y>_<summary>.md`.
- [ ] All methods and classes have complete Javadoc comments (`@param`, `@return`, `@throws`).
- [ ] Variables grouped logically with section headers and separated by blank lines.
- [ ] Indentation is 4 spaces; methods are 10–20 lines with guard clauses.
- [ ] Try-with-resources used for all file and stream operations.
- [ ] Logging uses SLF4J with MDC context (`sessionId`, `statementNumber`) and only `debug`/`error` levels.
- [ ] No log-and-throw: failures are logged once at a boundary (§5.4); throw sites only throw.
- [ ] `mvn -B verify` passes with 0 failures and 0 errors.