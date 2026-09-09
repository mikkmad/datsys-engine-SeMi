# Implementation Plan: Fix CSV Resource Trailing Blank Lines

## Problem Statement
During the initial execution of `mvn -B verify`, 7 of the 9 integration tests in [`StorageEngineIT.java`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/StorageEngineIT.java) failed with the following exception:

```text
java.lang.IllegalArgumentException: .../trips.csv: line 9: expected 3 fields but found 1
    at datasys.semi.StorageEngine.parseError(StorageEngine.java:380)
    at datasys.semi.StorageEngine.parseCsvLine(StorageEngine.java:180)
    at datasys.semi.StorageEngine.copyFile(StorageEngine.java:98)
```

## Root Cause
Both test resource files, [`src/test/resources/trips.csv`](file:///workspaces/datsys-engine-SeMi/src/test/resources/trips.csv) and [`src/test/resources/trips_sorted.csv`](file:///workspaces/datsys-engine-SeMi/src/test/resources/trips_sorted.csv), contained accidental trailing blank lines (lines 9 and 10).

In [`StorageEngine.java:96-99`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/StorageEngine.java#L96-L99), `BufferedReader.readLine()` reads the file sequentially and splits each line by comma:
```java
String[] fields = line.split(",", -1);
if (fields.length != schema.size()) {
    throw parseError(fileName, lineNumber, "expected " + schema.size() + " fields but found " + fields.length);
}
```
For the blank line 9, `"".split(",", -1)` returned an array of length 1, triggering the mismatch error against the 3-column schema.

## Proposed Changes

### 1. [`src/test/resources/trips.csv`](file:///workspaces/datsys-engine-SeMi/src/test/resources/trips.csv)
- Reformat file to contain strictly the 8 golden records without trailing empty lines.

### 2. [`src/test/resources/trips_sorted.csv`](file:///workspaces/datsys-engine-SeMi/src/test/resources/trips_sorted.csv)
- Reformat file to contain strictly the 8 distance-sorted records without trailing empty lines.

## Verification Plan
1. **Maven Verification Pipeline**:
   - Execute `mvn -B verify` to compile test resources and run all Surefire unit tests and Failsafe integration tests.
   - Verify `0` failures and `0` errors across all 9 integration tests.
2. **Golden Query Demonstration**:
   - Execute `mvn compile exec:java` to verify the standalone application correctly loads and queries data without parsing errors.

