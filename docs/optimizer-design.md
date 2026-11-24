# Optimizer Design
## **Statistics**: 
We will add NDV (Number of Distinct Values) per partition, per column to the statistics, which will be collected during COPY like min/max.


## **Where they live and how they merge**: 
The table NDV is the sum of the partition NDVs. This is exact when partitions don't share values. Otherwise, it gives an upper bound. We accept that in exchange for simplicity. Because a partition never has more distinct values than rows, the sum is never larger than the table's row count; the helper (`NdvMerger.tableNdv`) still caps it at the row count as a safety check.

## **Roll forward**: 

**Rolling back** (downgrading), the NDV field will be treated as an unknown JSON property, and `Jackson` will throw `UnrecognizedPropertyException`, which the StorageEngine will log as an `IllegalStateException` error. By adding `@JsonIgnoreProperties(ignoreUnknown = true)` to the `Catalog`, `Partition`, `Column` and the `Statistics` class, we can ignore the missing property and continue to use the table, and avoid the `IllegalStateException` error. This is a retrofit upgrade to the code, which would make downgrading safe, but the NDV statistic would be lost. Partitions an older engine writes have no NDV; the next start of a current engine fills them in.

**Rolling forward** (upgrading), the NDV statistic is calculated on engine start if it is missing. Every partition without an NDV is read once, and the NDVs are committed as a new catalog version, so all tables have the statistic before the first query is planned. We do this at startup rather than on the first scan because no statement holds a snapshot yet, so the commit cannot make a concurrent COPY or DELETE fail, and because the first plan would otherwise still use the fallback. The cost is a slower first startup after the upgrade; later startups read nothing. If a partition cannot be read, the estimator uses its row count as an upper bound until the next startup. Since the CLI starts a new engine per command, a partition that failed is retried on the next command.

## **The estimator:** 

The estimator (`optimizer/CardinalityEstimator`) will use the following formulas for operators:
| Operator                        | Estimate                                                                                                                                                 | Assumption                                                             |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Scan                            | Sum of the rows of the partitions that survive pruning                                                                                                   | None: the only exact number                                            |
| Equality filter `col = x`       | $\frac{\lvert R \rvert}{\mathrm{ndv}(col)}$                                                                                                              | Uniformity: every value is equally frequent                            |
| Range filter `col < x`          | $\sum_p \lvert p \rvert \cdot \frac{x - \min_p}{\max_p - \min_p}$ [1]                                                                                    | Uniformity between min and max                                         |
| Range filter `col > x`          | $\sum_p \lvert p \rvert \cdot \frac{\max_p - x}{\max_p - \min_p}$ [1]                                                                                    | Uniformity between min and max                                         |
| Range filter, no usable min/max | $\frac{\lvert R \rvert}{3}$                                                                                                                              | None: the statistics cannot answer                                     |
| Equi-join `R.a = S.b`           | $\frac{\lvert R \rvert \cdot \lvert S \rvert}{\max(\mathrm{ndv}(R.a),\ \mathrm{ndv}(S.b))}$                                                              | Containment: the smaller key set's values all appear in the larger one |

- $\lvert R \rvert$ = cardinality of $R$ = number of rows of $R$ **after pruning**: the sum of the row counts of the surviving partitions.
- $\mathrm{ndv}(col)$ is the number of distinct values of column $col$, merged with the sum rule over the **same surviving partitions** as $\lvert R \rvert$.
- Selectivity: the fraction of rows a predicate keeps. Equality: $\frac{1}{\mathrm{ndv}(col)}$.
- **Range filters** interpolate per surviving partition ($p$), using that partition's own min/max ($\min_p$/$\max_p$), clamp the fraction to $[0, 1]$, and weight the result by its row count ($\lvert p \rvert$). `STRING` columns cannot be interpolated, and partitions without statistics have no min/max; both fall back to $\frac{1}{3}$.
  - $\frac{1}{3}$ is System R's "magic constant" when the statistics cannot answer [1].
- **Several filters**: we multiply their selectivities, which assumes the columns are independent [1].
- **Equi-join**: the formula is from Lecture 11 [2]. Its selectivity $\frac{1}{\max(\mathrm{ndv})}$ is System R's rule for `col1 = col2` [1]. If both inputs are empty, both NDVs are 0 and the estimate is 0.
- **Intermediate results**: later joins in a chain need the NDV of a column of an intermediate result. We approximate it by the NDV of the base table that contributed the column, over that table's surviving partitions. 
- Estimates are kept as `double` through a chain of joins and rounded only when logged, so rounding each step does not add up.

## **The heuristic**: 
We will use Greedy smallest-intermediate-first. With at most four tables, exhaustive enumeration is also cheap.

**Cross-product guardrail**: only tables connected by a join predicate are candidates, so a cross product is never picked, even when it looks cheaper. A query that needs one is rejected, as in week 10.

### Literature
[1] P. G. Selinger, M. M. Astrahan, D. D. Chamberlin, R. A. Lorie, and T. G. Price, "Access path selection in a relational database management system," in Proceedings of the 1979 ACM SIGMOD International Conference on Management of Data, Boston, MA, USA, May 1979, pp. 23–34, doi: 10.1145/582095.582099.

[2] Slides from Lecture 11, "The Optimizer: Cardinality Estimation, Join Ordering", How to Build Data Systems, Fall 2026.