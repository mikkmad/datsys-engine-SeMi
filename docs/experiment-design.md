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
The values of the x axis varies between the following values [1mb, 10mb, 100mb and 1000mb].

## Metric & y-axis
We will measure the execution of one statements in `ms`.
For each measured statement, we use the executor’s statement-level summary log line. For `COPY`, the exact message format is 
```bash
statement=COPY table=<table> file=<file> durationMs=<milliseconds>
```
For `SELECT`, it is 
```bash
statement=SELECT table=<table> rowsOut=<row_count> durationMs=<milliseconds>
```
We extract the integer value following durationMs= and calculate the average over the eight warm-run repetitions.

**y-axis:** Average `durationMs` reported by the executor in milliseconds.

**Plot described:**
- x-axis: data size in MB using a logarithmic scale
- y-axis: average `durationMs` using a logarithmic scale
- Each curve is one statement (`COPY` or `SELECT`) on one machine.

## Procedure
### Data generation 
- python script to generate CSV files with fake data using the [Faker](https://faker.readthedocs.io/en/master/) library.
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
| | Heap Size | 4 GB |
| **Sebastian** | CPU | Apple M1 |
| | RAM       | 8 GB |
| | OS        | Sequoia 15.6.1 |
| | JVM Version | 25.0.4.1 |
| | Heap Size | 2 GB |


## Hypothesis
We assume there will be a linear scaling (H1), fixed overhead at small sizes (H2) and that `COPY` is slower than `SELECT` (H3).

**H1** Both `COPY` and `SELECT * FROM t` touch every row exactly once, so we expect execution time to grow linearly with data size (O(n)). Meaning a 10x increase in size should give roughly a 10x increase in average `durationMs`.
On a log-log plot, we expect both statements to be approximately straight lines with a slope near 1, indicating linear scaling with the amount of data.

**H2** At 1 MB we expect fixed costs (statement parsing, planning, opening the file, allocating the table) to make up a significant share of the runtime. We therefore expect the step from 1 MB to 10 MB to be clearly less than 10x.

**H3** `COPY` must read raw text, parse CSV, convert each field to its column type and write partitions, while `SELECT` reads already-typed data. We expect `COPY` to be at least 2x slower than `SELECT` at every size.
