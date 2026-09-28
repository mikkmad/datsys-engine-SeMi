# Exercise 4, Part 1 — Partition-Number Scan

## 1. Summary of Work Accomplished

Changed `ScanOperator` to accept the planner's zero-based partition numbers, matching the operator example. The selected numbers are captured with `List.copyOf`, preserving the planner's order while preventing later mutation.

## 2. Key Changes & Architecture

- `ScanOperator` now stores `List<Integer> partitionNumbers`.
- `StorageEngine.readPartition(String, int)` validates the number against the table catalog and resolves the selected partition.
- The existing `readPartition(String, Partition)` overload remains available.
- `ScanOperatorTest` now verifies selection with `List.of(1, 3)` and retains the empty-selection case.

## 3. Verification Results

| Command | Result |
| --- | --- |
| Focused `ScanOperatorTest` | 3 tests passed, 0 failures, 0 errors |
| `mvn clean compile` | Passed |
| `mvn -B verify` | 17 unit tests and 15 integration tests passed; 0 failures, 0 errors |

## 4. Edge Cases & Invariants Tested

- Selected partitions are read in the supplied order.
- An empty partition-number list returns no rows.
- Partition numbers are validated before file access.
- Existing unit and integration behavior remains green.
