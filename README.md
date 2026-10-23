# Data Systems Engine (`datasys-engine-SeMi`)

[![CI](https://github.com/mikkmad/datsys-engine-SeMi/actions/workflows/ci.yml/badge.svg)](https://github.com/mikkmad/datsys-engine-SeMi/actions/workflows/ci.yml)
![Java Version](https://img.shields.io/badge/Java-25-orange.svg)
![Maven](https://img.shields.io/badge/Maven-3.9+-blue.svg)

## Overview

`datasys-engine-SeMi` is an analytical relational database engine built from scratch in **Java 25** by **Team SeMi** for the ITU course *How to Build Data Systems (Fall 2026)*.

Built from first principles without external storage or SQL libraries, the engine implements:
- A custom binary partition format (`SEMI`) and persistent JSON catalogs
- Zone-map (`min`/`max`) partition pruning
- An ANTLR4 SQL parser, AST binder, and pretty-printer
- A Volcano-style operator pipeline (`Scan`, `Filter`, `Project`)
- Self-queryable 7-column CSV execution logs

---

## Getting Started

### Prerequisites

- **JDK 25+** (`java -version`)
- **Apache Maven 3.9+** (`mvn -version`)
- **Python 3.10+** (`python3 --version`, for synthetic benchmark data generation)

### Build & Test

| Command | Description |
|---|---|
| `mvn clean compile` | Compile Java and ANTLR4 sources |
| `mvn test` | Run unit tests (`*Test.java`) via Surefire |
| `mvn -B verify` | Run unit and integration tests (`*IT.java`) via Failsafe |
| `mvn clean package` | Build the shaded runnable JAR at `target/engine.jar` |

### Running the Engine

Package the JAR first (`mvn package`), then use the `./engine` launcher script:

```bash
# Print team name and usage
./engine

# Execute inline SQL statement(s)
./engine -c "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE); COPY trips FROM 'src/main/resources/trips.csv';"
./engine -c "SELECT * FROM trips WHERE distance > 100;"

# Execute a SQL script file (CSV rows written to stdout, logs/errors to stderr)
./engine -f script.sql > results.csv
```

### Synthetic Data & Performance Experiments

1. **Activate the Python environment** (auto-initialized in the VS Code DevContainer, or created locally via `./setup-python-env.sh`):
   ```bash
   source setup-python-env.sh
   ```
2. **Generate benchmark CSV datasets** (`generated_data/data_1mb.csv`, `data_10mb.csv`, `data_100mb.csv`, `data_1000mb.csv`):
   ```bash
   python3 data_generator.py
   ```
3. **Run and summarize experiments** ([`docs/experiment-design.md`](docs/experiment-design.md)):
   ```bash
   HEAP=4g ./run-experiments.sh
   ./summarize-experiments.sh
   ```

---

## Semester Roadmap

| Milestone | Tag | Description | Status |
|---|---|---|---|
| **Exercise 1** | `v0.1` | Toolchain, GitHub Actions CI, Log4j2 CSV logging, PR workflow | Completed |
| **Exercise 2** | `v0.2` | `SEMI` binary storage format, JSON catalogs, min/max partition pruning | Completed |
| **Exercise 3** | `v0.3` | ANTLR4 SQL grammar, typed AST, catalog binder, SQL pretty-printer | Completed |
| **Exercise 4** | `v0.4` | Volcano pipeline (`Scan`/`Filter`/`Project`), planner pruning, CLI front door | Completed |
| **Exercise 5** | `v0.5` | Shaded JAR & `./engine` launcher, log self-analysis, streaming `COPY`, experiment design | Completed |
| **Exercise 6** | `v0.6` | Reproducible synthetic data generator, experiment harness & log summarization (Part 1 release) | Completed |