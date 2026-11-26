# Walkthrough: Exercise 12 §1 (Join-Order Benchmark Scripts)

## 1. Summary of Work Accomplished
Added `project3/`, which generates the benchmark data, runs the min, max, and optimized configurations, and analyzes the logs through the engine's own join of `logs` and `runs`. See `project3/README.md`.

## 2. Key Changes & Architecture
- `generate_data.py`: fixed seed `20261203`, round-robin cities, shuffled foreign keys, header-less CSV. Sizes can be changed with flags.
- `run_benchmark.sh`: loads the data once, then runs the configurations interleaved: 1 warm-up and `REPS` (default 7) measured runs. It keeps one log per run and writes `runs.csv` (`r_session,r_config,r_repetition`), `wallclock.csv`, and `conditions.txt`. It stops if a run logs an `ERROR` or if the configurations return different row counts.
- `analyze_benchmark.sh`: starts a separate engine instance and runs `COPY logs` and `COPY runs`, then `SELECT * FROM logs, runs WHERE sessionId = r_session AND className = ...` for `HashJoinOperator`, `Executor`, and `JoinOrderer`.
- `summarize.py`: computes the median, min, Q1, Q3, and max of `durationMs`, plus estRows vs. rowsOut with q-error. It writes `results.md` and `runtime.svg` (hand-written SVG, standard library only).

## 3. Verification Results
- Smoke run (100 customers, REPS=2): all 12 runs returned 4,000 rows, and the analysis join labeled 24 HashJoin lines.
- Full scale (REPS=1): all runs returned 100,000 rows. durationMs was min 914 ms, max 2,269 ms, opt_min_from 940 ms, and opt_max_from 971 ms. Both optimized runs chose `customers+shipments+orders`.
- `mvn -B verify`: Tests run: 208, Failures: 0, Errors: 0, Skipped: 0. BUILD SUCCESS.

## 4. Edge Cases & Invariants Tested
- Results must agree across configurations (row count check).
- The warm-up repetition 0 is excluded from all statistics.
- Rolled log files (`engine-N.log`) are concatenated in order.
- An `ERROR` in any run's log aborts the benchmark.
