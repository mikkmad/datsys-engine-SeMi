# Optimizer Design
## **Statistics**: 
We will add NDV (number of distinct values per partition, per column) to the statistics, which will be collected during COPY like min/max.


## **Where they live and how they merge**: 
The NDVs will be summed per partition, and caps at the total row count. This is exact when partitions don't share values. Otherwise, it gives an upper bound. We accept that in exchange for simplicity.

## **Roll forward**: 

**Rolling back** (downgrading), the NDV field will be treated as an unknown JSON property, and `Jackson` will throw `UnrecognizedPropertyException`, which the StorageEngine will log as an `IllegalStateException` error. By adding `@JsonIgnoreProperties(ignoreUnknown = true)` to the `Catalog`, `Partition`, and the `Statistics` class, we can ignore the missing property and continue to use the table, and avoid the `IllegalStateException` error. This is a retrofit upgrade to the code, which would make downgrading safe, but the NDV statistic would be lost.

**Rolling forward** (upgrading), the NDV statistic should be calculated on the first scan if it is missing. That way we ensure that all tables will have the statistic after it have been used once.

## **The estimator:** 

The estimator will use the following formulars for operators:
| Operator                      | Estimate                                                | Assumption                                  |
| ----------------------------- | ------------------------------------------------------- | ------------------------------------------- |
| Scan                          | Sum of the rows of the partitions that survive pruning  | None: the only exact number                 |
| Equality filter `col = x`     | $\frac{\lvert R \rvert}{\mathrm{ndv}(col)}$             | Uniformity: every value is equally frequent |
| Range filter `col<x`, `col>c` | $\frac{\lvert R \rvert}{3}$ or interpolate from min/max | Uniformity                                  |

- $\mathrm{ndv}(col)$ is the number of distinct values of column $col$ of the whole table, approximated by the sum of partition NDVs (upper bound)
- $\lvert R \rvert$ = cardinality of $R$ = number of rows of the table $R$
- Selectivity: the fraction of rows a predicate keeps. Equality: $\frac{1}{\mathrm{ndv}(col)}$
- The $\frac{1}{3}$ is Selinger et al., 1979: System R's "magic constant" when the statistics cannot answer. Still in use: PostgreSQL's default for an inequality is $0.3333$

Source: Lecture 11

## **The heuristic**: 
We will use Greedy smallest-intermediate-first. With at most four tables, exhaustive enumeration is also cheap.