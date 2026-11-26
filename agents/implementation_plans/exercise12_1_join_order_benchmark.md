# Implementation Plan: Exercise 12 §1 (Join-Order Benchmark Scripts)

## 1. Sub-Exercise Reference
[`exercise_descriptions/Exercise12.md`](../../exercise_descriptions/Exercise12.md), §1 "The benchmark".

## 2. Background & Objective
Measure how much join order matters for one three-way join (two fact tables and one dimension table), and check whether `-j optimized` finds the min order whichever way the `FROM` list is written. The scripts and seed are committed so the numbers can be reproduced.

## 3. Requirements & Invariants
- The data comes from a script with a fixed seed: `customers` 1,000 rows over 100 cities (round-robin), `orders` and `shipments` 100,000 rows each, exactly 100 per customer, with foreign keys shuffled.
- Configurations: min and max (`-j syntax`), plus `-j optimized` with both `FROM` lists. All four use the same predicates and must return the same results.
- At least 5 measured runs per configuration. Report the median and spread, and state warm vs. cold. Record the machine, JVM, heap, and commit.
- Runtimes come from `durationMs`, and intermediate sizes from `estRows`/`rowsOut` in the engine log.
- At least one analysis runs through the engine's own joins (`logs` ⋈ `runs`).
- No Java changes. The scripts use only bash and the Python standard library (matplotlib is not available).

## 4. Proposed Changes
New folder `project3/`: `generate_data.py`, `run_benchmark.sh`, `analyze_benchmark.sh`, `summarize.py`, `README.md`, `.gitignore` (`data/`, `work/`).

## 5. Verification Plan
- Small-scale smoke run (`--customers 100 --cities 10 --per-customer 20`, `REPS=2`) through all three steps.
- Full-scale run with `REPS=1`: all configurations return 100,000 rows, and the max order's first join produces 10,000,000 rows.
- `mvn -B verify` still passes (no production code changed).
