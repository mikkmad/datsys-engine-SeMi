# Lexer & SQL Front-End Design

- Version: 1.0
- Date: 10/09/2026

## 1. Grammar & Lexer Architecture

The SQL front-end uses ANTLR 4 (`4.13.2`) to generate the lexer, parser, and parse tree visitor from `src/main/antlr4/datasys/semi/sql/Sql.g4`.

### Grammar Rules
- **Script**: `(statement ';')+ EOF` supporting multi-statement SQL scripts separated by semicolons.
- **Statements**:
  - `CREATE TABLE <identifier> (<columnDef> [, <columnDef>]* )`
  - `COPY <identifier> FROM <string_literal>`
  - `SELECT * FROM <identifier> [WHERE <predicate>]`
- **Predicate**: `<identifier> <comparison> <literal>` where comparison is `=`, `<`, or `>`.
- **Keywords**: Case-insensitive (`CREATE`, `TABLE`, `COPY`, `FROM`, `SELECT`, `WHERE`, `STRING`, `LONG`, `DOUBLE`).
- **Identifiers**: Case-preserving letters, digits, and underscores starting with letter or underscore (`[A-Z_] [A-Z_0-9]*`).
- **Literals**:
  - `STRING_LITERAL`: single-quoted ASCII string `'...'`.
  - `DOUBLE_LITERAL`: signed/unsigned floating point number with decimal point (`'-'? [0-9]+ '.' [0-9]+`).
  - `LONG_LITERAL`: signed/unsigned integer (`'-'? [0-9]+`).
- **Comments & Whitespace**: Single-line comments (`-- ...`) and whitespace (`[ \t\r\n]+`) are skipped.

## 2. AST Structure

The AST is composed of immutable Java 25 records implementing a sealed `Statement` interface in package `datasys.semi`:
- `Statement` (sealed interface)
  - `CreateTableStatement(String tableName, List<ColumnSpec> columns)`
  - `CopyStatement(String tableName, String csvFilePath)`
  - `SelectStatement(String tableName, Optional<Predicate> where)`
- `Predicate(String columnName, Comparison comparison, Object constant)`

Column specifications reuse `ColumnSpec`, `ColumnType`, and `Comparison` from the storage engine.

## 3. Parsing & Error Handling

The facade `SqlParser` removes default ANTLR error listeners and attaches a fail-fast `SqlErrorListener`. Upon any syntax or lexical violation, it immediately throws `SqlParseException` with:
- `line`: 1-based line number.
- `column`: 0-based column offset.

Each parse operation emits structured SLF4J logs with MDC context (`sessionId`, `statementNumber="0"`):
- Success: `statements=<count> durationMs=<ms>` at `DEBUG`.
- Failure: `failed line=<line> col=<col> durationMs=<ms>` at `ERROR`.

## 4. Catalog Binding

The `Binder` validates AST statements against the catalog via `StorageEngine.schema(tableName)`:
- `CREATE TABLE`: checks that columns list is non-empty and contains no duplicate column names.
- `COPY`: validates that the target table exists.
- `SELECT`: validates that the table exists, any predicate column exists in the schema, and the predicate constant matches the column's Java type.

## 5. Pretty-Printing & Round-Trip

`SqlPrinter` formats AST statements into canonical, uppercase-keyword SQL text terminated by `;`.
Guarantees the round-trip invariant:
```java
parse(print(statement)).getFirst().equals(statement)
```

