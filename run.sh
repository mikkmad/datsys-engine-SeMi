#!/bin/bash

rm -r data/
mvn -q compile exec:java -Dexec.args="-f ./src/test/resources/q.sql" > ours.csv
duckdb -csv -noheader < ./src/test/resources/q.sql > theirs.csv