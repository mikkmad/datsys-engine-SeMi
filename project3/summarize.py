#!/usr/bin/env python3
"""Summarizes the engine's labeled benchmark log lines for the Project 3 report.

Reads the files analyze_benchmark.sh produced with the engine's own join of
logs and runs (each row: the 7 log columns followed by r_session, r_config,
r_repetition) and writes, into the same analysis directory:

    runtime_summary.csv   durationMs per configuration: n, median, min, q1, q3, max (+ wall-clock median)
    runtime.svg           chart of end-to-end runtime per configuration, every run plotted
    intermediate_sizes.csv estRows vs. rowsOut per join and configuration, with the q-error
    results.md            both tables plus the chosen join orders, ready to paste into the report

Repetition 0 is the warm-up and is excluded everywhere. Only the Python
standard library is used.

Usage: python3 project3/summarize.py [--analysis-dir DIR] [--wallclock FILE]
"""

import argparse
import csv
import statistics
from collections import defaultdict
from pathlib import Path

CONFIG_ORDER = ["min", "max", "opt_min_from", "opt_max_from"]
CONFIG_DESCRIPTIONS = {
    "min": "-j syntax, FROM customers, shipments, orders",
    "max": "-j syntax, FROM orders, shipments, customers",
    "opt_min_from": "-j optimized, FROM customers, shipments, orders",
    "opt_max_from": "-j optimized, FROM orders, shipments, customers",
}
WARM_UP_REPETITION = 0


def parse_arguments():
    """Parses the command line; defaults match analyze_benchmark.sh."""
    project3 = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--machine", help="results folder under project3/results (e.g. mikma, sebs)")
    parser.add_argument("--analysis-dir", type=Path)
    parser.add_argument("--wallclock", type=Path)
    arguments = parser.parse_args()
    if arguments.analysis_dir is None or arguments.wallclock is None:
        if arguments.machine is None:
            parser.error("give --machine, or both --analysis-dir and --wallclock")
        results_dir = project3 / "results" / arguments.machine
        arguments.analysis_dir = arguments.analysis_dir or results_dir / "analysis"
        arguments.wallclock = arguments.wallclock or results_dir / "wallclock.csv"
    return arguments


def parse_message(message):
    """Splits a 'key=value key=value' log message into a dict."""
    return dict(part.split("=", 1) for part in message.split() if "=" in part)


def read_labeled_lines(path):
    """Reads engine output rows (log columns + run labels) from measured repetitions only."""
    lines = []
    with path.open(newline="") as file:
        for row in csv.reader(file):
            if len(row) != 10:
                continue
            repetition = int(row[9])
            if repetition == WARM_UP_REPETITION:
                continue
            lines.append({"session": row[1], "message": parse_message(row[6]),
                          "config": row[8], "repetition": repetition})
    return lines


def ordered_configs(configs):
    """Known configurations in report order, followed by any unknown ones."""
    return [c for c in CONFIG_ORDER if c in configs] + sorted(set(configs) - set(CONFIG_ORDER))


def quartiles(values):
    """Returns (q1, q3) with the inclusive method; equals the median for tiny samples."""
    if len(values) < 2:
        return values[0], values[0]
    q1, _, q3 = statistics.quantiles(values, n=4, method="inclusive")
    return q1, q3


# --- Runtimes ---

def durations_by_config(statement_lines):
    """durationMs per session (max over the per-table SELECT summary lines), grouped by configuration."""
    per_session = {}
    for line in statement_lines:
        message = line["message"]
        if message.get("statement") != "SELECT" or "durationMs" not in message:
            continue
        key = (line["config"], line["session"])
        per_session[key] = max(per_session.get(key, 0), int(message["durationMs"]))
    durations = defaultdict(list)
    for (config, _), duration in per_session.items():
        durations[config].append(duration)
    return durations


def wall_medians(wallclock_path):
    """Median wall-clock ms per configuration from wallclock.csv (measured repetitions only)."""
    if not wallclock_path.exists():
        return {}
    walls = defaultdict(list)
    with wallclock_path.open(newline="") as file:
        for row in csv.DictReader(file):
            if int(row["repetition"]) != WARM_UP_REPETITION:
                walls[row["config"]].append(int(row["wall_ms"]))
    return {config: statistics.median(values) for config, values in walls.items()}


