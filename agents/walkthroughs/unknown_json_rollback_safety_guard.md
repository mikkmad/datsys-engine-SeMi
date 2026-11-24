# Walkthrough — Unknown JSON Rollback Safety Guard

Plan: [`agents/implementation_plans/unknown_json_rollback_safety_guard.md`](../implementation_plans/unknown_json_rollback_safety_guard.md)

## 1. Summary of Work Accomplished

The version 10 engine now loads catalogs that contain fields it does not know, such as the `ndv` statistic that
version 11 will add. Rolling back from version 11 to version 10 keeps every table readable and writable. The
unknown fields are dropped on the next commit, which was accepted because version 11 recomputes missing NDV on
roll forward.

## 2. Key Changes & Architecture

| File | Change |
|------|--------|
| `engine/StorageEngine.java` | `@JsonIgnoreProperties(ignoreUnknown = true)` on the four persisted records: `Catalog`, `Column`, `Partition`, `Statistics`. The `Catalog` Javadoc explains the rollback reason and that any new record nested in the catalog needs the same annotation. |
| `CatalogRollbackCompatibilityTest` *(new)* | Three tests over a catalog rewritten with unknown fields at every nesting level. |

The annotation goes on each record rather than on the shared `ObjectMapper`. That way the guarantee belongs to the
on-disk format, holds for any mapper that reads it, and leaves other JSON strict. A forgotten annotation on a
future nested record is caught by the tests, which inject an unknown field at every level.

## 3. Verification Results

Before the fix, all three new tests failed in the `StorageEngine` constructor:

```
[ERROR] Tests run: 3, Failures: 0, Errors: 3, Skipped: 0 -- in datasys.semi.CatalogRollbackCompatibilityTest
Caused by: com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException: Unrecognized field "futureField"
    (class datasys.semi.engine.StorageEngine$Column), not marked as ignorable
CatalogRollbackCompatibilityTest...:78 » IllegalState Could not open storage directory /tmp/junit-...
```

After the fix, `mvn clean compile` and `mvn -B verify`:

| Phase | Tests run | Failures | Errors | Skipped |
|-------|-----------|----------|--------|---------|
| Surefire (unit) | 149 | 0 | 0 | 0 |
| Failsafe (integration) | 205 | 0 | 0 | 0 |
| — of which `CatalogRollbackCompatibilityTest` | 3 | 0 | 0 | 0 |

`BUILD SUCCESS`

## 4. Edge Cases & Invariants Tested

| Test | Scenario |
|------|----------|
| `restartedEngineLoadsCatalogWithUnknownFieldsAtEveryLevel` | A restarted engine loads a catalog with `futureField` on the catalog, every column and every partition, plus `ndv` in every column statistic. The loaded catalog equals the original (version, schema, partitions, row counts, min/max) and all 8 rows are readable. |
| `readCatalogVersionIgnoresUnknownFields` | After a further commit, the historical version 1 file (with unknown fields) is read through `readCatalogVersion` and equals the original. |
| `copyAfterRollbackCommitsAndDropsUnknownFields` | A `COPY` after the rollback commits version 2 with 16 rows. The version 2 catalog file contains neither `ndv` nor `futureField` (accepted loss), while version 1 still does. |
