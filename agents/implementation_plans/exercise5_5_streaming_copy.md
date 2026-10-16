# Implementation Plan: Exercise 5 §5 (Streaming `COPY`)

## 1. Sub-Exercise Reference
Exercise 5, Section 5: "Optional: hit the memory wall".
See [`exercise_descriptions/Exercise5.md`](../../exercise_descriptions/Exercise5.md). The `copyFile`
contract comes from [`exercise_descriptions/Exercise2.md`](../../exercise_descriptions/Exercise2.md) (§`copyFile`).

## 2. Background & Objective
`StorageEngine.copyFile` reads every CSV line into one `List<Object[]>` before it writes the first
partition. Memory therefore grows with the input file, and a CSV larger than the heap ends in
`OutOfMemoryError`. Exercise 5 §5 asks where the "data fits in memory" assumption lives and
what would have to change. For `COPY`, the answer is to stream the file: buffer at most one
partition (`maxRowsPerPartition` rows), write it, and continue.

The objective is to make `copyFile` stream while keeping every Exercise 2 guarantee.

## 3. Requirements & Invariants
- **Bounded memory**: at most `maxRowsPerPartition` parsed rows are held at any time.
- **All-or-nothing** (Exercise 2): "a malformed value fails the whole copy". With streaming,
  partitions may already be on disk when a bad line is found, so a failed copy must:
  - never write the catalog (the catalog is only written after the last partition), and
  - delete every partition file it wrote.
- **Cleanup by table directory**: a table accepts only one `COPY` in Part 1 (`copyFile` throws if
  the catalog already lists partitions), so `data/<table>/` holds no committed data when a copy
  starts. The copy may therefore:
  - clear `data/<table>/` before writing (removes leftovers of a crashed earlier copy), and
  - delete `data/<table>/` when the copy fails, including on `Error`s such as `OutOfMemoryError`
    (`finally` block with a commit flag).
  This stops being valid once appending arrives in Part 2; the code says so in a comment.
- Unchanged behavior:
  - Partitions of at most `maxRowsPerPartition` rows, named `partition-<n>.dat`, written as
    `.tmp-<uuid>` and moved with `ATOMIC_MOVE`.
  - Per-partition min/max computed from the rows being written, with one `LOGGER.debug` line per
    column per partition.
  - Summary line `table=… file=… rows=… partitions=… durationMs=…`.
  - Error messages naming file and line number (`<file>: line <n>: …`).
  - An empty CSV still produces a table with zero partitions.
- Logging (AGENTS.md §5): a failed copy leaves one single-line `LOGGER.error` with the sanitized
  message; no `Throwable` is passed to SLF4J. Cleanup failures are logged, not swallowed.

## 4. Proposed Changes
`src/main/java/datasys/semi/engine/StorageEngine.java`:
- `copyFile`: guard clauses as before, then clear the table directory, stream, commit the catalog,
  and in `finally` delete the table directory if not committed. Runtime failures log an error and
  are rethrown.
- New private `streamCsvIntoPartitions(...)`: reads lines with `BufferedReader`, parses each line
  into the current partition buffer, and flushes when the buffer reaches `maxRowsPerPartition`
  and at end of file.
- New private `writePartitionFile(...)`: writes one buffered partition (tmp file + atomic move),
  computes its statistics, and returns its `Partition` entry.
- New private `commitCatalog(...)`: writes the catalog with the new partitions and caches it.
- New private helpers `deleteDirectoryRecursively(...)` and `discardFailedCopy(...)`.
- `cleanupTemporaryFiles`: log failures instead of an empty `catch` block.
- The old buffer-everything loop and its `temporaryFiles` list are removed.

`src/test/java/datasys/semi/StorageEngineIT.java`:
- New test `malformedLineAfterFullPartitionsLeavesNoPartitionsBehind`: with
  `maxRowsPerPartition = 2`, a CSV whose 5th line is malformed fails the copy. Afterwards the catalog
  lists no partitions, `data/trips/` does not exist, and a new engine on the same directory has
  no partitions.
- New test `failedCopyCanBeRetriedWithValidFile`: after a failed copy, a valid copy succeeds and
  returns all rows.
- New test `copyStreamsIntoPartitionsOfAtMostMaxRows`: an 11-row file with `maxRowsPerPartition = 4`
  gives partitions of 4, 4, 3 rows, so the final partial partition is checked.

## 5. Verification Plan
```bash
mvn clean compile
mvn -B verify
```
Expected: all existing unit and integration tests pass unchanged, plus the three new
`StorageEngineIT` tests; 0 failures and 0 errors.
