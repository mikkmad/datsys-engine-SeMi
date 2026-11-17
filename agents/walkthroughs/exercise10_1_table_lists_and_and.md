# Walkthrough — Exercise 10.1: Grammar — Table Lists and AND

Plan: [`agents/implementation_plans/exercise10_1_table_lists_and_and.md`](../implementation_plans/exercise10_1_table_lists_and_and.md)
Spec: [`exercise_descriptions/Exercise10.md` §1](../../exercise_descriptions/Exercise10.md)

## 1. Summary of Work Accomplished

- The grammar now accepts `SELECT ... FROM t1, t2, ... WHERE p1 AND p2 AND ...`, where each predicate is either a **filter** (`col op literal`) or a **join** (`col = col`). `DELETE` keeps its single-table, single-filter form.
- New AST record `JoinPredicate`. `SelectStatement` now carries `tableNames`, `columns` (Part 1 column-list extension kept), `filters` and `joins`.
- The AST builder sorts each parsed predicate into the right list; the printer prints filters first, then joins, all joined by ` AND `, so `parse(print(s)) = s` still holds.
- Every exhaustive `switch` over `Statement` was adapted (printer, binder, planner, executor). The runtime stays single-table until §2–§4:
  - Binder rejects a repeated table (`duplicate table in FROM list: trips`, matching DuckDB's duplicate-alias error), rejects more than one table with `multi-table SELECT is not supported yet: ...`, validates every filter, and rejects joins over a single table because both sides land in the same table (after checking both columns exist).
  - Planner supports any number of filters on one table: partition survivors are the intersection of each filter's pruning result, and one `FilterOperator` per filter is stacked above the scan.

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `Sql.g4` | `select` takes `IDENTIFIER (',' IDENTIFIER)*` and `(WHERE condition)?`; new `condition`, `predicate : filter \| join`, `filter`, `join` rules; `delete` uses `filter`; `AND` keyword before `IDENTIFIER`. |
| `models/JoinPredicate.java` | New immutable record, rejects blank names. |
| `models/SelectStatement.java` | `(List<String> tableNames, Optional<List<String>> columns, List<Predicate> filters, List<JoinPredicate> joins)`; defensive `List.copyOf`, non-empty `FROM` list, no null elements. |
| `parser/SqlAstBuilder.java` | `visitSelect` + `sortPredicates`; `visitFilter`, `visitJoin`; `visitDelete` uses `filter()`. |
| `parser/SqlPrinter.java` | `printCondition` (filters then joins) and `printJoin`. |
| `parser/Binder.java` | `bindSelect` interim rules; extracted `bindFilter`, `findColumn`, `requireDistinctTables`, `bindSingleTableJoin`. |
| `planner/Planner.java` | Plans the first `FROM` table only (the binder guards against multi-table input); `planWithPredicates` (filter stack), `intersectSurvivingPartitions`, `planProjection` extracted. |
| `executor/Executor.java` | Pins the snapshot of the first `FROM` table. |
| `SqlCorpusIT.java` | Reclassified bad cases affected by the new grammar (see §4). |

Design note: ANTLR's ALL(*) prediction resolves `predicate : filter | join` by looking at the token after `=` (literal vs. identifier), so no grammar refactoring was needed.

## 3. Verification Results

`mvn -B clean compile` — success.

`mvn -B verify`:

```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.SqlJoinGrammarTest
[INFO] Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.PlannerTest
[INFO] Tests run: 106, Failures: 0, Errors: 0, Skipped: 0          (Surefire)
[INFO] Tests run: 164, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.SqlCorpusIT
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.BinderIT
[INFO] Tests run: 201, Failures: 0, Errors: 0, Skipped: 0          (Failsafe)
[INFO] BUILD SUCCESS
```

CLI smoke test: `./engine -c "SELECT * FROM trips WHERE distance > 50 AND price < 250.0;"` returns 3 rows; `SELECT * FROM trips, trips;` fails with an `ERROR` line `duplicate table in FROM list: trips`.

## 4. Edge Cases & Invariants Tested

- **`SqlJoinGrammarTest` (Required test 1):** table lists in source order; interleaved `AND` chains sorted into filters/joins preserving order; all four testbed queries of §5; filter-only chains; `AND` case-insensitive while `andes`/`android` stay identifiers; column lists with table lists; rejected shapes (`a < b` between columns, `OR`, parentheses, dangling/leading `AND`, trailing comma in `FROM`, aliases, `1 = col`, `AND` as identifier); `DELETE` rejects table lists, `AND` and column-to-column; printer output text and round-trip for table lists, joins, filters, mixed conjunctions and column lists; interleaved source prints in canonical order and round-trips; record validation and defensive copies.
- **`BinderIT`:** two filters bind; repeated table, multi-table, same-table join, unknown join column, mismatched second filter, unknown first table all throw `IllegalArgumentException`.
- **`PlannerTest`:** `distance > 50 AND price < 250.0` reads partitions `[1, 2]` (2 pruned), stacks price filter over distance filter over scan, and returns rows with distance 88, 95, 140.
- **Corpus reclassification (`SqlCorpusIT`):**
  - `B0034` (`distance = 1 AND price = 1.0`) is now accepted.
  - `B0031` (`trips, trips`), `B0035` (`distance = NULL`, which now parses as a join to a column named `NULL`) and `B0036` (`distance = distance`) parse and are rejected by the binder.

## 5. Notes for the Next Sub-Exercises

- §2 replaces the interim binder rules with the column resolution helper (name → table + index in the concatenated row), ambiguity and join type checks.
- §4 replaces the planner's first-table-only plan with the left-deep hash-join fold, and the executor must then pin one snapshot per `FROM` table.
- `NULL` is not a keyword in this grammar, so `x = NULL` is a join on a column named `NULL`. It is rejected because no such column exists. If NULL handling ever matters, reserve `NULL` in the lexer.
