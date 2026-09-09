# Data Systems Engine (`datasys-engine-SeMi`)

[![CI](https://github.com/mikkmad/datsys-engine-SeMi/actions/workflows/ci.yml/badge.svg)](https://github.com/mikkmad/datsys-engine-SeMi/actions/workflows/ci.yml)
![Java Version](https://img.shields.io/badge/Java-25-orange.svg)
![Maven](https://img.shields.io/badge/Maven-3.9+-blue.svg)

A relational database management system (DBMS) engine built from scratch in Java 25 as part of the **How to Build Data Systems (Fall 2026)** course by **Team SeMi**.

The project explores core database internals from first principles without relying on third-party database or storage libraries (such as SQLite, DuckDB, Parquet, or Arrow). It implements custom binary storage, partition management, metadata catalogs, zone-map partition pruning, structured queryable logging, and a SQL front end.

---

## Table of Contents

- [Overview](#overview)
- [Architecture & Features](#architecture--features)
  - [Custom Binary Storage Format (`SEMI`)](#custom-binary-storage-format-semi)
  - [Catalog & Schema Persistence](#catalog--schema-persistence)
  - [Zone Maps & Partition Pruning](#zone-maps--partition-pruning)
  - [CSV Ingestion](#csv-ingestion)
  - [Queryable Structured Logging](#queryable-structured-logging)
  - [SQL Front End & AST (Exercise 3)](#sql-front-end--ast-exercise-3)
- [Repository Structure](#repository-structure)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Build and Test Commands](#build-and-test-commands)
  - [Running the Demo](#running-the-demo)
- [Design Specifications](#design-specifications)
  - [Binary Partition Layout](#binary-partition-layout)
  - [Catalog Schema Example](#catalog-schema-example)
  - [Log Schema](#log-schema)
- [Semester Roadmap](#semester-roadmap)
- [Engineering Practices](#engineering-practices)

---

## Overview

Modern data systems process vast quantities of data by combining carefully designed on-disk storage layouts, catalog-backed metadata management, partition-level pruning, and efficient query pipelines.

This repository implements these concepts incrementally:
1. **Exercise 1 (v0.1)**: Engineering toolchain, GitHub Actions CI, branch protection, and structured CSV logging with SLF4J and Log4j2.
2. **Exercise 2 (v0.2)**: Storage core with a custom binary format (`SEMI`), JSON-based metadata catalogs, positional CSV loader, filtered scans, and min/max partition pruning.
3. **Exercise 3 (v0.3)**: SQL parser front end using ANTLR4, creating a typed, validated Abstract Syntax Tree (AST), semantic catalog binding, and SQL pretty-printing.
4. **Subsequent Exercises**: Volcano-style iterator execution pipeline, self-analyzing log execution, and support for complex SQL statements (`DELETE`, `JOIN`).

---

## Architecture & Features

### Custom Binary Storage Format (`SEMI`)

Persistent table data is stored in row-oriented partition files (`partition-<n>.dat`) under `<dataDirectory>/data/<tableName>/`:
- **Fixed Header**: 12-byte header with magic bytes `SEMI` (`0x53 0x45 0x4D 0x49`), a 2-byte format version (`uint16`), a 4-byte row count (`uint32`), and a 2-byte column count (`uint16`).
- **Big-Endian Encoding**:
  - `LONG`: 8-byte two's-complement signed integer.
  - `DOUBLE`: 8-byte IEEE 754 double precision float (`Double.doubleToLongBits`).
  - `STRING`: 4-byte unsigned byte length prefix followed by raw UTF-8 payload bytes.
- **Configurable Partition Size**: Data is partitioned into chunks of at most `maxRowsPerPartition` (default: 1024; configurable for testing).
- **Atomic Writes**: Partitions are written to temporary staging files (`.tmp-<uuid>`) and moved atomically via `Files.move` with `ATOMIC_MOVE` to prevent corrupted states on failure.

### Catalog & Schema Persistence

Table metadata is maintained persistently in JSON format under `<dataDirectory>/catalogs/<tableName>.json`:
- **Schema**: Column names and types (`STRING`, `LONG`, `DOUBLE`).
- **Partition Metadata**: Relative file paths (`data/<tableName>/partition-<n>.dat`), row counts, and per-partition min/max summaries for all columns.
- **Durability**: Completely decoupled from data files; restarting `StorageEngine` on an existing directory immediately restores table schemas and partition statistics without rescanning raw data files.

### Zone Maps & Partition Pruning

To avoid expensive disk I/O, `StorageEngine.select(...)` evaluates query predicates against the min/max summaries in the catalog before opening partition files:
- **Supported Comparisons**: `EQUALS` (`=`), `LESS_THAN` (`<`), and `GREATER_THAN` (`>`).
- **Data Types**: Numeric comparisons for `LONG` and `DOUBLE`; lexicographic byte comparison for `STRING`.
- **Observability**: Every scan tracks pruning statistics via `ScanStats(partitionsTotal, partitionsRead, partitionsPruned)`, and emits explicit log events for every prune or read decision.

### CSV Ingestion

The `StorageEngine.copyFile(tableName, csvFilePath)` API loads data from headerless, comma-separated CSV files:
- Positional mapping to schema columns.
- Validates field counts and data type formatting with descriptive line-number error reporting.
- Automatically creates partition files, computes min/max statistics on the fly, and registers partitions in the catalog.

### Queryable Structured Logging

The engine emits CSV logs conforming to an exact 7-field schema:
```csv
timestamp,sessionId,statementNumber,threadId,logLevel,className,logMessage
```
- Configured via Log4j2 in `src/main/resources/log4j2.xml`.
- Context tracking via SLF4J MDC (`sessionId` tracks the engine run; `statementNumber` tracks the executed statement).
- Output is written to `logs/engine.log` (with rolling backup files `logs/engine-%i.log`).
- Formatted without commas in messages (using `key=value` pairs) so the engine can ingest and query its own execution logs in later exercises.

### SQL Front End & AST (Exercise 3)

The SQL front end parses declarative SQL text into an executable representation:
- **ANTLR4 Grammar**: Supports case-insensitive SQL statements:
  ```sql
  CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
  COPY trips FROM 'trips.csv';
  SELECT * FROM trips WHERE distance > 100;
  SELECT * FROM trips;
  ```
- **Bound AST**: AST nodes (`CreateTableStatement`, `CopyStatement`, `SelectStatement`, `Predicate`) reuse the storage engine's `ColumnSpec`, `ColumnType`, and `Comparison` types.
- **Binder**: Validates AST nodes against the catalog schema before execution.
- **Pretty-Printer**: Re-renders AST structures into normalized SQL satisfying the round-trip identity property `parse(print(ast)) == ast`.

---

## Repository Structure

```
datasys-engine-SeMi/
├── .github/
│   ├── workflows/
│   │   └── ci.yml                     # GitHub Actions CI workflow (mvn verify)
│   └── pull_request_template.md       # PR template with AI attribution section
├── docs/
│   └── storage-design.md              # Storage engine architecture & design justification
├── exercise_descriptions/
│   ├── Exercise1.md                   # Exercise 1: Toolchain, logging, baseline
│   ├── Exercise2.md                   # Exercise 2: Binary format, catalog, scans
│   └── Exercise3.md                   # Exercise 3: SQL parser, bound AST, binder
├── logs/
│   └── engine.log                     # CSV runtime log output
├── semi-demo/                         # Demo database directory generated by Engine main
│   ├── catalogs/                      # Per-table JSON catalogs
│   │   └── trips.json
│   ├── data/                          # Binary partition files
│   │   └── trips/
│   │       ├── partition-0.dat
│   │       ├── partition-1.dat
│   │       ├── partition-2.dat
│   │       └── partition-3.dat
│   └── trips.csv                      # Sample input dataset
├── src/
│   ├── main/
│   │   ├── java/datasys/semi/
│   │   │   ├── ColumnSpec.java        # Record holding column name and ColumnType
│   │   │   ├── ColumnType.java        # Enum (STRING, LONG, DOUBLE)
│   │   │   ├── Comparison.java        # Enum (EQUALS, LESS_THAN, GREATER_THAN)
│   │   │   ├── Engine.java            # Main application runner & demo entry point
│   │   │   ├── ScanStats.java         # Record tracking partitions total/read/pruned
│   │   │   └── StorageEngine.java     # Storage engine, binary I/O, catalog manager
│   │   └── resources/
│   │       ├── log4j2.xml             # Log4j2 configuration for CSV output
│   │       └── trips.csv              # Golden dataset for tests and demos
│   └── test/
│       └── java/datasys/semi/
│           ├── EngineTest.java        # Unit test verifying baseline functionality
│           ├── StorageEngineUnitTest.java # Unit tests for encoders, min/max, pruning
│           └── StorageEngineIT.java   # End-to-end integration tests (JUnit + @TempDir)
├── pom.xml                            # Maven project definition and dependencies
└── README.md                          # Repository documentation
```

---

## Getting Started

### Prerequisites

- **Java Development Kit (JDK)**: Version 25 or newer (`java -version`)
- **Apache Maven**: Version 3.9 or newer (`mvn -version`)
- **Git**: Version 2.40 or newer

### Build and Test Commands

Maven commands used across the development lifecycle:

| Command | Description |
|---|---|
| `mvn clean compile` | Cleans target directory and compiles Java sources |
| `mvn test` | Runs unit tests (`*Test.java`) via Maven Surefire |
| `mvn -B verify` | Runs all tests, including integration tests (`*IT.java`) via Maven Failsafe |
| `mvn clean package` | Compiles, runs all tests, and builds executable JAR `target/engine-0.1.0.jar` |
| `mvn compile exec:java` | Compiles and executes the engine demo entrypoint (`datasys.semi.Engine`) |

### Running the Demo

Execute the storage engine demonstration:

```bash
mvn compile exec:java
```

The demo:
1. Initializes a fresh database directory under `semi-demo/`.
2. Creates the `trips` table with schema `(city STRING, distance LONG, price DOUBLE)`.
3. Ingests `trips.csv` partitioned into chunks of 2 rows each.
4. Executes three filtered queries:
   - `distance GREATER_THAN 100` (matches 4 rows: Aarhus, Copenhagen, Aalborg, Esbjerg)
   - `city EQUALS Copenhagen` (matches 3 rows: distances 12, 140, 88)
   - `price LESS_THAN 50.0` (matches 2 rows: Copenhagen 23.5, Roskilde 45.0)
5. Prints the matching records and records detailed scan and pruning logs in `logs/engine.log`.

---

## Design Specifications

For complete architecture details and rationale, see [docs/storage-design.md](docs/storage-design.md).

### Binary Partition Layout

Each `partition-<n>.dat` file starts with a fixed 12-byte header followed by sequential rows:

```
+-------------------------------------------------------------+
| Partition Header (12 bytes)                                 |
| - Magic Bytes:     4 bytes ASCII ('S', 'E', 'M', 'I')       |
| - Format Version:  2 bytes uint16 big-endian (value: 1)     |
| - Row Count:       4 bytes uint32 big-endian                |
| - Column Count:    2 bytes uint16 big-endian                |
+-------------------------------------------------------------+
| Row 0                                                       |
| - Column 0 (e.g. STRING: 4-byte len + UTF-8 payload)        |
| - Column 1 (e.g. LONG: 8-byte two's-complement)             |
| - Column 2 (e.g. DOUBLE: 8-byte IEEE 754)                   |
+-------------------------------------------------------------+
| Row 1 ...                                                   |
+-------------------------------------------------------------+
```

### Catalog Schema Example

Catalog files (`catalogs/<table_name>.json`) contain table schemas and partition statistics:

```json
{
  "table" : "trips",
  "schema" : [
    { "column_name" : "city", "column_type" : "STRING" },
    { "column_name" : "distance", "column_type" : "LONG" },
    { "column_name" : "price", "column_type" : "DOUBLE" }
  ],
  "partitions" : [
    {
      "path" : "data/trips/partition-0.dat",
      "rowCount" : 2,
      "statistics" : {
        "city" : { "min" : "Aarhus", "max" : "Copenhagen" },
        "distance" : { "min" : "12", "max" : "187" },
        "price" : { "min" : "23.5", "max" : "301.0" }
      }
    }
  ]
}
```

### Log Schema

Every line in `logs/engine.log` adheres to:

```
YYYY-MM-DD HH:MM:SS.SSS,sessionId,statementNumber,threadId,logLevel,className,logMessage
```

Example lines showing partition creation, min/max logging, and pruning decisions:
```csv
2026-09-08 18:07:13.722,73b83911-d09a-4904-a758-d13da061152c,0,3,DEBUG,StorageEngine,table=trips columns=3
2026-09-08 18:07:13.739,73b83911-d09a-4904-a758-d13da061152c,0,3,DEBUG,StorageEngine,table=trips partition=0 column=distance min=1 max=10
2026-09-08 18:07:13.957,73b83911-d09a-4904-a758-d13da061152c,0,3,DEBUG,StorageEngine,table=trips column=distance comparison=GREATER_THAN const=25 partition=0 min=1 max=10 decision=PRUNED
2026-09-08 18:07:13.958,73b83911-d09a-4904-a758-d13da061152c,0,3,DEBUG,StorageEngine,table=trips column=distance comparison=GREATER_THAN const=25 partition=1 min=20 max=30 decision=READ
2026-09-08 18:07:13.959,73b83911-d09a-4904-a758-d13da061152c,0,3,DEBUG,StorageEngine,table=trips column=distance comparison=GREATER_THAN const=25 partitionsRead=1 partitionsPruned=1 rowsOut=1 durationMs=1
```

---

## Semester Roadmap

| Milestone | Tag | Description | Status |
|---|---|---|---|
| **Exercise 1** | `v0.1` | Engineering toolchain, CI pipeline, Log4j2 CSV logging, baseline PR workflow | Completed |
| **Exercise 2** | `v0.2` | Custom binary storage format (`SEMI`), JSON catalogs, partition pruning, golden query demo | Completed |
| **Exercise 3** | `v0.3` | ANTLR4 SQL grammar, AST builder, catalog binder, SQL pretty-printer | Next up |
| **Part 2** | `v0.4` | Volcano iterator model (Scan/Filter/Project), SQL script execution, log self-analysis | Planned |
| **Part 3** | `v0.5` | Query optimizer, hash joins, updates/deletions | Planned |

---

## Engineering Practices

- **Branch Protection**: Direct pushes to `main` are blocked. All changes must go through a pull request requiring at least one approving peer review and passing CI checks.
- **Continuous Integration**: GitHub Actions runs `mvn -B verify` across pull requests and merges to `main`.
- **Separation of Tests**:
  - **Unit Tests (`*Test.java`)**: Focus on isolated logic (value encoders, min/max calculations, pruning decisions, CSV line parsers).
  - **Integration Tests (`*IT.java`)**: Exercise the engine end-to-end using JUnit 6 `@TempDir` to guarantee clean state and test persistence across restarts.
- **Traceability**: All pull requests follow `.github/pull_request_template.md`, linking GitHub issues and attributing AI-assisted tooling.

