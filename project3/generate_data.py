#!/usr/bin/env python3
"""Generates the Exercise 12 join-order benchmark data with a fixed seed.

Schema (one dimension table, two fact tables that both reference it):

    customers (c_name STRING, c_city STRING)                         -- dimension
    orders    (o_orderkey LONG, o_custname STRING, o_quantity LONG)  -- fact
    shipments (s_shipkey LONG, s_custname STRING, s_weight DOUBLE)   -- fact

Design decisions required by Exercise 12 §1:

* Cities are assigned round-robin (customer i lives in city i mod CITIES), so an
  equality filter on one city keeps exactly 1/CITIES of the customers. The
  selectivity is a stated number, not a measured one.
* Every customer has exactly PER_CUSTOMER orders and PER_CUSTOMER shipments.
* The foreign keys are shuffled (with the fixed seed) instead of written in
  customer order, so each partition's NDV stays close to the table's NDV.

The CSV files have no header row, matching the engine's COPY.
Only the Python standard library is used.

Usage:
    python3 project3/generate_data.py                      # default sizes
    python3 project3/generate_data.py --per-customer 200   # scale the fact tables
"""

import argparse
import csv
import random
from pathlib import Path

DEFAULT_SEED = 20261203
DEFAULT_CUSTOMERS = 1_000
DEFAULT_CITIES = 100
DEFAULT_PER_CUSTOMER = 100


def parse_arguments():
    """Parses the command line; every size has the Exercise 12 default."""
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED, help="random seed (default %(default)s)")
    parser.add_argument("--customers", type=int, default=DEFAULT_CUSTOMERS,
                        help="rows in customers (default %(default)s)")
    parser.add_argument("--cities", type=int, default=DEFAULT_CITIES,
                        help="distinct cities, assigned round-robin (default %(default)s)")
    parser.add_argument("--per-customer", type=int, default=DEFAULT_PER_CUSTOMER,
                        help="orders and shipments per customer (default %(default)s)")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "data",
                        help="output directory (default project3/data)")
    arguments = parser.parse_args()
    if arguments.customers % arguments.cities != 0:
        parser.error("--customers must be a multiple of --cities so every city has the same number of customers")
    return arguments


def customer_name(customer_number):
    """Returns the unique key of a customer, e.g. cust00042."""
    return f"cust{customer_number:05d}"


def city_name(city_number):
    """Returns the name of a city, e.g. city042."""
    return f"city{city_number:03d}"


def write_rows(path, rows):
    """Writes rows as a header-less CSV file and returns the row count."""
    with path.open("w", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        writer.writerows(rows)
    return len(rows)


def generate_customers(customer_count, city_count):
    """Customers in key order; customer i lives in city i mod city_count (round-robin)."""
    return [(customer_name(number), city_name(number % city_count)) for number in range(customer_count)]


def shuffled_foreign_keys(rng, customer_count, per_customer):
    """Every customer key exactly per_customer times, in random order."""
    keys = [customer_name(number) for number in range(customer_count) for _ in range(per_customer)]
    rng.shuffle(keys)
    return keys


def generate_orders(rng, customer_count, per_customer):
    """Orders with a unique key, a shuffled customer foreign key, and a quantity in 1..50."""
    keys = shuffled_foreign_keys(rng, customer_count, per_customer)
    return [(order_key, customer, rng.randint(1, 50)) for order_key, customer in enumerate(keys)]


def generate_shipments(rng, customer_count, per_customer):
    """Shipments with a unique key, a shuffled customer foreign key, and a weight in 0.1..100.0."""
    keys = shuffled_foreign_keys(rng, customer_count, per_customer)
    return [(ship_key, customer, round(rng.uniform(0.1, 100.0), 2)) for ship_key, customer in enumerate(keys)]


def main():
    """Generates the three CSV files and prints the expected join sizes."""
    arguments = parse_arguments()
    rng = random.Random(arguments.seed)
    arguments.out.mkdir(parents=True, exist_ok=True)

    customer_rows = write_rows(arguments.out / "customers.csv",
                               generate_customers(arguments.customers, arguments.cities))
    order_rows = write_rows(arguments.out / "orders.csv",
                            generate_orders(rng, arguments.customers, arguments.per_customer))
    shipment_rows = write_rows(arguments.out / "shipments.csv",
                               generate_shipments(rng, arguments.customers, arguments.per_customer))

    customers_per_city = arguments.customers // arguments.cities
    fact_join_rows = arguments.customers * arguments.per_customer ** 2
    min_first_join_rows = customers_per_city * arguments.per_customer
    result_rows = customers_per_city * arguments.per_customer ** 2
    print(f"seed={arguments.seed} out={arguments.out}")
    print(f"customers={customer_rows} orders={order_rows} shipments={shipment_rows}")
    print(f"filter selectivity=1/{arguments.cities} ({customers_per_city} customers per city)")
    print(f"expected: max first join={fact_join_rows} rows, min first join={min_first_join_rows} rows, "
          f"result={result_rows} rows")


if __name__ == "__main__":
    main()
