# Walkthrough: Exercise 5 §5 (Streaming `COPY`)

Plan: [`agents/implementation_plans/exercise5_5_streaming_copy.md`](../implementation_plans/exercise5_5_streaming_copy.md)

## 1. Summary of Work Accomplished
`StorageEngine.copyFile` now streams the CSV file instead of loading it into memory first. It
holds at most one partition (`maxRowsPerPartition` rows) of parsed rows at a time, so `COPY`
memory no longer grows with the input file. The copy is still all-or-nothing: the catalog is only
written after the last partition, and a failed copy deletes the table's partition directory.

## 2. Key Changes & Architecture
`src/main/java/datasys/semi/engine/StorageEngine.java`:
- **`copyFile`**: guard clauses (unknown table, table already has data) are unchanged. Then it:
  1. clears `data/<table>/`,
  2. streams the file into partitions (`streamCsvIntoPartitions`),
  3. commits the catalog (`commitCatalog`), and
  4. logs the summary line (`logCopySummary`).

  A `committed` flag plus `finally` deletes `data/<table>/` on any failure, including `Error`s such as
  `OutOfMemoryError`. A `RuntimeException` logs `api=copyFile table=… error=…` and is rethrown.
- **`streamCsvIntoPartitions`**: reads with `BufferedReader.readLine()`, parses into a partition
  buffer, and flushes the buffer when it reaches `maxRowsPerPartition` and at end of file.
- **`writePartitionFile`**: writes one partition as `.tmp-<uuid>`, moves it atomically to
  `partition-<n>.dat`, then computes and logs min/max statistics.
- **`discardFailedCopy` / `deleteDirectoryRecursively`**: cleanup of uncommitted partitions. A
  cleanup failure is logged, not rethrown, so the original failure reaches the caller.
- **`cleanupTemporaryFiles`**: the empty `catch` block now logs the failure (AGENTS.md §3).

**Why deleting the whole table directory is safe:** in Part 1 a table accepts a single `COPY`
(`copyFile` throws `UnsupportedOperationException` when the catalog already lists partitions), so
`data/<table>/` never holds committed data while a copy runs. A comment in `copyFile` notes that
this must change when appending arrives in Part 2.

`src/test/java/datasys/semi/StorageEngineIT.java`: three new integration tests (see §4).

## 3. Verification Results
`mvn clean compile`: success.

`mvn -B verify`:
```
[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0          (Surefire, unit)
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.StorageEngineIT
[INFO] Tests run: 164, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.SqlCorpusIT
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.BinderIT
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.EngineIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in datasys.semi.EngineLogIT
[INFO] Tests run: 187, Failures: 0, Errors: 0, Skipped: 0         (Failsafe, integration)
[INFO] BUILD SUCCESS
```

Memory check (Exercise 5 §5 scenario) with the packaged JAR. The input was a generated 2,000,000-row
CSV (38 MB), loaded with `ENGINE_JAVA_OPTS=-Xmx64m` and the default partition size of 10,000:
```
DEBUG,statement=COPY table=big file=.../big.csv durationMs=12823
```
The copy wrote 200 partition files, logged no `ERROR` line, and the process exited with 0.

## 4. Edge Cases & Invariants Tested
- `malformedLineAfterFullPartitionsLeavesNoPartitionsBehind`: with `maxRowsPerPartition = 2`,
  line 5 is malformed after two partitions were already written. The test checks that:
  - the copy throws with `broken.csv: line 5`,
  - the catalog lists no partitions,
  - `data/trips/` is gone, and
  - a restarted engine also sees no partitions.
- `failedCopyCanBeRetriedWithValidFile`: after a failed copy the table is still empty, so a valid
  copy succeeds (4 partitions, 8 rows).
- `copyStreamsIntoPartitionsOfAtMostMaxRows`: 11 rows with `maxRowsPerPartition = 4` produce
  partitions of 4, 4 and 3 rows. The final partial buffer is flushed, and all 11 rows are readable.
- Existing tests are unchanged and still pass:
  - Exercise 2 round trip, partitioning with exact min/max, pruning, and persistence across engines.
  - Line-number error messages in `StorageEngineUnitTest`.
  - `EngineLogIT` error-line attribution.
