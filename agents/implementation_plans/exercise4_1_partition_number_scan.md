# Exercise 4, Part 1 — Partition-Number Scan

## 1. Sub-Exercise Reference

[`exercise_descriptions/Exercise4.md`](../../exercise_descriptions/Exercise4.md) §1, the `ScanOperator` pull-operator requirements.

## 2. Background & Objective

Align `ScanOperator` with the operator example by passing the planner's selected partition numbers instead of passing `Partition` metadata objects. The scan should still read selected partitions in order and return no rows for an empty selection.

## 3. Requirements & Invariants

- The scan accepts a non-null list of zero-based partition numbers.
- The supplied selection is captured as an unmodifiable snapshot.
- Partition numbers are read in the order supplied by the planner.
- An empty selection returns no rows and reads no partition.
- Invalid partition numbers fail explicitly at the storage boundary.
- Existing object-based partition reading remains available for compatibility.

## 4. Proposed Changes

- Change `ScanOperator` state and constructor to use `List<Integer> partitionNumbers`.
- Add `StorageEngine.readPartition(String, int)` to resolve and validate a catalog partition number.
- Update `ScanOperatorTest` to hand the scan `List.of(1, 3)`.

## 5. Verification Plan

Run:

```bash
mvn -B clean test
mvn -B verify
```

Expected result: all unit and integration tests pass with zero failures and errors.
