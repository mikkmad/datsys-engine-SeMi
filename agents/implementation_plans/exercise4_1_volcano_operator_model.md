# Exercise 4, Part 1 — The Operator Model

## 1. Sub-Exercise Reference

[`exercise_descriptions/Exercise4.md`](file:///workspaces/datsys-engine-SeMi/exercise_descriptions/Exercise4.md) §1 *The operator model*, with the
required unit tests 1 and 2 from §6.

## 2. Background & Objective

Exercise 3 left the engine able to parse and bind SQL, but execution still lives entirely inside
`StorageEngine.select(...)`, which interleaves three unrelated concerns: deciding which partitions to
open, reading rows off disk, and testing each row against the predicate.

This sub-exercise extracts the last two concerns into a Volcano-style pull pipeline of two operators.
Both are deliberately dumb:

- `ScanOperator` is handed the partitions it must read and returns their rows in order. It never sees
  a predicate and makes no pruning decision.
- `FilterOperator` pulls rows from a child operator and emits the ones that pass, using the exact
  comparison semantics of week 2.

Partition pruning is *not* moved in this part; it stays in `select(...)` until the planner arrives in
Part 2. Part 1 delivers the operators and their unit tests, so the planner has something to build a
tree out of.

## 3. Requirements & Invariants

1. `Operator` is the three-method pull interface: `open()`, `next()`, `close()`.
2. `next()` returns one row as `Object[]` in schema column order, or `null` once exhausted.
3. `ScanOperator(engine, tableName, partitions)` returns exactly the rows of the partitions it is
   handed, in partition order and in row order within each partition.
4. An empty partition list yields no rows: `next()` returns `null` on the first call and no data file
   is opened. This is the fully-pruned query.
5. `FilterOperator(child, predicate)` emits only rows passing the predicate, and logs `rowsIn` and
   `rowsOut` in `close()`.
6. The filter's predicate is resolved to a column *index* before execution, so no schema lookup
   happens per row.
7. Calling `next()` or `close()` before `open()` is a programming error and throws
   `IllegalStateException`.
8. `close()` is idempotent and always propagates to the child operator.
9. Comparison semantics are unchanged from week 2: `Comparable.compareTo` against the typed constant.

## 4. Proposed Changes

### New: `datasys.semi.models.BoundPredicate`
Record `(int columnIndex, Comparison comparison, Object constant)`. The binder/planner resolves a
`Predicate`'s column name against the schema once; the operator then evaluates by position. Keeps
`FilterOperator`'s constructor to the `(child, predicate)` shape the exercise specifies without
handing the operator a schema it would otherwise have to search per row.

### `datasys.semi.operators.Operator`
Add the mandated Javadoc describing the pull contract and the `null`-terminates-the-stream invariant.

### New: `datasys.semi.operators.ScanOperator`
Holds the engine, table name and partition list. Buffers one partition at a time: `next()` serves
from the current buffer and advances to the next partition when the buffer runs dry. Tracks
`rowsOut` and the number of partitions actually opened for the `close()` log line.

### New: `datasys.semi.operators.FilterOperator`
Wraps a child operator and a `BoundPredicate`. Counts `rowsIn` / `rowsOut` and emits the summary
debug line in `close()`.

### `datasys.semi.engine.StorageEngine`
- New `public List<Partition> partitions(String tableName)` — exposes the catalog's partition list so
  a caller (the test today, the planner in Part 2) can hand a subset to the scan. Returns an
  unmodifiable copy.
- New `public List<Object[]> readPartition(String tableName, Partition partition)` — reads every row
  of one partition, no predicate involved.
- Refactor: extract the existing header-validating read loop into a private
  `readRows(Path, List<Column>)`, and re-express `readMatchingRows(...)` on top of it, so the binary
  format is decoded in exactly one place.

## 5. Verification Plan

Scope note: this sub-exercise is §1 only. Pruning, the planner, the executor and the SQL front door
(§2–§4) are explicitly out of scope and stay for a later sub-exercise. Test coverage is limited to the
two cases §6 names for these operators; §6 tests 3–6 belong with the planner and front door.

New tests in `src/test/java/datasys/semi/`:

- `TestListOperator` — the small test helper §6 asks for, serving rows from a list.
- `FilterOperatorTest` — §6 test 1: a filter over the stub child emits only the passing rows.
- `ScanOperatorTest` — §6 test 2: the scan returns exactly the rows of the partitions it is handed,
  including the empty partition list of a fully pruned query.

Commands:

```bash
mvn -B clean test      # unit tests, including the two new operator suites
mvn -B verify          # plus the untouched Exercise 2 integration tests
```

Expected: 0 failures, 0 errors. The Exercise 2 integration tests are not modified and must stay green,
since `select(...)` is behaviourally unchanged in this part.
