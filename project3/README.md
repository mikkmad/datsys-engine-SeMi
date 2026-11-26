# Project 3: join-order benchmark (Exercise 12)

Scripts for the benchmark in `exercise_descriptions/Exercise12.md` §1. They need only
`bash`, `python3` (standard library), and the packaged engine.

| File | Purpose |
|---|---|
| `generate_data.py` | Writes `customers.csv` (1,000 rows, 100 cities round-robin), `orders.csv` and `shipments.csv` (100,000 rows each, 100 per customer, foreign keys shuffled) to `project3/data/`. The seed is fixed (`20261203`); `--per-customer`, `--customers`, `--cities` scale it. |
| `run_benchmark.sh` | Loads the data once, then runs the four configurations interleaved: 1 warm-up + `REPS` (default 7) measured runs each. It saves one engine log per run and writes `runs.csv`, `wallclock.csv`, and `conditions.txt` (machine, JVM, heap, commit). |
| `analyze_benchmark.sh` | Loads all run logs and `runs.csv` into a separate engine instance and labels the log lines with **the engine's own join** (`SELECT * FROM logs, runs WHERE sessionId = r_session AND className = ...`). Then it calls `summarize.py`. |
| `summarize.py` | Uses the engine's output to compute the median, min, quartiles, and max of `durationMs`, the estRows vs. rowsOut table with q-error, `results.md`, and the `runtime.svg` chart. |

## Configurations

| Config | Flag | FROM list |
|---|---|---|
| `min` | `-j syntax` | `customers, shipments, orders` |
| `max` | `-j syntax` | `orders, shipments, customers` |
| `opt_min_from` | `-j optimized` | `customers, shipments, orders` |
| `opt_max_from` | `-j optimized` | `orders, shipments, customers` |

All four use `WHERE o_custname = s_custname AND s_custname = c_name AND c_city = 'city042'`.
`run_benchmark.sh` fails if they do not all return the same number of rows.

## Running it

```bash
mvn -DskipTests package                 # builds target/engine.jar
python3 project3/generate_data.py       # data with the fixed seed
MACHINE=mikma project3/run_benchmark.sh      # REPS=7 HEAP=4g CITY=city042 by default
MACHINE=mikma project3/analyze_benchmark.sh  # writes project3/results/mikma/analysis/
```

Results are kept per machine, one folder per group member: `project3/results/mikma/` and
`project3/results/sebs/`. `MACHINE` is required and picks the folder. Run on your own machine
with your own name so the two sets of numbers never overwrite each other.

Each machine's report material is `analysis/runtime.svg`, `analysis/results.md`, and
`conditions.txt`. Warm vs. cold: every run starts a new JVM (cold JIT), and the warm-up
repetition warms the OS page cache, so the measured runs are "cold JVM, warm page cache".
