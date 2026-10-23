#!/usr/bin/env bash
# Runs the experiment from docs/experiment-design.md: COPY and SELECT * on each
# data size, one cold run followed by REPS measured runs.
#
# Generate the CSV files first (python3 data_generator.py) and build the JAR
# (mvn package). Each run's engine log is saved as
# experiment-logs/engine-<statement>-<rows>-<run>.log, where run 0 is the cold run.
#
# Usage: HEAP=4g ./run-experiments.sh
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"

SIZES="${SIZES:-40000 400000 4000000 40000000}"
REPS=8
SCHEMA="(city STRING, distance LONG, price DOUBLE)"
LOG_DIR="$ROOT/experiment-logs"
RUN_DIR="$ROOT/experiment-run"    # engine working dir: it writes its log to RUN_DIR/logs/
DATA_DIR="$RUN_DIR/data"
export ENGINE_JAVA_OPTS="-Xmx${HEAP:-2g} -Dsemi.data.dir=${DATA_DIR}"

# Runs the engine with a fresh log and saves that log as LOG_DIR/<name>.log.
# Usage: run_engine <name> <engine args...>
run_engine() {
    local name="$1"
    shift
    rm -rf "$RUN_DIR/logs"
    (cd "$RUN_DIR" && "$ROOT/engine" "$@")

    # Log4j2 rolls full files away as engine-1.log, engine-2.log, ... (oldest first).
    local i=1
    {
        while [ -f "$RUN_DIR/logs/engine-$i.log" ]; do
            cat "$RUN_DIR/logs/engine-$i.log"
            i=$((i + 1))
        done
        cat "$RUN_DIR/logs/engine.log"
    } > "$LOG_DIR/$name.log"
}

# Resolves the generated CSV file path for a given row count (e.g. 40000 -> data_1mb.csv).
data_file() {
    local rows="$1"
    local label_file="$ROOT/generated_data/data_$((rows / 40000))mb.csv"
    if [ -f "$label_file" ]; then
        echo "$label_file"
    else
        echo "$ROOT/generated_data/data_$rows.csv"
    fi
}

mkdir -p "$LOG_DIR" "$RUN_DIR"

for rows in $SIZES; do
    csv="$(data_file "$rows")"

    # COPY: wipe the data directory before every run, since a table only takes one COPY.
    for run in $(seq 0 "$REPS"); do
        [ "$run" -eq 0 ] && echo "COPY $rows rows: cold run" || echo "COPY $rows rows: run $run"
        rm -rf "$DATA_DIR"
        run_engine "engine-COPY-$rows-$run" -c "CREATE TABLE t $SCHEMA; COPY t FROM '$csv';" 2> /dev/null
    done

    # SELECT: full scan of the table loaded by the last COPY run.
    for run in $(seq 0 "$REPS"); do
        [ "$run" -eq 0 ] && echo "SELECT $rows rows: cold run" || echo "SELECT $rows rows: run $run"
        run_engine "engine-SELECT-$rows-$run" -c "SELECT * FROM t;" > /dev/null 2>&1
    done
done

rm -rf "$RUN_DIR"
