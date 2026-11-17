# Implementation Plan — Exercise 10.1: Grammar — Table Lists and AND

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise10.md` §1 "Grammar: table lists and AND"](../../exercise_descriptions/Exercise10.md) and §7 Required test 1 (parser + printer round-trip).
- Later sub-exercises that build on this one: §2 binder with column resolution helper, §3 `HashJoinOperator`, §4 left-deep planning.

## 2. Background & Objective

Project 3 teaches the engine multi-table joins. The first step is the front end: a `SELECT` may now list several tables in `FROM` and carry a conjunction (`AND`) of predicates, where each predicate is either a **filter** (column compared with a constant) or a **join** (column `=` column). `DELETE` keeps its single-table, single-filter form.

Changing `SelectStatement` breaks every exhaustive `switch` over the sealed `Statement`, so printer, binder, planner and executor must be adapted. This sub-exercise keeps the engine's *runtime* single-table: multi-table binding and execution arrive in §2–§4. Until then the binder rejects multi-table statements with a clear "not supported yet" message instead of silently mis-executing them.

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | Grammar: `select : SELECT ('*' \| columnList) FROM IDENTIFIER (',' IDENTIFIER)* (WHERE condition)? ;`, `condition : predicate (AND predicate)* ;`, `predicate : filter \| join ;`, `filter : IDENTIFIER comparison=('=' \| '<' \| '>') literal ;`, `join : left=IDENTIFIER '=' right=IDENTIFIER ;`, `delete : DELETE FROM IDENTIFIER (WHERE filter)? ;`, `AND : 'AND' ;` declared before `IDENTIFIER`. The Part 1 column-list extension is kept. |
| F2 | New AST record `JoinPredicate(String leftColumn, String rightColumn)`; rejects null/blank names. |
| F3 | `SelectStatement(List<String> tableNames, Optional<List<String>> columns, List<Predicate> filters, List<JoinPredicate> joins)`; lists are defensively copied (immutable), `tableNames` non-empty with no blank names. |
| F4 | `SqlAstBuilder` sorts each parsed predicate into `filters` or `joins`, preserving source order within each list. |
| F5 | `SqlPrinter` prints `FROM t1, t2` and `WHERE <filters...> AND <joins...>` (filters first, then joins, joined by ` AND `). `parse(print(s)) = s` holds. |
| F6 | `Binder` (interim, until §2): rejects duplicate table names in `FROM`; rejects more than one table with "multi-table SELECT not supported yet"; validates every filter as before; validates both join columns exist and then rejects the join because both sides land in the one table. |
| F7 | `Planner` (interim, until §4): supports any number of filters on one table: partition survivors are the intersection of each filter's pruning result, and one `FilterOperator` per filter is stacked above the scan. Only the first `FROM` table is planned; the planner does not re-check for multiple tables, because the binder already rejects them on every execution path. |
| F8 | `Executor` pins the snapshot of the first `FROM` table (the only one until §4). |

### Invariants
- **Sealed exhaustiveness:** no `default` branches added to `Statement` switches.
- **No log-and-throw** (AGENTS §5.4): new validation throws plainly; the executor boundary logs.
- **DuckDB compatibility:** every statement the engine accepts runs in DuckDB (`AND`, comma-joins and `col = col` are standard SQL).
- **Corpus:** good corpus unchanged. Bad corpus cases now affected by the new grammar are explicitly reclassified in `SqlCorpusIT`:
  - `B0034` (`distance = 1 AND price = 1.0`) is now accepted (superseded).
  - `B0031` (`FROM trips, trips`) and `B0036` (`distance = distance`) now parse and must be rejected by the binder.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `src/main/antlr4/datasys/semi/sql/Sql.g4` | New rules `condition`, `predicate` (now `filter \| join`), `filter`, `join`; `AND` keyword; table list in `select`; `delete` uses `filter`. |
| `models/JoinPredicate.java` | New record. |
| `models/SelectStatement.java` | New components (see F3). |
| `parser/SqlAstBuilder.java` | `visitSelect` builds table list and splits predicates; `visitFilter`, `visitJoin`; `visitDelete` uses `filter()`. |
| `parser/SqlPrinter.java` | Table list and `AND` chain printing; `printJoin`. |
| `parser/Binder.java` | Interim multi-table rules (F6). |
| `planner/Planner.java` | Multiple filters per table (F7). |
| `executor/Executor.java` | Pin first table (F8). |
| Tests | Update constructor call sites; new parser tests (table lists, AND chains, join vs filter sorting, `AND` keyword case-insensitivity, rejected shapes like `a < b`, `OR`, parentheses, `DELETE ... AND`); printer round-trip for multi-table / join / mixed statements; binder tests for the interim rules; planner test for two filters; corpus reclassification. |

## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

Expected: all unit and integration tests green, including the new parser/printer tests and the full SQL corpus (`SqlCorpusIT`) with the reclassified cases.
