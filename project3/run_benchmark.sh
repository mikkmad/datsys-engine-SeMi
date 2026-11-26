#!/usr/bin/env bash
# Runs the Exercise 12 join-order benchmark.
#
# One three-way join, four configurations:
#   min           -j syntax     FROM customers, shipments, orders  (filtered dimension joins first)
#   max           -j syntax     FROM orders, shipments, customers  (the two fact tables join first)
#   opt_min_from  -j optimized  FROM customers, shipments, orders
#   opt_max_from  -j optimized  FROM orders, shipments, customers
# The predicates are identical in all four, so the results are identical too.
#
# The data is loaded once into a fresh data directory. Then every repetition
# runs each configuration once, interleaved (min, max, opt_min_from,
# opt_max_from, min, ...), so slow drift on the machine hits all
# configurations alike. Repetition 0 is a warm-up that fills the OS page
# cache; the summary skips it. Every execution is a new JVM, so the measured
# runs are "cold JVM, warm OS page cache".
#
# Outputs, in RESULTS_DIR (default project3/results/$MACHINE):
#   logs/<config>-<rep>.log  the engine log of each execution (one session each)
#   logs/load.log            the engine log of the CREATE TABLE + COPY session
#   runs.csv                 r_session,r_config,r_repetition (no header, for COPY)
#   wallclock.csv            config,repetition,session,wall_ms,result_rows
#   conditions.txt           machine, JVM, heap, commit, sizes, and method
#
# Prerequisites: python3 project3/generate_data.py   and   mvn -DskipTests package
#
# MACHINE (required) names the results folder, one per group member's machine:
#   project3/results/mikma, project3/results/sebs
#
# Usage: MACHINE=mikma project3/run_benchmark.sh
#        MACHINE=sebs REPS=10 HEAP=8g CITY=city007 project3/run_benchmark.sh
set -euo pipefail

PROJECT3="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$PROJECT3/.." && pwd)"

REPS="${REPS:-7}"                         # measured repetitions per configuration (>= 5)
HEAP="${HEAP:-4g}"
CITY="${CITY:-city042}"
DATA_CSV_DIR="${DATA_CSV_DIR:-$PROJECT3/data}"
if [ -z "${MACHINE:-}" ]; then
    echo "set MACHINE to the machine's results folder, e.g. MACHINE=mikma $0" >&2
    exit 1
fi
RESULTS_DIR="${RESULTS_DIR:-$PROJECT3/results/$MACHINE}"
WORK_DIR="$PROJECT3/work/benchmark"       # engine working dir: the engine writes WORK_DIR/logs/
ENGINE_DATA_DIR="$PROJECT3/work/benchmark-data"
LOG_DIR="$RESULTS_DIR/logs"
CONFIGS="min max opt_min_from opt_max_from"
export ENGINE_JAVA_OPTS="-Xmx$HEAP -Dsemi.data.dir=$ENGINE_DATA_DIR"

MIN_FROM="customers, shipments, orders"
MAX_FROM="orders, shipments, customers"
PREDICATES="o_custname = s_custname AND s_custname = c_name AND c_city = '$CITY'"

# Prints the join mode (-j value) of a configuration.
join_mode() {
    case "$1" in
        min | max) echo syntax ;;
        opt_min_from | opt_max_from) echo optimized ;;
        *) echo "unknown configuration: $1" >&2; exit 1 ;;
    esac
}

# Prints the benchmark query of a configuration.
benchmark_query() {
    case "$1" in
        min | opt_min_from) echo "SELECT * FROM $MIN_FROM WHERE $PREDICATES;" ;;
        max | opt_max_from) echo "SELECT * FROM $MAX_FROM WHERE $PREDICATES;" ;;
        *) echo "unknown configuration: $1" >&2; exit 1 ;;
    esac
}

# Concatenates the live log and its rolled files (engine-1.log is the oldest) into one file.
# Usage: collect_log <target file>
collect_log() {
    local target="$1" i=1
    {
        while [ -f "$WORK_DIR/logs/engine-$i.log" ]; do
            cat "$WORK_DIR/logs/engine-$i.log"
            i=$((i + 1))
        done
        cat "$WORK_DIR/logs/engine.log"
    } > "$target"
}

# Prints the sessionId of the (single-session) log file given as argument.
session_of() {
    grep -m1 ',Engine,engine started' "$1" | cut -d, -f2
}

# Prints the current time in milliseconds.
now_ms() {
    echo $(($(date +%s%N) / 1000000))
}

# Prints the number of logical CPU cores (Linux: nproc, macOS: sysctl).
cpu_cores() {
    nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo "unknown"
}

# Prints the CPU model name (Linux: /proc/cpuinfo, macOS: sysctl).
cpu_model() {
    if [[ -r /proc/cpuinfo ]]; then
        grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | sed 's/^ *//'
    else
        sysctl -n machdep.cpu.brand_string 2>/dev/null || echo "unknown"
    fi
}

# Prints the total physical memory in GiB (Linux: /proc/meminfo, macOS: sysctl).
total_memory() {
    if [[ -r /proc/meminfo ]]; then
        awk '/MemTotal/ { printf "%.1f GiB", $2 / 1048576 }' /proc/meminfo
    else
        sysctl -n hw.memsize 2>/dev/null | awk '{ printf "%.1f GiB", $1 / 1073741824 }'
    fi
}

