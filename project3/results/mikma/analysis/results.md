# Join-order benchmark results

Runtime is `durationMs` from the engine's SELECT summary line; warm-up repetition excluded.

| Config | Mode / FROM list | Join order | n | Median (ms) | Min | Q1 | Q3 | Max | Wall median (ms) |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| min | -j syntax, FROM customers, shipments, orders | customers+shipments+orders | 7 | 922 | 908 | 914.5 | 930 | 938 | 1641 |
| max | -j syntax, FROM orders, shipments, customers | orders+shipments+customers | 7 | 2,200 | 2164 | 2,188 | 2,219.5 | 2264 | 2988 |
| opt_min_from | -j optimized, FROM customers, shipments, orders | customers+shipments+orders | 7 | 924 | 905 | 915.5 | 928 | 933 | 1665 |
| opt_max_from | -j optimized, FROM orders, shipments, customers | customers+shipments+orders | 7 | 926 | 908 | 920 | 928.5 | 935 | 1651 |

## Intermediate result sizes (estimated vs. actual)

| Config | Join | Tables | Build rows | Probe rows | estRows | rowsOut | q-error |
|---|---:|---|---:|---:|---:|---:|---:|
| min | 1 | customers+shipments | 10 | 100000 | 100 | 1,000 | 10.00 |
| min | 2 | customers+shipments+orders | 1000 | 100000 | 1,000 | 100,000 | 100.00 |
| max | 1 | orders+shipments | 100000 | 100000 | 1,000,000 | 10,000,000 | 10.00 |
| max | 2 | orders+shipments+customers | 10000000 | 10 | 1,000 | 100,000 | 100.00 |
| opt_min_from | 1 | customers+shipments | 10 | 100000 | 100 | 1,000 | 10.00 |
| opt_min_from | 2 | customers+shipments+orders | 1000 | 100000 | 1,000 | 100,000 | 100.00 |
| opt_max_from | 1 | customers+shipments | 10 | 100000 | 100 | 1,000 | 10.00 |
| opt_max_from | 2 | customers+shipments+orders | 1000 | 100000 | 1,000 | 100,000 | 100.00 |
