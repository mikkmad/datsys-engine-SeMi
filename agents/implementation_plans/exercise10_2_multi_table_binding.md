# Implementation Plan — Exercise 10.2: Binding Without Qualified Names

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise10.md` §2 "Binding without qualified names"](../../exercise_descriptions/Exercise10.md), §7 Required tests 2 (binder) and 3 (column resolution helper).
- Builds on: [`exercise10_1_table_lists_and_and.md`](exercise10_1_table_lists_and_and.md).

## 2. Background & Objective

After §1 the AST can describe multi-table `SELECT`s, but the binder still rejects every statement with more than one table. §2 makes the binder resolve each unqualified column name against the schemas of **all** `FROM` tables:

- name in no table → unknown column;
- name in two or more tables → ambiguous (as DuckDB: `Ambiguous reference to column name`);
- a join's two sides must resolve to two different tables and to columns of the same type (the hash join compares keys with `equals`, so `LONG 3` never equals `DOUBLE 3.0`);
- filters bind as before (existing column, constant of the column's type).

The question "which table owns this column, and where does it sit in the joined row?" is shared by binder and planner (§4), so it is answered once by a small, unit-tested helper. The joined row is the concatenation of the table schemas in `FROM`-list order.

## 3. Requirements & Invariants

| ID | Requirement |
|----|-------------|
| F1 | `schema.TableSchema(String tableName, List<ColumnSpec> columns)` record: one `FROM` table's name and schema. |
| F2 | `schema.ResolvedColumn(String tableName, int tablePosition, int columnIndexInTable, int joinedIndex, ColumnSpec column)` record. |
| F3 | `schema.ColumnResolver(List<TableSchema>)`: rejects an empty list and repeated table names; `resolve(name)` returns the single owner or throws `IllegalArgumentException` (`unknown column: x` / `ambiguous column: x (in a, b)`); `joinedWidth()` returns the concatenated row width. Built once, O(1) lookups. |
| F4 | `Binder.bind(SelectStatement, List<Catalog>)` new public overload: one snapshot per `FROM` table, in order. Validates snapshots match the `FROM` list, builds a `ColumnResolver`, resolves projected columns, filters (with type check) and joins (different tables, equal types). |
| F5 | `Binder.bind(Statement)` snapshots every `FROM` table; `Binder.bind(Statement, Catalog)` keeps working for single-table `SELECT`s and rejects a multi-table `SELECT` with a message asking for one snapshot per table. |
| F6 | `Executor` pins one snapshot per `FROM` table at statement start (spec §4: "A multi-table statement pins one snapshot per table") and releases all of them, also on failure. |
| F7 | Until hash joins are planned (§4), the executor rejects a bound multi-table `SELECT` with `UnsupportedOperationException` instead of letting the single-table planner silently scan only the first table. This guard is removed in §4. |

### Invariants
- No package cycles: the resolver lives in `datasys.semi.schema`, which depends on nothing else in the engine; the binder converts `Catalog`s to `TableSchema`s.
- No log-and-throw; the executor boundary logs failures.
- Corpus unchanged: `B0031` (`trips, trips`) and `B0036` (`distance = distance`) are still binder rejections.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `schema/TableSchema.java`, `schema/ResolvedColumn.java`, `schema/ColumnResolver.java` | New. |
| `parser/Binder.java` | Multi-table `bindSelect` via `ColumnResolver`; remove interim single-table rules. |
| `executor/Executor.java` | Pin/release all `FROM` tables; temporary multi-table execution guard. |
| `ColumnResolverTest.java` | New: required test 3. |
| `MultiTableBinderTest.java` | New: required test 2 plus acceptance of the testbed queries. |
| `BinderIT.java` | Update the §1 interim test (multi-table statements now bind). |
| `ExecutorTest.java` | Multi-table `SELECT` binds, then fails with the §4 guard; unknown column across tables fails at binding. |

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

Expected: all unit and integration tests green, including `ColumnResolverTest`, `MultiTableBinderTest` and the full SQL corpus.
