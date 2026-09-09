# Walkthrough: Fix CSV Resource Trailing Blank Lines

## Overview
This walkthrough covers the fix for the integration test failure encountered during `mvn -B verify`, where CSV parsing failed on line 9 due to trailing blank lines in test resources.

---

## Changes Made

### 1. Fixed [`src/test/resources/trips.csv`](file:///workspaces/datsys-engine-SeMi/src/test/resources/trips.csv)
Removed trailing empty lines so the file contains exactly 8 rows:
```csv
Copenhagen,12,23.5
Aarhus,187,301.0
Odense,95,120.75
Copenhagen,140,210.0
Aalborg,210,340.5
Roskilde,31,45.0
Copenhagen,88,99.99
Esbjerg,299,450.25
```

### 2. Fixed [`src/test/resources/trips_sorted.csv`](file:///workspaces/datsys-engine-SeMi/src/test/resources/trips_sorted.csv)
Removed trailing empty lines so the file contains exactly 8 rows sorted by `distance`:
```csv
Copenhagen,12,23.5
Roskilde,31,45.0
Copenhagen,88,99.99
Odense,95,120.75
Copenhagen,140,210.0
Aarhus,187,301.0
Aalborg,210,340.5
Esbjerg,299,450.25
```

---

## Verification Results

### 1. `mvn -B verify` Execution
Running the full build and test lifecycle confirmed all tests passed:

```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running datasys.semi.EngineTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.134 s -- in datasys.semi.EngineTest
[INFO] Running datasys.semi.StorageEngineUnitTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.057 s -- in datasys.semi.StorageEngineUnitTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] --- failsafe:3.5.6:integration-test (default) @ engine ---
[INFO] Running datasys.semi.StorageEngineIT
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.040 s -- in datasys.semi.StorageEngineIT
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] --- failsafe:3.5.6:verify (default) @ engine ---
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  7.582 s
[INFO] Finished at: 2026-09-09T12:30:40Z
[INFO] ------------------------------------------------------------------------
```

### 2. `mvn compile exec:java` Execution
Running the golden example verified end-to-end execution of `StorageEngine`:

```text
distance > 100: [[Aarhus, 187, 301.0], [Copenhagen, 140, 210.0], [Aalborg, 210, 340.5], [Esbjerg, 299, 450.25]]
city = Copenhagen: [[Copenhagen, 12, 23.5], [Copenhagen, 140, 210.0], [Copenhagen, 88, 99.99]]
price < 50.0: [[Copenhagen, 12, 23.5], [Roskilde, 31, 45.0]]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

