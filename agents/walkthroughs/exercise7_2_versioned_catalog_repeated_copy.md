# Walkthrough — Exercise 7.2: Versioned Catalog & Repeated COPY

## 1. Summary of Work Accomplished

Implemented per-version immutable table catalogs, statement-start snapshot pinning, catalog retention (10 versions), UUID-named partition files, and repeated `COPY` support per [`exercise_descriptions/Exercise7.md` §2](file:///workspaces/datsys-engine-SeMi/exercise_descriptions/Exercise7.md) and [`docs/transactions-design.md`](file:///workspaces/datsys-engine-SeMi/docs/transactions-design.md).

- **`CREATE TABLE`** now commits version `0` with an empty partition list to `catalogs/<table>-v0.json`.
- **Repeated `COPY`** streams rows into UUID-named partition files (`data/<table>/partition-<uuid>.dat`), appends the new partitions to the pinned snapshot's partitions, and atomically commits `version + 1` to `catalogs/<table>-v<version>.json` and the in-memory `ConcurrentHashMap`.
- **Statement-start Snapshot Pinning**: [`Executor`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/executor/Executor.java) pins an immutable [`Catalog`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/engine/StorageEngine.java) snapshot before binding, passing it through [`Binder`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/parser/Binder.java), [`Planner`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/planner/Planner.java), and [`ScanOperator`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/operators/ScanOperator.java) so running queries never re-read mutable metadata.
- **Versioned Statement Logging**: Summary log lines in [`Executor`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/executor/Executor.java) now record `version=<N>` on `CREATE_TABLE`, `COPY`, and `SELECT`.

---

## 2. Key Changes & Architecture

| File | Summary of Changes |
|------|--------------------|
| [`CopyResult.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/engine/CopyResult.java) | Added immutable record `(long rows, int partitions, long version)` returned by `StorageEngine.copyFile`. |
| [`StorageEngine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/engine/StorageEngine.java) | Converted `Catalog`, `Column`, `Partition`, and `Statistics` into immutable records with defensive copies; replaced `HashMap` with `ConcurrentHashMap`; added `snapshot(String)`, `commitCatalog(Catalog, Catalog)`, `readCatalogVersion(String, long)`, `pruneOldCatalogVersions(String)`, and `readPartition(Catalog, Partition)`; updated `loadCatalogs()` to load the highest numerical version per table; replaced whole-directory wipe on failed `COPY` with `discardWrittenPartitions` using a caller-owned `writtenFiles` collector. |
| [`Executor.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/executor/Executor.java) | Pins `engine.snapshot(tableName)` at the start of `executeStatement` and `executeQuery`; passes the pinned snapshot to `Binder`, `Planner`, and `StorageEngine.copyFile`; logs `version=` in `CREATE_TABLE`, `COPY`, and `SELECT` summary lines. |
| [`Binder.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/parser/Binder.java) | Added `bind(Statement, Catalog)` to validate against the pinned snapshot's schema without re-querying `StorageEngine`. |
| [`Planner.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/planner/Planner.java) | Updated `plan(SelectStatement, Catalog)` to plan and prune against `snapshot.partitions()` and record `snapshot.version()` in [`ScanStats`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/models/ScanStats.java). |
| [`ScanStats.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/models/ScanStats.java) | Added `long version` component. |
| [`ScanOperator.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/operators/ScanOperator.java) | Updated constructor to `ScanOperator(StorageEngine, Catalog, List<Integer>)` and reads partitions via `engine.readPartition(snapshot, partition)`. |

---

## 3. Verification Results

Executed `mvn clean compile` and `mvn -B verify`. Both unit tests (Surefire) and integration tests (Failsafe) passed with **0 failures and 0 errors**:

| Suite | Test Class | Tests Run | Failures | Errors |
|-------|------------|-----------|----------|--------|
| Surefire | `EngineTest` | 5 | 0 | 0 |
| Surefire | `SqlPrinterTest` | 7 | 0 | 0 |
| Surefire | `SqlParserTest` | 10 | 0 | 0 |
| Surefire | `StorageEngineTest` | 9 | 0 | 0 |
| Surefire | `ScanOperatorTest` | 2 | 0 | 0 |
| Surefire | `ProjectOperatorTest` | 2 | 0 | 0 |
| Surefire | `ExecutorTest` | 9 | 0 | 0 |
| Surefire | `PlannerTest` | 6 | 0 | 0 |
| Surefire | `FilterOperatorTest` | 1 | 0 | 0 |
| Surefire | `LogSanitizerTest` | 5 | 0 | 0 |
| Failsafe | `EngineIT` | 6 | 0 | 0 |
| Failsafe | `EngineLogIT` | 2 | 0 | 0 |
| Failsafe | `SqlCorpusIT` | 164 | 0 | 0 |
| Failsafe | `StorageEngineIT` | 17 | 0 | 0 |
| Failsafe | `BinderIT` | 6 | 0 | 0 |
| **Total** | **All Suites** | **248** | **0** | **0** |

---

## 4. Edge Cases & Invariants Tested

1. **Monotonic Versioning & Additive `COPY`** ([`StorageEngineIT.createCopyCopyProducesVersionsZeroThroughTwo`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/StorageEngineIT.java#L317-L333), [`EngineIT.repeatedCopyScriptReturnsRowsFromBothFiles`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/EngineIT.java#L154-L181)): Verified that `CREATE TABLE` produces `v0` and subsequent `COPY` statements produce `v1` and `v2`, preserving the byte contents and paths of earlier partitions ([`StorageEngineIT.secondCopyAppendsWithoutOverwritingExistingPartitions`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/StorageEngineIT.java#L336-L361)).
2. **Per-Version Catalog Files & Restart Recovery** ([`StorageEngineIT.eachCommitWritesItsOwnCatalogFile`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/StorageEngineIT.java#L364-L379), [`StorageEngineIT.freshEngineLoadsHighestCatalogVersion`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/StorageEngineIT.java#L382-L398)): Verified that `trips-v0.json`, `trips-v1.json`, and `trips-v2.json` are persisted with matching `"version"` fields, and a fresh `StorageEngine` loads the highest version while ignoring leftover `.tmp-*` files.
3. **Failed Second `COPY` Rollback** ([`StorageEngineIT.failedSecondCopyKeepsPreviousVersionAndPartitions`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/StorageEngineIT.java#L401-L429)): Verified that when a second `COPY` fails mid-stream after writing new partition files, only the newly written partition files are deleted while `v1` and its 4 partition files remain intact and readable.
4. **Catalog Version Retention (10 Versions)** ([`StorageEngineIT.retentionKeepsOnlyTenNewestCatalogVersions`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/StorageEngineIT.java#L432-L457)): Verified numerical version sorting (`Long::compare`) so that after 12 `COPY` commits (`v0`–`v12`), `v0`–`v2` are deleted, `v3`–`v12` are retained, and `readCatalogVersion("trips", 1)` logs an error and throws `IllegalStateException`.
5. **Snapshot Isolation & Stale-Base Guard** ([`ExecutorTest.pinnedSnapshotIsUnaffectedByLaterCommit`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/ExecutorTest.java#L186-L216), [`ExecutorTest.commitWithStaleBaseVersionThrowsIllegalStateException`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/ExecutorTest.java#L222-L234)): Verified that an operator plan built against `v1` returns only `v1`'s 8 rows even after `v2` commits 8 more rows, and attempting to commit against a stale base snapshot throws `IllegalStateException`.
6. **Versioned Summary Logging** ([`EngineLogIT.summaryLinesCarryVersion`](file:///workspaces/datsys-engine-SeMi/src/test/java/datasys/semi/EngineLogIT.java#L73-L115)): Verified that `logs/engine.log` records `version=0` for `CREATE_TABLE` and `version=1` for `COPY` and `SELECT`.

