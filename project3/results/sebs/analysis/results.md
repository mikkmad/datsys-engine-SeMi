# Join-order benchmark results

Runtime is `durationMs` from the engine's SELECT summary line; warm-up repetition excluded.

| Config | Mode / FROM list | Join order | n | Median (ms) | Min | Q1 | Q3 | Max | Wall median (ms) |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| min | -j syntax, FROM customers, shipments, orders | customers+shipments+orders | 7 | 610 | 605 | 608 | 613.5 | 616 | 968 |
| max | -j syntax, FROM orders, shipments, customers | orders+shipments+customers | 7 | 1,289 | 1273 | 1,284 | 1,294.5 | 1309 | 1656 |
| opt_min_from | -j optimized, FROM customers, shipments, orders | customers+shipments+orders | 7 | 609 | 607 | 609 | 611.5 | 614 | 968 |
| opt_max_from | -j optimized, FROM orders, shipments, customers | customers+shipments+orders | 7 | 621 | 619 | 619.5 | 623.5 | 625 | 981 |

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
