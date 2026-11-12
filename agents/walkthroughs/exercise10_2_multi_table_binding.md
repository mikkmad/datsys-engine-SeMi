# Walkthrough — Exercise 10.2: Binding Without Qualified Names

Plan: [`agents/implementation_plans/exercise10_2_multi_table_binding.md`](../implementation_plans/exercise10_2_multi_table_binding.md)
Spec: [`exercise_descriptions/Exercise10.md` §2](../../exercise_descriptions/Exercise10.md)

## 1. Summary of Work Accomplished

- New column resolution helper `ColumnResolver` (with `TableSchema` input and `ResolvedColumn` output). It maps an unqualified column name to its one owning table, that table's `FROM` position, the column's index in its table, and its index in the joined row (table schemas concatenated in `FROM` order).
- The binder now resolves every column of a `SELECT` (column list, filters, joins) against **all** `FROM` tables:
  - unknown → `unknown column: x`
  - in two or more tables → `ambiguous column: x (in a, b)`
  - join within one table → `join predicate a = b must connect two different tables but both columns belong to t`
  - join across types → `join predicate a = b compares LONG with DOUBLE; join columns must have the same type`
  - filters → existing column and a constant of its type, as before
  - repeated table → `duplicate table in FROM list: t`
- New public overload `Binder.bind(SelectStatement, List<Catalog>)` takes one snapshot per `FROM` table. `bind(Statement)` snapshots every listed table. `bind(Statement, Catalog)` still works for single-table `SELECT`s and rejects a multi-table one.
- The executor pins one snapshot per `FROM` table at statement start and releases all of them, also on failure (including a failure while pinning a later table).
- **Temporary guard (removed in §4):** after binding, the executor rejects a multi-table `SELECT` with `UnsupportedOperationException("multi-table SELECT execution is not supported yet: ...")`. Now that the binder accepts joins, this stops the single-table planner from quietly scanning only the first table and returning wrong rows.

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `schema/TableSchema.java` | New record: table name + column specs. |
| `schema/ResolvedColumn.java` | New record: `tableName`, `tablePosition`, `columnIndexInTable`, `joinedIndex`, `column`; `type()` accessor. |
| `schema/ColumnResolver.java` | New. Builds a name → candidates index once in the constructor (O(1) `resolve`), rejects empty and repeated `FROM` lists, exposes `joinedWidth()`. |
| `parser/Binder.java` | `bind(SelectStatement, List<Catalog>)`, `requireMatchingSnapshots`, `bindJoin`. The §1 interim rules (`requireDistinctTables`, `bindSingleTableJoin`, single-table check) are gone; duplicate detection lives in the resolver. |
| `executor/Executor.java` | `runPinnedAll` (pin all / release all) and `bindAndPlanSelect` (bind + temporary guard + plan), used by both `execute` and `executeQuery`. |

Package placement: the resolver lives in `datasys.semi.schema`, which depends on nothing else in the engine. The binder converts each `Catalog` into a `TableSchema`, so there is no `schema → engine` cycle, and the planner can use the resolver in §4 without depending on `parser`.

## 3. Verification Results

`mvn -B clean compile`: success.

`mvn -B verify`:

```
[INFO] Tests run: 123, Failures: 0, Errors: 0, Skipped: 0     (Surefire)
[INFO] Tests run: 201, Failures: 0, Errors: 0, Skipped: 0     (Failsafe, incl. SqlCorpusIT 164 and BinderIT)
[INFO] BUILD SUCCESS
```

## 4. Edge Cases & Invariants Tested

- **`ColumnResolverTest` (Required test 3):** joined-row indices over the 4-table testbed (`o_orderkey`→0, `c_name`→4, `p_price`→7, `ct_city`→8, `ct_population`→10); `joinedWidth` 11; positions follow `FROM` order, not creation order; unknown column (including a column owned by an unlisted table); ambiguous column naming both owners while unshared names in the same tables still resolve; duplicate, empty, null and null-element `FROM` lists; record validation.
- **`MultiTableBinderTest` (Required test 2):** all four testbed queries bind; unknown column in filter, join and column list; ambiguous column in filter, join and column list; `LONG = DOUBLE` and `STRING = LONG` joins rejected; same-table joins rejected (different columns and the same column); filters bind against their owning table with type checks; column lists over joins bind; repeated and unknown tables; snapshot list count and order must match the `FROM` list; the single-snapshot entry point rejects multi-table statements.
- **`ExecutorTest`:** a multi-table `SELECT` binds and then hits the temporary guard. A bind failure on the second table's column and an unknown second table both fail cleanly, and afterwards `pinnedReaderCount` is 0 for every table, so no pins leak.
- **`BinderIT`:** the §1 interim test now expects a two-table join to bind.
- **Corpus:** `B0031` (`trips, trips`, duplicate table) and `B0036` (`distance = distance`, same-table join) are still binder rejections.

## 5. Notes for the Next Sub-Exercises

- §4: replace the executor guard in `bindAndPlanSelect` with planning over all snapshots. Use `ColumnResolver` to assign each filter to its table (`tablePosition`, `columnIndexInTable`) and to compute join keys (`joinedIndex` for the build side, `columnIndexInTable` for the probing table).
- The binder accepts a multi-table `FROM` list without a connecting join (a cross product). Per spec §4 the planner rejects it, with a message suggesting a different `FROM` order.