# Writes conditions.txt: everything the report must state once.
write_conditions() {
    local commit dirty=""
    commit="$(git -C "$ROOT" rev-parse HEAD)"
    git -C "$ROOT" diff --quiet HEAD -- src pom.xml || dirty=" (uncommitted changes in src/ or pom.xml)"
    {
        echo "date:        $(date -Iseconds)"
        echo "commit:      $commit$dirty"
        echo "machine:     $(uname -srm), $(cpu_cores) cores"
        echo "cpu:         $(cpu_model)"
        echo "memory:      $(total_memory)"
        echo "jvm:         $(java -version 2>&1 | head -n 2 | tr '\n' ' ')"
        echo "heap:        -Xmx$HEAP"
        echo "data:        $(for t in customers orders shipments; do printf '%s=%s ' "$t" "$(wc -l < "$DATA_CSV_DIR/$t.csv" | tr -d ' ')"; done)"
        echo "filter:      c_city = '$CITY'"
        echo "configs:     $CONFIGS"
        echo "repetitions: 1 warm-up (repetition 0, discarded) + $REPS measured per configuration, interleaved"
        echo "conditions:  every execution is a new JVM (cold JIT); the OS page cache is warm after the warm-up"
    } > "$RESULTS_DIR/conditions.txt"
}

# Creates the three tables in a fresh data directory and loads the CSV files.
load_data() {
    echo "loading data from $DATA_CSV_DIR"
    rm -rf "$ENGINE_DATA_DIR" "$WORK_DIR/logs"
    (cd "$WORK_DIR" && "$ROOT/engine" -c "
        CREATE TABLE customers (c_name STRING, c_city STRING);
        CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_quantity LONG);
        CREATE TABLE shipments (s_shipkey LONG, s_custname STRING, s_weight DOUBLE);
        COPY customers FROM '$DATA_CSV_DIR/customers.csv';
        COPY orders FROM '$DATA_CSV_DIR/orders.csv';
        COPY shipments FROM '$DATA_CSV_DIR/shipments.csv';" 2> /dev/null)
    collect_log "$LOG_DIR/load.log"
}

# Runs one configuration once, saves its log, and records the session in runs.csv.
# Usage: run_configuration <config> <repetition>
run_configuration() {
    local config="$1" repetition="$2" log session started_ms wall_ms result_rows
    log="$LOG_DIR/$config-$repetition.log"
    rm -rf "$WORK_DIR/logs"

    started_ms="$(now_ms)"
    result_rows="$(cd "$WORK_DIR" && "$ROOT/engine" -j "$(join_mode "$config")" -c "$(benchmark_query "$config")" 2> /dev/null | wc -l)"
    wall_ms=$(($(now_ms) - started_ms))

    collect_log "$log"
    session="$(session_of "$log")"
    if grep -q ',ERROR,' "$log"; then
        echo "  $config rep $repetition logged an ERROR, see $log" >&2
        exit 1
    fi
    echo "$session,$config,$repetition" >> "$RESULTS_DIR/runs.csv"
    echo "$config,$repetition,$session,$wall_ms,$result_rows" >> "$RESULTS_DIR/wallclock.csv"
    echo "  $config rep $repetition: ${wall_ms} ms wall, $result_rows rows"
}

# Fails unless every configuration returned the same number of rows.
check_results_agree() {
    local distinct
    distinct="$(cut -d, -f5 "$RESULTS_DIR/wallclock.csv" | tail -n +2 | sort -u | wc -l)"
    if [ "$distinct" -ne 1 ]; then
        echo "configurations returned different row counts, see $RESULTS_DIR/wallclock.csv" >&2
        exit 1
    fi
    echo "all executions returned $(cut -d, -f5 "$RESULTS_DIR/wallclock.csv" | tail -n 1) rows"
}

main() {
    [ -f "$ROOT/target/engine.jar" ] || { echo "build the engine first: mvn -DskipTests package" >&2; exit 1; }
    [ -f "$DATA_CSV_DIR/orders.csv" ] || { echo "generate the data first: python3 project3/generate_data.py" >&2; exit 1; }

    rm -rf "$RESULTS_DIR/logs" "$RESULTS_DIR/runs.csv" "$RESULTS_DIR/wallclock.csv"
    mkdir -p "$LOG_DIR" "$WORK_DIR"
    : > "$RESULTS_DIR/runs.csv"
    echo "config,repetition,session,wall_ms,result_rows" > "$RESULTS_DIR/wallclock.csv"
    write_conditions
    load_data

    for repetition in $(seq 0 "$REPS"); do
        [ "$repetition" -eq 0 ] && echo "repetition 0 (warm-up)" || echo "repetition $repetition of $REPS"
        for config in $CONFIGS; do
            run_configuration "$config" "$repetition"
        done
    done

    check_results_agree
    echo "done: logs and runs.csv in $RESULTS_DIR; next: MACHINE=$MACHINE project3/analyze_benchmark.sh"
}

main "$@"
