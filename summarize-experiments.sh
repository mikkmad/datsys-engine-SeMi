#!/usr/bin/env bash
# Summarizes the logs written by run-experiments.sh for plotting.
#
# For every size it takes durationMs from the Executor summary line of runs
# 1..REPS (run 0, the cold run, is skipped) and writes the mean and sample
# standard deviation per statement to experiment-results/results.csv.
# Size is the CSV file size in MB (10^6 bytes) from generated_data/.
#
# Usage: ./summarize-experiments.sh [log directory] [output csv]
#        (defaults: experiment-logs, experiment-results/results.csv)
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"

LOG_DIR="${1:-$ROOT/experiment-logs}"
OUT="${2:-$ROOT/experiment-results/results.csv}"
SIZES="${SIZES:-40000 400000 4000000 40000000}"
REPS=8

# Prints the durationMs of the measured runs of one statement and size, one per line.
# Usage: measured_durations <COPY|SELECT> <rows>
measured_durations() {
    local statement="$1" rows="$2" run log
    for run in $(seq 1 "$REPS"); do
        log="$LOG_DIR/engine-$statement-$rows-$run.log"
        grep -h ",Executor,statement=$statement " "$log" | grep -o 'durationMs=[0-9]*' | cut -d= -f2
    done
}

# Prints "mean,stddev" of the numbers on stdin, and fails if there are not REPS of them.
mean_and_stddev() {
    awk -v expected="$REPS" '
        { sum += $1; sumsq += $1 * $1; n++ }
        END {
            if (n != expected) { print "expected " expected " runs but found " n > "/dev/stderr"; exit 1 }
            mean = sum / n
            variance = (sumsq - n * mean * mean) / (n - 1)
            printf "%.1f,%.1f", mean, sqrt(variance < 0 ? 0 : variance)
        }'
}

mkdir -p "$(dirname "$OUT")"
echo "rows,size_mb,copy_ms,copy_std_ms,select_ms,select_std_ms" > "$OUT"

for rows in $SIZES; do
    bytes="$(wc -c < "$ROOT/generated_data/data_$rows.csv" | tr -d ' ')"
    size_mb="$(awk -v bytes="$bytes" 'BEGIN { printf "%.2f", bytes / 1000000 }')"
    copy="$(measured_durations COPY "$rows" | mean_and_stddev)" || exit 1
    select="$(measured_durations SELECT "$rows" | mean_and_stddev)" || exit 1
    echo "$rows,$size_mb,$copy,$select" >> "$OUT"
done

cat "$OUT"