def summarize_runtimes(durations, walls):
    """One summary row per configuration."""
    rows = []
    for config in ordered_configs(durations):
        values = sorted(durations[config])
        q1, q3 = quartiles(values)
        rows.append({"config": config, "n": len(values), "median_ms": statistics.median(values),
                     "min_ms": values[0], "q1_ms": q1, "q3_ms": q3, "max_ms": values[-1],
                     "wall_median_ms": walls.get(config, "")})
    return rows


# --- Intermediate sizes ---

def summarize_joins(join_lines):
    """estRows and median rowsOut per (configuration, join number)."""
    grouped = defaultdict(list)
    for line in join_lines:
        message = line["message"]
        grouped[(line["config"], int(message["join"]))].append(message)
    rows = []
    for config in ordered_configs({config for config, _ in grouped}):
        for join_number in sorted(number for c, number in grouped if c == config):
            messages = grouped[(config, join_number)]
            estimated = statistics.median(int(m["estRows"]) for m in messages)
            actual = statistics.median(int(m["rowsOut"]) for m in messages)
            rows.append({"config": config, "join": join_number, "tables": messages[0]["tables"],
                         "build_rows": messages[0]["rowsBuild"], "probe_rows": messages[0]["rowsProbe"],
                         "est_rows": estimated, "actual_rows": actual, "q_error": q_error(estimated, actual),
                         "stable": len({m["rowsOut"] for m in messages}) == 1})
    return rows


def q_error(estimated, actual):
    """max(est/act, act/est), with both clamped to at least 1 row."""
    estimated, actual = max(estimated, 1), max(actual, 1)
    return max(estimated / actual, actual / estimated)


def chosen_orders(join_rows):
    """The final join order per configuration: the tables of its highest-numbered join."""
    orders = {}
    for row in join_rows:
        orders[row["config"]] = row["tables"]
    return orders


# --- Output ---

def write_csv(path, rows):
    """Writes a list of dicts as CSV with a header."""
    with path.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def format_number(value):
    """Integers without decimals, other numbers with one."""
    return f"{value:,.0f}" if float(value).is_integer() else f"{value:,.1f}"


def write_markdown(path, runtime_rows, join_rows):
    """Writes results.md with the runtime table, the size table, and the chosen orders."""
    orders = chosen_orders(join_rows)
    lines = ["# Join-order benchmark results", "",
             "Runtime is `durationMs` from the engine's SELECT summary line; warm-up repetition excluded.", "",
             "| Config | Mode / FROM list | Join order | n | Median (ms) | Min | Q1 | Q3 | Max | Wall median (ms) |",
             "|---|---|---|---:|---:|---:|---:|---:|---:|---:|"]
    for row in runtime_rows:
        lines.append(f"| {row['config']} | {CONFIG_DESCRIPTIONS.get(row['config'], '')} | {orders.get(row['config'], '')} "
                     f"| {row['n']} | {format_number(row['median_ms'])} | {row['min_ms']} | {format_number(row['q1_ms'])} "
                     f"| {format_number(row['q3_ms'])} | {row['max_ms']} | {row['wall_median_ms']} |")
    lines += ["", "## Intermediate result sizes (estimated vs. actual)", "",
              "| Config | Join | Tables | Build rows | Probe rows | estRows | rowsOut | q-error |",
              "|---|---:|---|---:|---:|---:|---:|---:|"]
    for row in join_rows:
        unstable = "" if row["stable"] else " (varies)"
        lines.append(f"| {row['config']} | {row['join']} | {row['tables']} | {row['build_rows']} | {row['probe_rows']} "
                     f"| {format_number(row['est_rows'])} | {format_number(row['actual_rows'])}{unstable} "
                     f"| {row['q_error']:.2f} |")
    path.write_text("\n".join(lines) + "\n")


def nice_axis_max(value):
    """Rounds a positive maximum up to 1, 2, or 5 times a power of ten."""
    magnitude = 10 ** (len(str(int(max(value, 1)))) - 1)
    for step in (1, 2, 5, 10):
        if value <= step * magnitude:
            return step * magnitude
    return 10 * magnitude


