# Implementation Plan — Exercise 10.5: Testbed Integration Against DuckDB

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise10.md` §5 "The testbed"](../../exercise_descriptions/Exercise10.md) and §7 Required test 6 (integration).
- Builds on: [`exercise10_4_left_deep_join_planning.md`](exercise10_4_left_deep_join_planning.md). Joins already run through the main query path. The testbed CSVs are in `src/test/resources/joins/`.

## 2. Background & Objective

Required test 6: run the testbed script through `./engine -f`, and check that the sorted output equals the sorted DuckDB output for all four queries.

Two footnotes from §5 shape the test:

1. A hash join emits rows in probe-side order, and DuckDB promises no order either, so the test compares **sorted** results.
2. DuckDB's CSV sniffer reads line 1 of an all-`STRING` CSV as a header. `customers.csv` (`c_name, c_city`) is all `STRING`, so the oracle runs `COPY ... (HEADER false)`. Without that, Anna's two orders would silently disappear.

CI (`ubuntu-latest`, `mvn -B verify`) has no DuckDB. The DuckDB output is therefore generated once, committed as golden files, and reproducible with a committed script. The test itself needs no DuckDB.

## 3. Requirements & Invariants

| ID | Requirement |
|----|-------------|
| F1 | Each testbed query lives in one file, `src/test/resources/joins/queries/queryN.sql`. Both the engine test and the DuckDB oracle script read these files, so the two sides cannot drift apart. |
| F2 | Golden files `src/test/resources/joins/expected/queryN.csv` hold DuckDB's output: no header, sorted. |
| F3 | `scripts/joins-duckdb-oracle.sh` regenerates the golden files with DuckDB (`DUCKDB` env var or `duckdb` on `PATH`) using `HEADER false`. |
| F4 | `JoinTestbedIT` runs the real `./engine -f <script>` as a child process for each query. The script is `CREATE TABLE` ×4, `COPY` ×4, then the query. Each run gets a fresh temporary working directory, so `data/` and `logs/` stay out of the repository. The test asserts exit code 0 and that sorted stdout equals the sorted golden file. |
| F5 | Row counts are asserted too: 4, 4, 4, 3, as §5 states. |
| F6 | The child's stdout and stderr are redirected to files, so a full pipe cannot block the process (the console appender copies debug logs to stderr). |

### Invariants
- No production code changes. This sub-exercise only adds test assets and an oracle script.
- The test runs in the Failsafe phase, after `package` has built `target/engine.jar`.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `src/test/resources/joins/queries/query{1..4}.sql` | New: the four §5 queries. |
| `src/test/resources/joins/expected/query{1..4}.csv` | New: sorted DuckDB output. |
| `scripts/joins-duckdb-oracle.sh` | New: regenerates the expected files with DuckDB. |
| `src/test/java/datasys/semi/JoinTestbedIT.java` | New: required test 6. |

## 5. Verification Plan

```bash
scripts/joins-duckdb-oracle.sh      # golden files unchanged (git diff empty)
mvn clean compile
mvn -B verify
```

Expected: `JoinTestbedIT` passes 4 parameterised cases. All other tests stay green.
