# Experiment design
We want to investigate the performance of the engines statement execution across different sizes of data
- We want to investigate the performance of `COPY` statement
- We want to investigate the performance of `SELECT` statement

## Question & x-axis
**Question:** How does the execution time of the engine's `COPY` and `SELECT` statements scale with the size of the data?

**Dimensions**
- `COPY`: loading a CSV file into a table.
- `SELECT`: a full scan of a table (`SELECT * FROM t`) with no predicate, so the whole table is read every time.

**x-axis:** size of table or csv file (in Megabytes).

## Metric & y-axis
We will measure the execution of one statements in `ms`.

**y-axis:** is average `durationMs` time in `ms`.

**Plot described:**
- x-axis: data size in MB
- y-axis: average `durationMs`
- Each curve is one statement (`COPY` or `SELECT`) on one machine.

## Procedure
### Data generation 
- python script to generate CSV files with fake data using a library.
- Fixed schema, that is a fixed column size.

### Values that vary
Size of data, statement (copy or select)

### Method
We do 8 measured repetitions pr. (statement, size).
We do not include the first cold run.
We report the average execution time based on the 8 runs.

### System Specification
| User | Component | Specification |
|------|-----------|----------------|
| **Mikkel** | CPU       | AMD Ryzen 7, 3700X |
| | RAM       | 16GB DDR4 |
| | OS        | Fedora 44 (BlueFin) |
| | JVM Version | 25.0.4+7-LTS temurin |
| | Heap Size | ... |
| **Sebastian** | CPU | Apple M1 |
| | RAM       | 8 GB |
| | OS        | Sequoia 15.6.1 |
| | JVM Version | 25.0.4.1 |
| | Heap Size | 2 GB |


## Hypothesis
We assume that as the size of the data or table increases, the execution time of statements will steadily increase.

We also assume `COPY` will be slower than `SELECT` at every size, since `COPY` must parse CSV, convert types and write partitions, while `SELECT` only reads.