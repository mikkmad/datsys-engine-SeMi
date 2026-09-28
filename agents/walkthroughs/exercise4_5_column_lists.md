# Walkthrough: Exercise 4 §5 (Column Lists)

## 1. Summary of Work Accomplished
Implemented the optional extension for column lists in the `SELECT` statement (Exercise 4, Section 5). The query parser now supports specific column projections (e.g., `SELECT city, price FROM trips`), which the planner translates into a Volcano `ProjectOperator`. This allows queries to return only requested columns instead of always `*`.

## 2. Key Changes & Architecture
- **Parser & Grammar**: Modified `Sql.g4` to parse `columnList` in addition to `*` for `SELECT`. Updated `SqlAstBuilder.java` to extract column identifiers into `SelectStatement`.
- **AST Model**: Modified `SelectStatement.java` to hold an `Optional<List<String>> columns` field alongside the table name and the predicate.
- **Operator Execution**: Added `ProjectOperator.java` which takes a child operator and an array of target column indices. It extracts exactly those mapped columns per row during the `next()` invocation.
- **Planner Refactor**: Modified `Planner.java` to evaluate if a `SELECT` statement requests specific columns. If so, it resolves the names against the table schema and wraps the existing plan (scan/filter) in a `ProjectOperator`.

## 3. Verification Results
- Ran `mvn clean verify` which executed all unit and integration tests successfully (0 failures, 0 errors).
- Tested `SqlParser` and `SqlPrinter` unit tests which successfully round-trip the newly updated `SelectStatement` shapes with default `*` mapping.

## 4. Edge Cases & Invariants Tested
- **No Projection (`*`)**: Handled correctly. The `Optional.empty()` results in no `ProjectOperator` being appended, natively continuing to return all fields in schema order.
- **Column Order Handling**: The columns returned follow the order defined in the user's `SELECT` query as the mapped indices fetch them sequentially per iteration.
- **Missing/Invalid Columns**: Implicitly checked during resolution. If a user queries for an unknown column, `Planner.resolveColumnIndex` will safely throw an `IllegalArgumentException` prior to any execution or operator setup.
