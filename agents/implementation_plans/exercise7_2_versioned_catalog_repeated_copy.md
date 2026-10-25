# Implementation Plan — Exercise 7.2: Versioned Catalog & Repeated COPY

## 1. Sub-Exercise Reference

- Specification: [`exercise_descriptions/Exercise7.md` §2 "Version the catalog and allow repeated COPY"](file:///workspaces/datsys-engine-SeMi/exercise_descriptions/Exercise7.md)
- Design decisions: [`docs/transactions-design.md`](file:///workspaces/datsys-engine-SeMi/docs/transactions-design.md) (§1 Versioning, §2 Commit Atomicity, §3 Snapshot, §4 Retention, §7 Partition Placement)

## 2. Background & Objective

Part 1 treats a table's catalog as a single mutable `catalogs/<table>.json` that a single `COPY` fills exactly once:

- [`StorageEngine.copyFile`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/engine/StorageEngine.java#L118-L146) rejects a second `COPY` with `UnsupportedOperationException` (L122-124).
- It also **wipes `data/<table>/`** before loading (L133) and deletes the entire table directory when a load fails (`discardFailedCopy`, L405-411).
- Partition files are named `partition-<n>.dat`, and `n` restarts at 0 on every `COPY`. A second `COPY` would overwrite committed partitions.
- There is no snapshot today. The Binder, the Planner (L75-76) and `ScanOperator` (L134, per partition) each re-read the *current* catalog from `StorageEngine`.

**Objective:**

- Give every table a monotonically increasing **version**: `CREATE TABLE` produces v0, and every committed `COPY` increments it.
- Persist one immutable catalog file per version and commit each version atomically, both on disk and in memory.
- Let `SELECT` (and `COPY`) pin the last committed version at statement start.
- Allow repeated `COPY` that appends partitions.
- Log `version=N` on every statement summary line.

This lays the groundwork for `DELETE` (7.3/7.4), which reuses the same commit and snapshot machinery.

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | `Catalog` carries a `version` (`long`). `CREATE TABLE` commits version 0 with an empty partition list. |
| F2 | Every committed `COPY` builds `newPartitions = snapshot.partitions + loadedPartitions`, sets `version = snapshot.version + 1`, and commits both together. |
| F3 | Each version is persisted as `catalogs/<table>-v<version>.json`, which also contains the `"version"` field (design §1). |
| F4 | On startup, `loadCatalogs` loads the **highest** version per table. It ignores `*.tmp-*` leftovers and files that don't match `^(.+)-v(\d+)\.json$`. |
| F5 | The one-`COPY` restriction is removed. A second `COPY` appends partitions and does not touch existing partition files. |
| F6 | New partition files are named `data/<table>/partition-<uuid>.dat` (design §7). |
| F7 | A statement pins a `Catalog` reference **at statement start, before binding** (design §3). Binder, Planner and `ScanOperator` use only the pinned snapshot and never re-read `StorageEngine` metadata. |
| F8 | Summary lines include the committed or read version:<br>`statement=CREATE_TABLE table=trips version=0 durationMs=…`<br>`statement=COPY table=trips rows=8 partitions=2 version=2 durationMs=12`<br>`statement=SELECT table=trips rowsOut=4 partitionsRead=2 partitionsPruned=2 version=2 durationMs=3` |
| F9 | Retention (design §4): keep the newest **10** catalog versions per table on disk and delete older catalog files after a successful commit. Asking for a dropped version throws `IllegalStateException("version N of table T is no longer available")`. |

### Invariants
- **I1 Immutability:** a written partition file or catalog file is never modified. `Catalog`, `Partition` and `Statistics` become immutable `record`s with defensive `List.copyOf`/`Map.copyOf`.
- **I2 Atomic commit:**
  - On disk: write `<table>-v<N>.json.tmp-<uuid>`, then `moveAtomically` into place.
  - In memory: one reference swap in a `ConcurrentHashMap`.
  - The disk write happens **before** the in-memory swap, so memory never shows a version that isn't durable.
- **I3 Monotonic versions:** a commit only succeeds if the current version still equals the snapshot's base version. Otherwise it throws `IllegalStateException` (minimal guard; real conflict validation is Exercise 8).
- **I4 Failed COPY is harmless:**
  - It deletes only the partition files *it* wrote (tracked list).
  - It never deletes committed partitions.
  - The catalog stays at the previous version.
- **I5 Logging:** only `debug`/`error`, comma-free messages, and every thrown exception leaves a `LOGGER.error` line (AGENTS.md §5). In particular, all new failure paths (`snapshot` / `requireCatalog` when called outside `Executor`, `commitCatalog` on stale base version, `readCatalogVersion` on dropped/missing version, and `pruneOldCatalogVersions` on I/O errors) must log `LOGGER.error(...)` before throwing.

### Non-Functional
- Commit cost is O(#partitions) JSON serialization. No global locks on the read path.
- Methods ≤ ~20 lines, full Javadoc, grouped fields (AGENTS.md §3-4).

## 4. Proposed Changes

### 4.1 `engine/StorageEngine.java` — catalog model
- Convert the nested classes to records (Jackson supports records natively):
  ```java
  public record Catalog(String table, long version, List<Column> schema, List<Partition> partitions) { /* compact ctor: List.copyOf */ }
  public record Column(@JsonProperty("column_name") String name, @JsonProperty("column_type") String type) { }
  public record Partition(String path, int rowCount, Map<String, Statistics> statistics) { /* Map.copyOf */ }
  public record Statistics(String min, String max) { }
  ```
- Add `Catalog withAppendedPartitions(List<Partition> added)` → returns a new `Catalog` with `version + 1`.
- Update all field accesses (`catalog.partitions` → `catalog.partitions()`, etc.) in the engine, Planner, Binder, ScanOperator and tests.

### 4.2 `engine/StorageEngine.java` — storage & commit
| Change | Detail |
|---|---|
| `catalogs` map | `HashMap` → `ConcurrentHashMap<String, Catalog>` |
| `snapshot(String table)` *(new, public)* | Returns the current immutable `Catalog`, or logs `LOGGER.error` and throws IAE `unknown table`. Replaces `partitions(...)`/`schema(...)` for statement-level access. |
| `createTable` | Commits `new Catalog(name, 0, schema, List.of())` via `commitCatalog`. |
| `copyFile(Catalog snapshot, String csvFilePath)` | <ol><li>Pass a caller-owned `List<Path> writtenFiles` collector into `streamCsvIntoPartitions` so files written before a mid-stream parse/I/O error are still tracked when an exception unwinds the stack.</li><li>Stream the CSV into new `partition-<uuid>.dat` files, recording each file in `writtenFiles` immediately after `moveAtomically`.</li><li>Build the next version with `snapshot.withAppendedPartitions(...)`.</li><li>Call `commitCatalog`.</li><li>Return a new `CopyResult(rows, partitions, version)` record.</li></ol>Remove the restriction (L122-124), the directory wipe (L133) and the whole-directory `discardFailedCopy`. Update the stale Javadoc (L104-117). |
| `commitCatalog(Catalog base, Catalog next)` | <ol><li>Pre-serialize `next` to `<table>-v<version>.json.tmp-<uuid>` before entering the map lock.</li><li>Run `catalogs.compute(table, …)`.</li><li>Inside it, verify `current.version() == base.version()` (I3); if stale, log `LOGGER.error` and throw `IllegalStateException` (cleaning up the `.tmp` file in `catch`).</li><li>Perform `moveAtomically(temporary, target)` and return `next`.</li></ol>Then call `pruneOldCatalogVersions(table)`. |
| `writeTemporaryCatalog` / `publishCatalogAtomically` | Target becomes `catalogFileName(table, version)` = `<table>-v<version>.json`. Writes `.tmp-<uuid>` outside the lock and moves atomically inside `compute`. |
| `loadCatalogs` | Match filenames against `^(.+)-v(\d+)\.json$`, parse the version as a `long`, group by table name, read only the highest numerical version (`Long::compare`), and log `table=… version=… loaded` at debug. |
| `pruneOldCatalogVersions` *(new)* | Uses `Files.newDirectoryStream` and matches files against `^\Q<table>\E-v(\d+)\.json$` (avoiding prefix collisions with other tables), sorts by parsed `long` version (`Long::compare`, never lexicographical filename order where `-v10` precedes `-v2`), and deletes all but the newest 10. An `IOException` is logged at `LOGGER.error` level and not rethrown so post-commit housekeeping never rolls back a committed version. |
| `readCatalogVersion(String table, long version)` *(new)* | Reads an older version from disk. Logs `LOGGER.error` and throws `IllegalStateException` if the file is gone (F9). Used by tests now and by time-travel/DELETE later. |
| `writePartitionFile` | `"partition-%s.dat".formatted(UUID.randomUUID())`. Clean up `temporaryPath` via `cleanupTemporaryFiles` if `writePartition` fails before `moveAtomically`, and append `finalPath` to `writtenFiles` once moved. Pass `snapshot.partitions().size() + partitions.size()` as the `partitionNumber` ordinal so `computeStatistics` debug logs match the partition's index in the new catalog. |
| `discardWrittenPartitions(Path tableDirectory, List<Path> writtenFiles)` *(replaces `discardFailedCopy`)* | Deletes only the files in `writtenFiles` that this `COPY` wrote, then removes `data/<table>/` **only if it is empty**. That way `malformedLineAfterFullPartitionsLeavesNoPartitionsBehind` keeps passing without touching committed partitions. |
| `readPartition(Catalog, Partition)` | Replaces `readPartition(String, int)` and `readPartition(String, Partition)` so scans never consult the live catalog. |
| `logCopySummary` | Moves into Executor (see 4.4). The engine keeps a debug line with `version=`. |

> [!NOTE]
> **Reference counting is deferred to 7.4.** In 7.2, `COPY` only appends, so no partition file ever stops being referenced. Retention can only remove *catalog JSON files*, and running statements hold the `Catalog` in memory, so this is safe without reader counts. Per-version reader refcounts (design §4) become necessary once `DELETE` can drop partitions, and will be added in the DELETE sub-exercise.

### 4.3 Snapshot plumbing
- **`executor/Executor.java`:** at the top of `executeStatement`/`executeQuery`, pin the snapshot with `engine.snapshot(tableOf(statement))`. `CREATE TABLE` has no snapshot. Pass the pinned snapshot to the binder, planner and executor helpers.
- **`parser/Binder.java`:**
  - `bind(Statement, Catalog snapshot)` validates against `snapshot.schema()` and no longer calls `engine.schema`.
  - Keep a `bind(Statement)` overload for `CREATE TABLE`.
- **`planner/Planner.java`:**
  - `plan(SelectStatement, Catalog snapshot)` uses `snapshot.partitions()`.
  - `ScanStats` gains a `version` field so the summary line can report it.
  - `prunePartitions` stays private for now. It becomes public in the DELETE sub-exercise (design §5).
- **`operators/ScanOperator.java`:**
  - The constructor becomes `ScanOperator(StorageEngine, Catalog snapshot, List<Integer> partitionIndices)`.
  - Partitions are resolved from the snapshot and read with `engine.readPartition(snapshot, partition)`.
  - `partitionNumbers()` is kept for PlannerTest assertions.

### 4.4 Logging (Executor)
- `statement=CREATE_TABLE table={} version={} durationMs={}`
- `statement=COPY table={} rows={} partitions={} version={} durationMs={}`, using `CopyResult` for rows/partitions/version.
- `statement=SELECT table={} rowsOut={} partitionsRead={} partitionsPruned={} version={} durationMs={}`
- The lines keep the `statement=<X> ` prefix and the `Executor` logger, so `summarize-experiments.sh` (L25) still works.

### 4.5 Files Touched
| File | Nature |
|---|---|
| `engine/StorageEngine.java` | Records, versioned commit, per-version files, UUID partitions, retention, failure cleanup |
| `executor/Executor.java` | Pin the snapshot, new summary lines |
| `parser/Binder.java` | Bind against the snapshot |
| `planner/Planner.java` | Plan against the snapshot, version in `ScanStats` |
| `models/ScanStats.java` | Add `version` |
| `operators/ScanOperator.java` | Read the pinned partitions |
| `engine/CopyResult.java` *(new record)* | `rows`, `partitions`, `version` |
| Tests (see §5) | Update field accesses, constructor changes, catalog filenames |

## 5. Verification Plan

### 5.1 Existing tests to update
| Test | Change |
|---|---|
| `StorageEngineIT.schemaPersistenceAcrossRestart` (L42) | Expect `catalogs/trips-v0.json`. |
| `StorageEngineIT.partitioningProducesExpectedPartitionsAndStatistics` (L191) | Read `trips-v1.json` and assert `version == 1`. |
| `PlannerTest.prunePartitionsKeepsPartitionAndLogsWhenStatisticsAreMissing` (L184) | It mutates `statistics`, which is impossible once records are immutable. Build a `Catalog` snapshot with a partition whose statistics map omits `distance` and plan against it. |
| `ScanOperatorTest` (L38, L58, L76) | Update `copyFile` and `ScanOperator` calls to pass `engine.snapshot("trips")`. |
| `PlannerTest`, `ExecutorTest`, `StorageEngineIT` | Rewrite `copyFile`, `plan`, and catalog accessors (`partitions`/`schema`) to pass/read `engine.snapshot("trips")` and use record accessors. |

### 5.2 New tests
| Test class | Test method | Asserts |
|---|---|---|
| `StorageEngineIT` | `createCopyCopyProducesVersionsZeroThroughTwo` | `snapshot().version()` is 0 → 1 → 2. Partition count is the sum of both loads. |
| `StorageEngineIT` | `secondCopyAppendsWithoutOverwritingExistingPartitions` | The v1 partition paths are still present in v2 and their file bytes are unchanged. All paths are unique. |
| `StorageEngineIT` | `eachCommitWritesItsOwnCatalogFile` | `trips-v0.json`, `trips-v1.json` and `trips-v2.json` exist, and each `"version"` field matches its filename. |
| `StorageEngineIT` | `freshEngineLoadsHighestCatalogVersion` | Restart on the same directory sees v2. Stray `*.tmp-*` files are ignored. |
| `StorageEngineIT` | `failedSecondCopyKeepsPreviousVersionAndPartitions` | A malformed second `COPY` leaves the version at 1, keeps the v1 partitions readable and deletes the new partition files. |
| `StorageEngineIT` | `retentionKeepsOnlyTenNewestCatalogVersions` | After 12 `COPY` statements only v3–v12 remain. `readCatalogVersion(…, 1)` throws `IllegalStateException`. |
| `ExecutorTest` | `selectAfterEachCommitSeesThatCommitsRows` | After each `COPY`, `SELECT` returns the cumulative row count. |
| `ExecutorTest` | `pinnedSnapshotIsUnaffectedByLaterCommit` | A plan built from snapshot v1 still yields only the v1 rows after a v2 commit. |
| `ExecutorTest` | `commitWithStaleBaseVersionThrowsIllegalStateException` | Covers I3. |
| `EngineLogIT` | `summaryLinesCarryVersion` | Matching `statement=CREATE_TABLE … version=0`, `statement=COPY … version=1`, `statement=SELECT … version=1` lines exist in the log. |
| `EngineIT` | `repeatedCopyScriptReturnsRowsFromBothFiles` | `./engine -f` with `CREATE`, `COPY`, `COPY`, `SELECT` prints the union of both loads. |

### 5.3 Commands
```bash
mvn clean compile
mvn -B verify
```
**Expected:** all Surefire (`*Test`) and Failsafe (`*IT`) tests pass with 0 failures and 0 errors. Also inspect `logs/engine.log` manually for `version=` on the summary lines.

## 6. Out of Scope (later sub-exercises)
- `DELETE` grammar, AST, binder and printer (7.3).
- Copy-on-write rewrite, the public pruning helper, and per-version reader refcounts (7.4).
- Orphan-partition garbage collection beyond failed-`COPY` cleanup.
- Concurrent-writer conflict handling (Exercise 8).

