# Walkthrough - Exercise 3: Parsing SQL Text into a Bound AST

## 1. Summary of Work Accomplished
Implemented Exercise 3 for `datasys-engine-SeMi`, establishing a SQL front-end that translates SQL text into a typed, validated, and bound AST:
- Authored ANTLR 4 grammar `Sql.g4` under `src/main/antlr4/datasys/semi/sql/Sql.g4`.
- Created typed AST record hierarchy (`Statement`, `CreateTableStatement`, `CopyStatement`, `SelectStatement`, `Predicate`) reusing Exercise 2 types (`ColumnSpec`, `ColumnType`, `Comparison`).
- Built AST visitor `SqlAstBuilder` and facade `SqlParser` with fail-fast error listener `SqlErrorListener` and SLF4J MDC logging.
- Extended `StorageEngine` with read-only schema accessor `schema(String tableName)`.
- Implemented `Binder` to validate AST statements against table catalogs and types.
- Implemented `SqlPrinter` pretty-printer satisfying the `parse(print(s))` round-trip property.
- Updated `Engine.main` to parse and pretty-print the 4 demonstration statements.
- Developed comprehensive JUnit 6 unit and integration test suites.

## 2. Key Changes & Architecture

### ANTLR Grammar & Code Generation
- File: [`src/main/antlr4/datasys/semi/sql/Sql.g4`](file:///workspaces/SeMi/src/main/antlr4/datasys/semi/sql/Sql.g4)
- Uses `caseInsensitive = true`, supporting case-insensitive keywords and case-preserved identifiers.
- Numbers and strings properly typed with `LONG_LITERAL`, `DOUBLE_LITERAL`, and `STRING_LITERAL`.
- ANTLR generated classes reside in package `datasys.semi.sql` to avoid name collisions with the facade.

### AST Data Models
- Sealed interface: [`datasys.semi.Statement`](file:///workspaces/SeMi/src/main/java/datasys/semi/Statement.java)
- Records:
  - [`CreateTableStatement`](file:///workspaces/SeMi/src/main/java/datasys/semi/CreateTableStatement.java) (defensively copies columns for immutability)
  - [`CopyStatement`](file:///workspaces/SeMi/src/main/java/datasys/semi/CopyStatement.java)
  - [`SelectStatement`](file:///workspaces/SeMi/src/main/java/datasys/semi/SelectStatement.java) (with `Optional<Predicate>`)
  - [`Predicate`](file:///workspaces/SeMi/src/main/java/datasys/semi/Predicate.java)

### Parser Facade & Error Handling
- Exception: [`SqlParseException`](file:///workspaces/SeMi/src/main/java/datasys/semi/SqlParseException.java) capturing 1-based `line` and 0-based `column`.
- Error listener: [`SqlErrorListener`](file:///workspaces/SeMi/src/main/java/datasys/semi/SqlErrorListener.java) intercepting all lexer and parser errors.
- Facade: [`SqlParser`](file:///workspaces/SeMi/src/main/java/datasys/semi/SqlParser.java) emitting SLF4J logs at DEBUG (success) and ERROR (failure) with MDC context.
- Visitor: [`SqlAstBuilder`](file:///workspaces/SeMi/src/main/java/datasys/semi/SqlAstBuilder.java).

### Catalog Binder & Schema Accessor
- Accessor: `StorageEngine.schema(String tableName)` returns ordered `List<ColumnSpec>`.
- Validator: [`Binder`](file:///workspaces/SeMi/src/main/java/datasys/semi/Binder.java) validating table existence, column existence, predicate types, and duplicate/empty column definitions.

### Pretty-Printer & CLI Demo
- Printer: [`SqlPrinter`](file:///workspaces/SeMi/src/main/java/datasys/semi/SqlPrinter.java) converting AST nodes to canonical semicolon-terminated SQL strings.
- CLI: [`Engine.java`](file:///workspaces/SeMi/src/main/java/datasys/semi/Engine.java) executes `DEMO_SQL` and prints 4 statements.

## 3. Verification Results

### Build & Verification Commands
- `mvn clean compile`: Succeeded with ANTLR 4 grammar processing.
- `mvn test`: 14 unit tests passed (0 failures, 0 errors).
- `mvn -B verify`: 15 integration tests passed (0 failures, 0 errors), 29 total tests passed.
- `mvn exec:java`: Successfully parsed and pretty-printed:
```
08:48:40.852 DEBUG Engine - engine started
08:48:40.915 DEBUG SqlParser - statements=4 durationMs=52
CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'trips.csv';
SELECT * FROM trips WHERE distance > 100;
SELECT * FROM trips;
08:48:40.923 DEBUG Engine - engine stopped
```

### Test Suite Breakdown
| Test Class | Category | Scenarios Covered | Status |
|---|---|---|---|
| [`SqlParserTest`](file:///workspaces/SeMi/src/test/java/datasys/semi/SqlParserTest.java) | Unit | Statement shapes, literal typing (`12`, `12.0`, `'12'`, `-1`, `-1.5`), case insensitivity, 5 malformed inputs with exact line & column, comments & whitespace skipping | Passed (5/5) |
| [`SqlPrinterTest`](file:///workspaces/SeMi/src/test/java/datasys/semi/SqlPrinterTest.java) | Unit | Round-trip `parse(print(s))` property across all shapes, operators, and types | Passed (4/4) |
| [`BinderIT`](file:///workspaces/SeMi/src/test/java/datasys/semi/BinderIT.java) | Integration | Valid statements bind; unknown table/column, type mismatches, duplicate columns throw `IllegalArgumentException` | Passed (6/6) |
| [`StorageEngineUnitTest`](file:///workspaces/SeMi/src/test/java/datasys/semi/StorageEngineUnitTest.java) | Unit | Existing storage engine unit tests | Passed (4/4) |
| [`StorageEngineIT`](file:///workspaces/SeMi/src/test/java/datasys/semi/StorageEngineIT.java) | Integration | Existing storage engine integration tests | Passed (9/9) |
| [`EngineTest`](file:///workspaces/SeMi/src/test/java/datasys/semi/EngineTest.java) | Unit | Team name verification | Passed (1/1) |

## 4. Edge Cases & Invariants Tested
- **Malformed Inputs**:
  - Missing `;` -> Line 1, Col 19.
  - Unbalanced parens -> Line 1, Col 31.
  - Unknown type `city TEXT` -> Line 1, Col 25.
  - Unterminated string `'trips.csv;` -> Line 1, Col 16.
  - Missing `FROM` keyword -> Line 1, Col 9.
- **Literal Typing**: Negative longs (`-1L`), negative doubles (`-1.5`), string literals with stripped quotes.
- **Round-Trip Fidelity**: `parse(print(s)).getFirst().equals(s)` across all combinations.
- **Binder Type Checking**: Rejected strings for numeric columns, doubles for long columns, longs for string columns, and duplicate column names.

