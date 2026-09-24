# Implementation Plan: Exercise 4 §5 (Column Lists)

## 1. Sub-Exercise Reference
Exercise 4, Section 5: Optional extension: column lists.
See `exercise_descriptions/Exercise4.md`.

## 2. Background & Objective
The engine currently only supports `SELECT * FROM ...`. We need to add support for projecting specific columns like `SELECT city, price FROM ...`. This requires a grammar change to parse column lists, an AST update to represent them, and a new `ProjectOperator` in the execution pipeline.

## 3. Requirements & Invariants
- `SELECT *` must still work and return all columns.
- `SELECT col1, col2` must return only the specified columns, in the requested order.
- The `ProjectOperator` must implement the `Operator` interface: `void open()`, `Object[] next()`, `void close()`.
- The parser must produce a list of requested column names in `SelectStatement`.
- The planner must insert `ProjectOperator(child, columns)` into the pipeline if specific columns are requested.
- The executor will implicitly print whatever `Object[]` the pipeline root returns, so no executor changes are needed for projection itself.

## 4. Proposed Changes
1. **ANTLR Grammar (`Sql.g4`)**:
   - Change `select` rule to allow `*` or a list of identifiers.
   - Add `columnList` rule.
2. **AST (`SelectStatement.java`)**:
   - Add `Optional<List<String>> columns` to the record. If empty, it means `*`.
3. **AST Builder (`SqlAstBuilder.java`)**:
   - Parse the selected columns and construct `SelectStatement` accordingly.
4. **ProjectOperator (`datasys/semi/operators/ProjectOperator.java`)**:
   - Implements `Operator`. Takes a child operator and the mapping from output column indices to child output column indices.
   - `next()` reads from the child and constructs a new `Object[]` with only the projected columns.
5. **Planner (`Planner.java`)**:
   - When building the pipeline for `SELECT`, if `columns` is present in the statement, resolve the column indices against the table schema and wrap the root operator (which is currently `Scan` or `Filter`) in a `ProjectOperator`.
6. **Binder (`Binder.java`)**:
   - (Optional but good) Verify that projected columns exist in the table schema. But wait, we only need to map the names to indices in the planner. Let's see if the binder handles it or the planner. In Exercise 4, the planner usually binds schemas too, or maybe `Binder` is separate? We will need to look at `Binder.java`.

## 5. Verification Plan
- Run `mvn -B clean compile` to verify ANTLR grammar generates code correctly.
- Run `mvn -B verify` to run all unit and integration tests.
- Add a test or verify via SQL front door.
