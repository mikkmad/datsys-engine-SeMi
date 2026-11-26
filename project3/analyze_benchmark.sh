#!/usr/bin/env bash
# Analyzes the join-order benchmark through the engine's own joins (Exercise 12 §1).
#
# Loads the benchmark logs and runs.csv into a separate engine instance and
# joins them, so every log line is labeled with the configuration that
# produced it:
#
#   CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG,
#                      logLevel STRING, className STRING, logMessage STRING);
#   CREATE TABLE runs (r_session STRING, r_config STRING, r_repetition LONG);
#   SELECT * FROM logs, runs WHERE sessionId = r_session AND className = 'HashJoinOperator';
#
# The engine's result rows are written to RESULTS_DIR/analysis/ and then
# summarized by summarize.py into the runtime chart and the
# estimated-vs.-actual table.
#
# Outputs, in RESULTS_DIR/analysis (default project3/results/analysis):
#   joins.csv       HashJoinOperator lines + run labels   (estRows / rowsOut per join)
#   statements.csv  Executor lines + run labels           (durationMs per statement)
#   orderer.csv     JoinOrderer lines + run labels        (the planner's choices per step)
#   analysis.log    the engine log of the analysis session itself
#   + everything summarize.py writes
#
# Usage: project3/analyze_benchmark.sh      (after project3/run_benchmark.sh)
set -euo pipefail

PROJECT3="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$PROJECT3/.." && pwd)"

RESULTS_DIR="${RESULTS_DIR:-$PROJECT3/results}"
ANALYSIS_DIR="$RESULTS_DIR/analysis"
WORK_DIR="$PROJECT3/work/analysis"        # engine working dir: the engine writes WORK_DIR/logs/
ENGINE_DATA_DIR="$PROJECT3/work/analysis-data"
ALL_LOGS="$PROJECT3/work/benchmark-logs.csv"
export ENGINE_JAVA_OPTS="-Xmx${HEAP:-2g} -Dsemi.data.dir=$ENGINE_DATA_DIR"

LABELED_JOIN="FROM logs, runs WHERE sessionId = r_session"

# Concatenates the logs of all benchmark executions (not the load session) into one CSV.
collect_benchmark_logs() {
    local count
    count="$(find "$RESULTS_DIR/logs" -name '*-*.log' | wc -l)"
    [ "$count" -gt 0 ] || { echo "no benchmark logs in $RESULTS_DIR/logs; run project3/run_benchmark.sh first" >&2; exit 1; }
    find "$RESULTS_DIR/logs" -name '*-*.log' -print0 | sort -z | xargs -0 cat > "$ALL_LOGS"
    echo "collected $(wc -l < "$ALL_LOGS" | tr -d ' ') log lines from $count benchmark executions"
}

# Runs SQL in the analysis engine. Usage: run_engine <sql>
run_engine() {
    (cd "$WORK_DIR" && "$ROOT/engine" -c "$1" 2> /dev/null)
}

# Creates the logs and runs tables in a fresh data directory and loads them.
load_analysis_tables() {
    rm -rf "$ENGINE_DATA_DIR" "$WORK_DIR/logs"
    run_engine "
        CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG,
                           logLevel STRING, className STRING, logMessage STRING);
        CREATE TABLE runs (r_session STRING, r_config STRING, r_repetition LONG);
        COPY logs FROM '$ALL_LOGS';
        COPY runs FROM '$RESULTS_DIR/runs.csv';" > /dev/null
}

# Runs one labeling join for a class name and writes its rows to a file.
# Usage: label_lines <className> <output file>
label_lines() {
    local class_name="$1" output="$2"
    run_engine "SELECT * $LABELED_JOIN AND className = '$class_name';" > "$output"
    echo "  $class_name: $(wc -l < "$output" | tr -d ' ') labeled lines -> $output"
}

main() {
    [ -f "$RESULTS_DIR/runs.csv" ] || { echo "missing $RESULTS_DIR/runs.csv; run project3/run_benchmark.sh first" >&2; exit 1; }
    mkdir -p "$ANALYSIS_DIR" "$WORK_DIR"

    collect_benchmark_logs
    load_analysis_tables
    echo "joining logs with runs inside the engine:"
    label_lines HashJoinOperator "$ANALYSIS_DIR/joins.csv"
    label_lines Executor "$ANALYSIS_DIR/statements.csv"
    label_lines JoinOrderer "$ANALYSIS_DIR/orderer.csv"
    cat "$WORK_DIR"/logs/engine*.log > "$ANALYSIS_DIR/analysis.log"

    python3 "$PROJECT3/summarize.py" --analysis-dir "$ANALYSIS_DIR" --wallclock "$RESULTS_DIR/wallclock.csv"
}

main "$@"
