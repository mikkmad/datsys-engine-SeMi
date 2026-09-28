# Walkthrough — Exercise 4, Part 1: The Operator Model

Plan: [`agents/implementation_plans/exercise4_1_volcano_operator_model.md`](file:///workspaces/datsys-engine-SeMi/agents/implementation_plans/exercise4_1_volcano_operator_model.md)

## 1. Summary of Work Accomplished

The two Volcano operators of Exercise 4 §1 are implemented and unit-tested:

- `ScanOperator` — a leaf that reads an explicit list of partitions and returns their rows in order.
  It has no predicate and makes no pruning decision.
- `FilterOperator` — a parent that pulls rows from a child and emits the ones passing a predicate,
  reporting `rowsIn` / `rowsOut` in `close()`.

Supporting them: a documented `Operator` pull contract, a `BoundPredicate` record carrying a
predicate whose column has already been resolved to an index, and two new read APIs on
`StorageEngine` so a caller can ask for a table's partitions and read one of them without a
predicate.

`select(...)` is deliberately **not** rewired onto the pipeline in this part, and pruning still lives
in `select(...)`. Both move in Part 2 when the planner arrives. The Exercise 2 integration tests were
not touched and stay green, which is the evidence that nothing observable changed.

## 2. Key Changes & Architecture

### `datasys.semi.operators.Operator`
Documented the lifecycle that everything else relies on: `open()` once, `next()` until it returns
`null`, `close()` once; operators are single-threaded cursors, one pipeline per statement.

### `datasys.semi.operators.ScanOperator`
```
ScanOperator(engine, tableName, partitions)
```
Buffers **one partition at a time**: `next()` serves from the current buffer and pulls the following
partition only when that buffer runs dry, so the pipeline never holds more than one partition in
memory even though the underlying read is whole-file. The partition list is defensively copied, and
`close()` drops the buffer and logs `partitionsRead` / `rowsOut`.

The interesting property is what the class does *not* contain: no predicate field, no min/max
inspection, no catalog lookup beyond the table name. Handed an empty list it opens no file at all —
which is exactly how a fully pruned query will be expressed once the planner exists.

### `datasys.semi.operators.FilterOperator`
```
FilterOperator(child, predicate)
```
`next()` loops on the child until a row passes or the child is exhausted. The row test is the week 2
semantics unchanged — `Comparable.compareTo` against the typed constant, then a switch on the
comparison. `close()` emits the summary line and then closes the child:

```
operator=Filter column=1 comparison=GREATER_THAN const=100 rowsIn=4 rowsOut=2
```

### `datasys.semi.models.BoundPredicate`
`(int columnIndex, Comparison comparison, Object constant)`. Resolving the column name to a position
before execution keeps the filter free of per-row schema lookups and lets its constructor stay at the
`(child, predicate)` shape the exercise specifies, rather than taking a schema it would have to
search on every row.

### `datasys.semi.engine.StorageEngine`
- `partitions(String tableName)` — unmodifiable list of the table's partitions in catalog order. The
  planner will read their min/max summaries in Part 2; today it is how a caller hands a subset to a
  scan.
- `readPartition(String tableName, Partition partition)` — reads every row of one partition, no
  predicate involved. Wraps the `IOException` in an `IllegalStateException` after a `LOGGER.error`,
  so a failed read still leaves a line in the session log.
- Refactor: the header-validating decode loop was extracted into a private `readRows(Path, schema)`,
  and `readMatchingRows(...)` re-expressed on top of it. The binary partition format is now decoded
  in exactly one place, which both the old `select(...)` path and the new scan go through.

## 3. Verification Results

`mvn -B verify`:

| Suite | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| `ScanOperatorTest` *(new)* | 2 | 0 | 0 | 0 |
| `FilterOperatorTest` *(new)* | 1 | 0 | 0 | 0 |
| `SqlParserTest` | 5 | 0 | 0 | 0 |
| `SqlPrinterTest` | 4 | 0 | 0 | 0 |
| `StorageEngineUnitTest` | 4 | 0 | 0 | 0 |
| `EngineTest` | 1 | 0 | 0 | 0 |
| **Surefire total** | **17** | **0** | **0** | **0** |
| `StorageEngineIT` *(unchanged)* | 9 | 0 | 0 | 0 |
| `BinderIT` *(unchanged)* | 6 | 0 | 0 | 0 |
| **Failsafe total** | **15** | **0** | **0** | **0** |

```
[INFO] BUILD SUCCESS
```

The helper `TestListOperator` is package-private and carries no `@Test` methods, so despite matching
Surefire's `Test*.java` include pattern it is not discovered as a test class — confirmed by its
absence from the `Running ...` lines.

## 4. Edge Cases & Invariants Tested

Coverage is deliberately limited to the two cases Exercise 4 §6 names for these operators. §6 tests
3–6 cover the planner and the front door and belong with §2–§3.

**§6 test 1 — `FilterOperator` over a stub child**
`TestListOperator` serves four rows from a list; the filter is given `distance > 100` and emits
exactly the two passing rows, in the child's order. No storage is involved.

**§6 test 2 — `ScanOperator` returns exactly the rows of the partitions it is handed**
- Handed partitions 1 and 3 of the four-partition golden table, the scan returns precisely those four
  rows in order — proving it reads what it is handed and not the whole table.
- **Empty partition list**: `next()` returns `null` immediately and no data file is opened. This is
  the fully-pruned query the planner will express in §2.

## 5. Scope Boundary

This sub-exercise is Exercise 4 §1 only. Left untouched for later sub-exercises:

- `select(...)` still does its own pruning and its own reading; §2 replaces that with
  `Filter(Scan(table, survivingPartitions), predicate)`.
- The `decision=READ|PRUNED` log lines still come from `StorageEngine`; §2 moves them to the planner,
  and the `className` column of the log is where that refactor becomes visible.
- `Engine.main`, the SQL front door and `statementNumber` (§3–§4) are unchanged.

`StorageEngine.shouldPrune(...)` is already public, so the planner will be able to reuse the helper
unit-tested in Exercise 2 from outside the storage package without further widening.
