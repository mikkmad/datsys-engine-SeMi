# Implementation Plan: Exercise 2 Integration Test Suite

## Background & Motivation
In accordance with [`exercise_descriptions/Exercise2.md`](file:///workspaces/datsys-engine-SeMi/exercise_descriptions/Exercise2.md), the storage engine must be validated by two distinct test suites: unit tests in `*Test` and end-to-end integration tests in `*IT` running under Maven Failsafe. While the engine implementation in [`StorageEngine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/StorageEngine.java) and the unit tests in [`StorageEngineUnitTest.java`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/StorageEngineUnitTest.java) fulfilled their specifications, the integration test suite in [`StorageEngineIT.java`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/StorageEngineIT.java) was incomplete and did not cover the 9 required test scenarios against the golden dataset.

This plan details the design and implementation of the complete 9-test integration suite and corresponding test resources.

---

## Requirements from Exercise 2 (§4)

The integration tests must each execute on a fresh JUnit `@TempDir` and test:
1. **Schema persistence**: `createTable`, restart engine, table is still known and catalog exists.
2. **Duplicate table**: Second `createTable` with the same name throws `IllegalArgumentException`.
3. **Round trip**: Copy golden CSV; a predicate matching everything (e.g., `distance > -1`) returns all 8 rows with correct values and types.
4. **All comparisons × all types**: `=`, `<`, `>` against `STRING`, `LONG`, and `DOUBLE` (9 combinations), verified against golden data.
5. **Empty result**: A predicate matching nothing returns an empty list.
6. **Errors**: Unknown table, unknown column, and type-mismatched constants each throw `IllegalArgumentException`.
7. **Partitioning**: With `maxRowsPerPartition = 2`, the golden file produces 4 partitions with correct `rowCount` and per-column `min`/`max` values read back from the catalog.
8. **Pruning**: Copy CSV sorted by `distance` with `maxRowsPerPartition = 2`; a selective predicate (e.g., `distance > 200`) reports at least 2 partitions pruned in `ScanStats` and returns exact matching rows.
9. **Data persistence**: Copy with engine A; a new engine B on the same directory returns identical rows.

Additionally:
- Test CSV files must live in `src/test/resources/` instead of hardcoded test strings.
- Tests must load resources in an isolated manner per `@TempDir`.

---

## Proposed Changes

### 1. Test Resources
Create dedicated test CSV fixtures in `src/test/resources/`:
- `trips.csv`: The 8-row golden dataset in original order.
- `trips_sorted.csv`: The 8-row golden dataset sorted by `distance` ascending (`[12, 31]`, `[88, 95]`, `[140, 187]`, `[210, 299]`) to ensure predictable partition boundaries for pruning.

### 2. Integration Test Implementation (`StorageEngineIT.java`)
- Define shared schema constant: `List<ColumnSpec>` for `city: STRING`, `distance: LONG`, `price: DOUBLE`.
- Implement `copyResource(Path directory, String filename)`:
  - Reads resource stream via classloader (`StorageEngineIT.class.getResourceAsStream("/" + filename)`).
  - Falls back to `Path.of("src/test/resources", filename)`.
  - Atomically copies the resource file into the isolated `@TempDir` for the test.
- Implement the 9 test methods annotated with `@Test` and `@TempDir Path directory`.
- Use Jackson's `ObjectMapper` in Test 7 to deserialize `StorageEngine.Catalog` from `catalogs/trips.json` to verify persisted partition statistics.

---

## Verification Plan

### Automated Test Verification
- Run unit tests: `mvn test` (runs Surefire plugin).
- Run integration tests: `mvn verify` (runs Failsafe plugin with `*IT` pattern).
- Verify each test method runs independently without shared state across `@TempDir`.

### Scenarios Covered
| Test | Method Name | Verification Criteria |
| :--- | :--- | :--- |
| 1 | `schemaPersistenceAcrossRestart` | Table known across restarts, catalog file present, duplicate creation fails |
| 2 | `duplicateTableThrows` | `createTable` fails on existing table name |
| 3 | `roundTripMatchesAllRowsWithCorrectTypes` | 8 rows returned, correct types (`String`, `Long`, `Double`), exact values |
| 4 | `allComparisonsAcrossAllTypesAgainstGoldenData` | 9 combinations (`=`, `<`, `>` on `city`, `distance`, `price`) |
| 5 | `emptyResultWhenPredicateMatchesNothing` | Non-matching query returns empty list |
| 6 | `errorsOnUnknownTableColumnAndTypeMismatch` | Throws on missing table, missing column, `Integer` for `LONG`, `String` for `DOUBLE` |
| 7 | `partitioningProducesExpectedPartitionsAndStatistics` | 4 partitions, 2 rows/part, exact min/max stats verified in `trips.json` |
| 8 | `pruningSkipsPartitionsUsingMinMax` | 4 total, 1 read, 3 pruned ($\ge 2$), correct 2 matching rows |
| 9 | `dataPersistenceAcrossEngines` | Engine A copies, Engine B reads identical rows |

