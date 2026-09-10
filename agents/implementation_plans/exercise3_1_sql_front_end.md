# Implementation Plan - Exercise 3: Parsing SQL Text into a Bound AST

## 1. Sub-Exercise Reference
- Specification: [`exercise_descriptions/Exercise3.md`](file:///workspaces/SeMi/exercise_descriptions/Exercise3.md)

## 2. Background & Objective
Exercise 3 places a SQL front end in front of the storage engine implemented in Exercise 2. SQL text will be parsed into an abstract syntax tree (AST), validated, and bound against the storage engine's catalog schema. Execution of statements is out of scope for this exercise. ANTLR generates the lexer and parser from the grammar definition. We construct the AST data structures, the AST builder visitor, the parser facade, the schema catalog binder, and the pretty-printer.

To avoid a class name collision between ANTLR's generated `SqlParser` and our facade `datasys.semi.SqlParser` while keeping all code cleanly in the team's `datasys.semi` domain:
- The grammar lives at `src/main/antlr4/datasys/semi/sql/Sql.g4`.
- ANTLR generates into package `datasys.semi.sql` (`SqlLexer`, `SqlParser`, `SqlBaseVisitor`).
- Engine AST, Facade, Binder, and Printer live in `datasys.semi`.

## 3. Requirements & Invariants
1. **SQL Subset**:
   - `CREATE TABLE <name> (<col> <type>, ...);`
   - `COPY <name> FROM '<path>';`
   - `SELECT * FROM <name> [WHERE <col> <op> <const>];`
   - Keywords are case-insensitive; identifier casing is preserved.
   - Literals typed as `String`, `Long`, `Double` (including negative numbers).
   - Comparisons: `=`, `<`, `>`. Semicolon-terminated statements. `--` comments skipped.
2. **Grammar & ANTLR**:
   - Grammar location: `src/main/antlr4/datasys/semi/sql/Sql.g4`.
   - Plugin generates visitor and listener into `target/generated-sources/antlr4`.
3. **AST**:
   - `Statement` sealed interface permitting `CreateTableStatement`, `CopyStatement`, `SelectStatement`.
   - `Predicate` record for column filter.
   - Reuses `ColumnSpec`, `ColumnType`, `Comparison` from week 2.
4. **Parser Facade & Error Handling**:
   - `SqlParser.parse(String sqlText)` returning `List<Statement>`.
   - Removes default error listeners; registers fail-fast listener throwing `SqlParseException` with 1-based `line` and 0-based `column`.
5. **Logging**:
   - SLF4J MDC with `sessionId` and `statementNumber`.
   - Debug on success: `statements=<count> durationMs=<ms>`.
   - Error on failure: `failed line=<line> col=<col> durationMs=<ms>`.
6. **Binder**:
   - `StorageEngine.schema(String tableName)` returns `List<ColumnSpec>`.
   - Validates tables, columns, predicate constant types, and non-empty/duplicate columns in `CREATE TABLE`.
7. **Pretty-Printer & CLI Demo**:
   - `SqlPrinter.print(Statement s)` renders normalized SQL ending in `;`.
   - Round-trip invariant: `parse(print(s)).getFirst().equals(s)`.
   - `Engine.main` parses and prints the 4 Task 1 statements, one per line.
8. **Test Coverage**:
   - Unit tests covering shapes, literals, case-insensitivity, 5 malformed inputs, comments/whitespace, and round-trip printing.
   - Integration tests covering binder catalog checks on `@TempDir`.

## 4. Proposed Changes
- `src/main/antlr4/datasys/semi/sql/Sql.g4` [NEW]: SQL grammar.
- `src/main/java/datasys/semi/Statement.java` [NEW]: Sealed interface.
- `src/main/java/datasys/semi/CreateTableStatement.java` [NEW]: Create table AST record.
- `src/main/java/datasys/semi/CopyStatement.java` [NEW]: Copy AST record.
- `src/main/java/datasys/semi/SelectStatement.java` [NEW]: Select AST record.
- `src/main/java/datasys/semi/Predicate.java` [NEW]: Predicate record.
- `src/main/java/datasys/semi/SqlParseException.java` [NEW]: Parser syntax exception.
- `src/main/java/datasys/semi/SqlAstBuilder.java` [NEW]: ANTLR visitor building AST.
- `src/main/java/datasys/semi/SqlParser.java` [NEW]: Facade parser.
- `src/main/java/datasys/semi/StorageEngine.java` [MODIFY]: Add `schema(String tableName)`.
- `src/main/java/datasys/semi/Binder.java` [NEW]: Catalog statement binder.
- `src/main/java/datasys/semi/SqlPrinter.java` [NEW]: AST SQL pretty-printer.
- `src/main/java/datasys/semi/Engine.java` [MODIFY]: Demo parsing and pretty-printing 4 statements.
- `src/test/java/datasys/semi/SqlParserTest.java` [NEW]: Parser unit tests.
- `src/test/java/datasys/semi/SqlPrinterTest.java` [NEW]: Printer round-trip unit tests.
- `src/test/java/datasys/semi/BinderIT.java` [NEW]: Binder integration tests.

## 5. Verification Plan
- `mvn clean compile`: ANTLR generation and compile validation.
- `mvn test`: Surefire unit tests (including 5 malformed inputs, literal typing, round-trip).
- `mvn -B verify`: Full build including failsafe integration tests (`BinderIT`, `StorageEngineIT`).
- `mvn compile exec:java`: Verify 4 golden statements printed accurately.
