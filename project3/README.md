# Project 3: join-order benchmark (Exercise 12)

Scripts for the benchmark in `exercise_descriptions/Exercise12.md` §1. They need only
`bash`, `python3` (standard library), and the packaged engine.

The benchmark runs on each group member's machine. Every machine has its own results folder, so
`MACHINE` (`mikma` or `sebs`) is required by both shell scripts.

| File | Purpose |
|---|---|
| `generate_data.py` | Writes `customers.csv` (1,000 rows, 100 cities round-robin), `orders.csv` and `shipments.csv` (100,000 rows each, 100 per customer, foreign keys shuffled) to `project3/data/`. The seed is fixed (`20261203`); `--per-customer`, `--customers`, `--cities` scale it. |
| `run_benchmark.sh` | Loads the data once, then runs the four configurations interleaved: 1 warm-up + `REPS` (default 7) measured runs each. It saves one engine log per run and writes `runs.csv`, `wallclock.csv`, and `conditions.txt` (machine, JVM, heap, commit) to `results/$MACHINE/`. |
| `analyze_benchmark.sh` | Loads the machine's run logs and `runs.csv` into a separate engine instance and labels the log lines with **the engine's own join** (`SELECT * FROM logs, runs WHERE sessionId = r_session AND className = ...`). It writes to `results/$MACHINE/analysis/`, then calls `summarize.py`. |
| `summarize.py` | Uses the engine's output to compute the median, min, quartiles, and max of `durationMs`, the estRows vs. rowsOut table with q-error, `results.md`, and the `runtime.svg` chart. Run it on its own with `--machine mikma` (or `--analysis-dir` and `--wallclock`). |

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

Replace `mikma` with your own folder name (`sebs`):

```bash
mvn -DskipTests package                      # builds target/engine.jar
python3 project3/generate_data.py            # data with the fixed seed
MACHINE=mikma project3/run_benchmark.sh      # REPS=7 HEAP=4g CITY=city042 by default
MACHINE=mikma project3/analyze_benchmark.sh  # writes project3/results/mikma/analysis/
```

Run the benchmark on your own machine with your own folder name, so the two sets of numbers
never overwrite each other. `MACHINE` is only a folder name. The hardware itself is recorded in
`conditions.txt`.

Warm vs. cold: every run starts a new JVM (cold JIT), and the warm-up repetition warms the OS
page cache, so the measured runs are "cold JVM, warm page cache".

## Results layout

```
results/
├── mikma/                     # results from mikma's machine
│   ├── conditions.txt         # machine, JVM, heap, commit, sizes, method
│   ├── runs.csv               # r_session,r_config,r_repetition
│   ├── wallclock.csv          # config,repetition,session,wall_ms,result_rows
│   ├── logs/                  # raw engine logs per run (git-ignored)
│   └── analysis/
│       ├── results.md         # runtime + intermediate-size tables for the report
│       ├── runtime.svg        # runtime chart for the report
│       ├── runtime_summary.csv, intermediate_sizes.csv
│       ├── joins.csv, statements.csv, orderer.csv   # the engine's labeled join output
│       └── analysis.log
└── sebs/                      # same layout, from sebs' machine (still to be run)
```

The report material for each machine is `analysis/runtime.svg`, `analysis/results.md`, and
`conditions.txt`.
