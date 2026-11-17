#!/usr/bin/env bash
# Regenerates the DuckDB oracle output for the Exercise 10 join testbed.
#
# Usage: scripts/joins-duckdb-oracle.sh
#        DUCKDB=/path/to/duckdb scripts/joins-duckdb-oracle.sh
#
# Reads src/test/resources/joins/queries/queryN.sql and writes the sorted,
# header-less DuckDB result to src/test/resources/joins/expected/queryN.csv.
# JoinTestbedIT compares the engine's sorted output with these files, so CI
# needs no DuckDB.
#
# HEADER false is required: customers.csv is all STRING, so DuckDB's sniffer
# would otherwise read "Anna,Copenhagen" as a header and drop Anna's orders.
set -euo pipefail

DUCKDB="${DUCKDB:-duckdb}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JOINS="$ROOT/src/test/resources/joins"
mkdir -p "$JOINS/expected"

SETUP="
CREATE TABLE orders (o_orderkey BIGINT, o_custname VARCHAR, o_prodname VARCHAR, o_quantity BIGINT);
CREATE TABLE products (p_name VARCHAR, p_price DOUBLE);
CREATE TABLE customers (c_name VARCHAR, c_city VARCHAR);
CREATE TABLE cities (ct_city VARCHAR, ct_country VARCHAR, ct_population BIGINT);
COPY orders FROM '$JOINS/orders.csv' (HEADER false);
COPY products FROM '$JOINS/products.csv' (HEADER false);
COPY customers FROM '$JOINS/customers.csv' (HEADER false);
COPY cities FROM '$JOINS/cities.csv' (HEADER false);
"

for query in "$JOINS"/queries/query*.sql; do
    name="$(basename "$query" .sql)"
    "$DUCKDB" -csv -noheader -c "$SETUP $(cat "$query")" | LC_ALL=C sort > "$JOINS/expected/$name.csv"
    echo "wrote expected/$name.csv ($(wc -l < "$JOINS/expected/$name.csv" | tr -d ' ') rows)"
done
