# Walkthrough — Exercise 10.5: Testbed Integration Against DuckDB

Plan: [`agents/implementation_plans/exercise10_5_testbed_duckdb_integration.md`](../implementation_plans/exercise10_5_testbed_duckdb_integration.md)
Spec: [`exercise_descriptions/Exercise10.md` §5 and §7 test 6](../../exercise_descriptions/Exercise10.md)

## 1. Summary of Work Accomplished

- **Required test 6** is `JoinTestbedIT`. For each of the four §5 queries, it runs the real `./engine -f` as a child process and checks two things: the sorted stdout equals the sorted DuckDB output, and the row count is 4, 4, 4 or 3.
- **DuckDB oracle:** `scripts/joins-duckdb-oracle.sh` regenerates `src/test/resources/joins/expected/queryN.csv` with DuckDB. CI has no DuckDB, so the golden files are committed and the test needs no DuckDB.
- **One source of truth for the queries:** `src/test/resources/joins/queries/queryN.sql` is read by both the oracle script and the test.
- No production code changed. The joins from §4 already produce DuckDB's rows.

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `src/test/java/datasys/semi/JoinTestbedIT.java` | New. A parameterised test with 4 cases. Each case writes `CREATE TABLE` ×4, `COPY` ×4 and the query into a temp working directory, then runs `<repo>/engine -f`. `data/` and `logs/` land in the temp directory. stdout and stderr are redirected to files, because the console appender copies debug logs to stderr and a full pipe would block the child. The test asserts exit code 0, with a 60 s timeout. |
| `src/test/resources/joins/queries/query{1..4}.sql` | The four §5 queries. |
| `src/test/resources/joins/expected/query{1..4}.csv` | DuckDB output: header-less and `LC_ALL=C sort`ed. |
| `scripts/joins-duckdb-oracle.sh` | Regenerates the golden files. It uses `DUCKDB` or `duckdb` from `PATH` and `COPY ... (HEADER false)`. |

**Why `HEADER false`:** `customers.csv` is all `STRING`, so DuckDB's sniffer would treat `Anna,Copenhagen` as a header and drop Anna's two orders (the lecture 2 divergence).

**Why sorted:** the hash join emits rows in probe-side order and DuckDB promises no order. Java's natural `String` order equals `LC_ALL=C sort`.

## 3. Verification Results

`scripts/joins-duckdb-oracle.sh` (DuckDB CLI):

```
wrote expected/query1.csv (4 rows)
wrote expected/query2.csv (4 rows)
wrote expected/query3.csv (4 rows)
wrote expected/query4.csv (3 rows)
```

`mvn -B clean verify`:

```
[INFO] Tests run: 146, Failures: 0, Errors: 0, Skipped: 0     (Surefire)
[INFO] Running datasys.semi.JoinTestbedIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.JoinTestbedIT
[INFO] Tests run: 205, Failures: 0, Errors: 0, Skipped: 0     (Failsafe)
[INFO] BUILD SUCCESS
```

Sanity check: changing `299.0` to `300.0` in `expected/query4.csv` makes `query4` fail with a clear expected/actual diff. The file was then restored.

## 4. Edge Cases & Invariants Tested

- **Two-, three- and four-table chains** (queries 1–3) and a **filter below the joins** (query 4, `c_city = 'Copenhagen'`, 3 rows).
- **Duplicate join keys on the build side:** Anna has two orders, and each finds her customer and city row.
- **Column order:** `SELECT *` emits columns in `FROM`-list order, identical to DuckDB.
- **DOUBLE formatting:** `2999.0` and `299.0` print the same as DuckDB.
- **All-`STRING` table** (`customers`) loads every row in both systems.
- **The real CLI path:** shell wrapper, packaged jar, script parsing, exit code.

## 5. Notes

- Tag `v0.10` (§8) is cut on `main` after the `exercise10` branch is merged. I have not done this.
