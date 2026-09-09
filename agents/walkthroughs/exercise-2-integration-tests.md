# Walkthrough: Exercise 2 Integration Test Suite

## Overview
This walkthrough documents the completion of the integration test suite for the `datsys-engine-SeMi` storage engine. All changes ensure strict compliance with Section 4 and Section 5 of [`exercise_descriptions/Exercise2.md`](file:///workspaces/datsys-engine-SeMi/exercise_descriptions/Exercise2.md).

---

## Files Added & Modified

### 1. Test Resources
- [**`src/test/resources/trips.csv`**](file:///workspaces/datsys-engine-SeMi/src/test/resources/trips.csv)
  - Contains the 8 golden lines representing trips:
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

- [**`src/test/resources/trips_sorted.csv`**](file:///workspaces/datsys-engine-SeMi/src/test/resources/trips_sorted.csv)
  - Contains the golden trips dataset sorted by `distance` ascending. With `maxRowsPerPartition = 2`, this creates four partitions with monotonic intervals:
    - Partition 0: `[12, 31]`
    - Partition 1: `[88, 95]`
    - Partition 2: `[140, 187]`
    - Partition 3: `[210, 299]`

### 2. Integration Test Suite ([`StorageEngineIT.java`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/StorageEngineIT.java))

Replaced the two incomplete tests with the complete 9-test suite where each test receives an isolated `@TempDir Path directory`:

#### Test 1: `schemaPersistenceAcrossRestart`
- **Objective**: Verify that table schema is stored persistently in the catalog and survives engine restart.
- **Actions**:
  1. Creates table `"trips"` on `engine1`.
  2. Instantiates `engine2` pointing to the same directory.
  3. Verifies `engine2.createTable("trips", SCHEMA)` throws `IllegalArgumentException` (table already known).
  4. Verifies `engine2.select(...)` succeeds against the schema.
  5. Verifies `<directory>/catalogs/trips.json` exists on disk.

#### Test 2: `duplicateTableThrows`
- **Objective**: Confirm that calling `createTable` with an existing name fails immediately.
- **Actions**: Creates table `"trips"` and asserts `IllegalArgumentException` on a duplicate call on the same engine instance.

#### Test 3: `roundTripMatchesAllRowsWithCorrectTypes`
- **Objective**: Ingest the golden CSV, query with a catch-all predicate (`distance > -1L`), and assert that all 8 rows are returned with correct values and strict Java types.
- **Actions**:
  1. Copies `trips.csv` into the temp directory via `copyResource`.
  2. Asserts row count is exactly 8.
  3. Validates each row against expected values in disk order.
  4. Verifies type enforcement: `row[0] instanceof String`, `row[1] instanceof Long`, `row[2] instanceof Double`.

#### Test 4: `allComparisonsAcrossAllTypesAgainstGoldenData`
- **Objective**: Test 9 combinations of comparisons (`=`, `<`, `>`) across all three column types (`STRING`, `LONG`, `DOUBLE`) against the golden dataset.
- **Actions**:
  - `city = 'Copenhagen'` $\rightarrow$ 3 rows
  - `city < 'Copenhagen'` $\rightarrow$ 2 rows (`Aalborg`, `Aarhus`)
  - `city > 'Copenhagen'` $\rightarrow$ 3 rows (`Esbjerg`, `Odense`, `Roskilde`)
  - `distance = 95L` $\rightarrow$ 1 row (`Odense`)
  - `distance < 95L` $\rightarrow$ 3 rows (`12`, `31`, `88`)
  - `distance > 95L` $\rightarrow$ 4 rows (`140`, `187`, `210`, `299`)
  - `price = 120.75` $\rightarrow$ 1 row (`Odense`)
  - `price < 50.0` $\rightarrow$ 2 rows (`23.5`, `45.0`)
  - `price > 300.0` $\rightarrow$ 3 rows (`301.0`, `340.5`, `450.25`)

#### Test 5: `emptyResultWhenPredicateMatchesNothing`
- **Objective**: Confirm that queries with non-matching predicates return an empty list rather than null or error.
- **Actions**: Queries `city = 'NonExistentCity'`, asserts `rows.isEmpty()`.

#### Test 6: `errorsOnUnknownTableColumnAndTypeMismatch`
- **Objective**: Validate error handling against invalid requests.
- **Actions**:
  - Unknown table name $\rightarrow$ throws `IllegalArgumentException`.
  - Unknown column name $\rightarrow$ throws `IllegalArgumentException`.
  - Type mismatch: Passing an `Integer` constant for a `LONG` column $\rightarrow$ throws `IllegalArgumentException` (no auto-widening).
  - Type mismatch: Passing a `String` constant for a `DOUBLE` column $\rightarrow$ throws `IllegalArgumentException`.

#### Test 7: `partitioningProducesExpectedPartitionsAndStatistics`
- **Objective**: Verify that `maxRowsPerPartition = 2` produces 4 partitions and verifies persisted partition statistics in the catalog.
- **Actions**:
  1. Bulk-loads `trips.csv` with `maxRowsPerPartition = 2`.
  2. Deserializes `catalogs/trips.json` using Jackson `ObjectMapper` into `StorageEngine.Catalog`.
  3. Verifies `catalog.partitions.size() == 4`.
  4. Asserts exact `rowCount = 2` and column min/max bounds for all 4 partitions:
     - Partition 0: `city: [Aarhus, Copenhagen]`, `distance: [12, 187]`, `price: [23.5, 301.0]`
     - Partition 1: `city: [Copenhagen, Odense]`, `distance: [95, 140]`, `price: [120.75, 210.0]`
     - Partition 2: `city: [Aalborg, Roskilde]`, `distance: [31, 210]`, `price: [45.0, 340.5]`
     - Partition 3: `city: [Copenhagen, Esbjerg]`, `distance: [88, 299]`, `price: [99.99, 450.25]`

#### Test 8: `pruningSkipsPartitionsUsingMinMax`
- **Objective**: Verify that partition pruning skips partitions using min/max summaries without reading data files.
- **Actions**:
  1. Bulk-loads `trips_sorted.csv` with `maxRowsPerPartition = 2`.
  2. Executes `select("trips", "distance", Comparison.GREATER_THAN, 200L)`.
  3. Inspects `engine.getLastScanStats()`:
     - `partitionsTotal`: 4
     - `partitionsRead`: 1
     - `partitionsPruned`: 3 ($\ge 2$, satisfying requirement)
  4. Confirms exactly the 2 expected matching rows: `Aalborg (210)` and `Esbjerg (299)`.

#### Test 9: `dataPersistenceAcrossEngines`
- **Objective**: Verify that data written by one engine instance can be queried by a fresh engine instance constructed on the same directory.
- **Actions**:
  1. Engine A writes the table and data.
  2. Engine B initializes on the same directory.
  3. Select query results from Engine A and Engine B are compared row-by-row and element-by-element using `assertArrayEquals`.

---

## Resource Loading Strategy

In [`StorageEngineIT.java`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/StorageEngineIT.java#L257-L268), `copyResource(Path directory, String filename)` handles file preparation:
```java
private static Path copyResource(Path directory, String filename) throws IOException {
    Path target = directory.resolve(filename);
    try (InputStream stream = StorageEngineIT.class.getResourceAsStream("/" + filename)) {
        if (stream != null) {
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }
    Path localPath = Path.of("src/test/resources", filename);
    Files.copy(localPath, target, StandardCopyOption.REPLACE_EXISTING);
    return target;
}
```
This isolates each test run inside its designated `@TempDir`, avoids hardcoding test data in Java source code, and works seamlessly across command-line Maven builds and IDE runners.

