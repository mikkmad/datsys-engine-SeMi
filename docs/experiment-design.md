# Experiment design

## Question & x-axis
We want to investigate the performance the engines statement execution across different sizes of data
- We want to investigate the performance of `COPY` statement
- We want to investigate the performance of `SELECT` statement

x-axis = size of table or csv file (in Megabytes)

## Metric & y-axis
We will measure the execution of the statements in `ms`.

- y-axis is execution time in `ms`

## Procedure
Data generation...

Values that vary: size of table / csv file, statement (copy or select)

We do/do not include the first cold run ...

We do 8 repetitions?

**System Specification**:
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
| | Heap Size | ... |


## Hypothesis
We assume that as the size of the data or table increases the execution time of statements will steadily increase.

- **Question and x axis:** define the dimension you sweep. Examples are `maxRowsPerPartition` from 2 to 1024, table size from 1K to 1M rows, sorted versus shuffled input, or predicate selectivity from 0% to 100%.
- **Metric and y axis:** define what you measure from your own log. This could be the fraction of partitions read from the `decision=` lines, or the median `durationMs` from the summary lines. State the plot before you see it: what is on the x axis, what is on the y axis, and what one curve represents. Use a log scale on x if the values span multiple orders of magnitude.
- **Procedure:** describe data generation, the values you vary, the number of repetitions, whether you include or exclude the first cold run, the machine, JVM version, and heap size.
- **Hypothesis:** state it before the first run, and quantify it where possible. "Sorted input halves the partitions read at every partition size" is informative. "Pruning improves" is less informative.