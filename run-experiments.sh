#!/usr/bin/env bash
# Runs the experiment from docs/experiment-design.md: COPY and SELECT * on each
# data size, one cold run followed by REPS measured runs.
#
# Generate the CSV files first (python3 data_generator.py) and build the JAR
# (mvn package). Durations are logged as durationMs in logs/engine.log.
#
# Usage: HEAP=4g ./run-experiments.sh
set -u
cd "$(dirname "$0")"

SIZES="${SIZES:-40000 400000 4000000 40000000}"
REPS=8
SCHEMA="(city STRING, distance LONG, price DOUBLE)"
DATA_DIR="experiment-data"
export ENGINE_JAVA_OPTS="-Xmx${HEAP:-2g} -Dsemi.data.dir=${DATA_DIR}"

for rows in $SIZES; do
    csv="generated_data/data_${rows}.csv"

    # COPY: wipe the data directory before every run, since a table only takes one COPY.
    for run in $(seq 0 "$REPS"); do
        [ "$run" -eq 0 ] && echo "COPY $csv: cold run" || echo "COPY $csv: run $run"
        rm -rf "$DATA_DIR"
        ./engine -c "CREATE TABLE t $SCHEMA; COPY t FROM '$csv';" 2> /dev/null
    done

    # SELECT: full scan of the table loaded by the last COPY run.
    for run in $(seq 0 "$REPS"); do
        [ "$run" -eq 0 ] && echo "SELECT $csv: cold run" || echo "SELECT $csv: run $run"
        ./engine -c "SELECT * FROM t;" > /dev/null 2>&1
    done
done

rm -rf "$DATA_DIR"