def write_runtime_svg(path, durations, runtime_rows):
    """Draws every measured run as a dot, the median as a bar, and the min-max range as a whisker."""
    width, height, left, right, top, bottom = 760, 440, 80, 20, 40, 90
    plot_width, plot_height = width - left - right, height - top - bottom
    axis_max = nice_axis_max(max(row["max_ms"] for row in runtime_rows) * 1.05)
    to_y = lambda ms: top + plot_height - ms / axis_max * plot_height
    slot = plot_width / len(runtime_rows)
    colors = {"min": "#2a9d8f", "max": "#e76f51", "opt_min_from": "#457b9d", "opt_max_from": "#8d6cab"}
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
             f'font-family="sans-serif" font-size="12">',
             f'<rect width="{width}" height="{height}" fill="white"/>',
             f'<text x="{width / 2}" y="22" text-anchor="middle" font-size="15">'
             f'Join-order benchmark: end-to-end runtime per configuration</text>']
    for tick in range(6):
        ms = axis_max * tick / 5
        y = to_y(ms)
        parts.append(f'<line x1="{left}" x2="{width - right}" y1="{y:.1f}" y2="{y:.1f}" stroke="#ddd"/>')
        parts.append(f'<text x="{left - 8}" y="{y + 4:.1f}" text-anchor="end">{ms:,.0f}</text>')
    parts.append(f'<text transform="translate(22 {top + plot_height / 2}) rotate(-90)" text-anchor="middle">'
                 f'Runtime, durationMs (ms)</text>')
    for index, row in enumerate(runtime_rows):
        config, center = row["config"], left + slot * (index + 0.5)
        color = colors.get(config, "#555")
        parts.append(f'<line x1="{center}" x2="{center}" y1="{to_y(row["min_ms"]):.1f}" '
                     f'y2="{to_y(row["max_ms"]):.1f}" stroke="{color}" stroke-width="1.5"/>')
        parts.append(f'<line x1="{center - 28}" x2="{center + 28}" y1="{to_y(row["median_ms"]):.1f}" '
                     f'y2="{to_y(row["median_ms"]):.1f}" stroke="{color}" stroke-width="4"/>')
        for run_index, ms in enumerate(sorted(durations[config])):
            jitter = (run_index % 5 - 2) * 5
            parts.append(f'<circle cx="{center + jitter + 40}" cy="{to_y(ms):.1f}" r="3.5" fill="{color}" '
                         f'fill-opacity="0.7"/>')
        parts.append(f'<text x="{center}" y="{to_y(row["median_ms"]) - 8:.1f}" text-anchor="middle">'
                     f'{row["median_ms"]:,.0f} ms</text>')
        parts.append(f'<text x="{center}" y="{top + plot_height + 20}" text-anchor="middle" font-weight="bold">'
                     f'{config}</text>')
        parts.append(f'<text x="{center}" y="{top + plot_height + 36}" text-anchor="middle" font-size="10">'
                     f'{CONFIG_DESCRIPTIONS.get(config, "").split(", FROM ")[0]}</text>')
    parts.append(f'<line x1="{left}" x2="{width - right}" y1="{top + plot_height}" y2="{top + plot_height}" '
                 f'stroke="#333"/>')
    parts.append(f'<text x="{width / 2}" y="{height - 18}" text-anchor="middle">Configuration '
                 f'(bar = median, whisker = min-max, dots = individual runs; n = {runtime_rows[0]["n"]} each)</text>')
    parts.append("</svg>")
    path.write_text("\n".join(parts) + "\n")


def main():
    """Reads the engine's labeled lines and writes the summary tables and the chart."""
    arguments = parse_arguments()
    analysis_dir = arguments.analysis_dir
    durations = durations_by_config(read_labeled_lines(analysis_dir / "statements.csv"))
    join_rows = summarize_joins(read_labeled_lines(analysis_dir / "joins.csv"))
    if not durations or not join_rows:
        raise SystemExit(f"no measured runs found in {analysis_dir}; did analyze_benchmark.sh run?")
    runtime_rows = summarize_runtimes(durations, wall_medians(arguments.wallclock))

    write_csv(analysis_dir / "runtime_summary.csv", runtime_rows)
    write_csv(analysis_dir / "intermediate_sizes.csv", join_rows)
    write_markdown(analysis_dir / "results.md", runtime_rows, join_rows)
    write_runtime_svg(analysis_dir / "runtime.svg", durations, runtime_rows)
    print((analysis_dir / "results.md").read_text())
    print(f"wrote runtime_summary.csv, intermediate_sizes.csv, results.md, runtime.svg to {analysis_dir}")


if __name__ == "__main__":
    main()
