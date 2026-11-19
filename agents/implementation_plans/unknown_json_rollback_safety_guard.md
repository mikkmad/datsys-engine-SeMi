# Implementation Plan — Unknown JSON Rollback Safety Guard

## 1. Sub-Exercise Reference

- Design decision: [`docs/optimizer-design.md`](../../docs/optimizer-design.md) §"Roll forward" (rolling back)
- Related: [`docs/ndv-merge-options.md`](../../docs/ndv-merge-options.md) (the NDV statistic that version 11 adds)
- No exercise description exists yet for Exercise 11; this is a retrofit to the version 10 engine ahead of it.

## 2. Background & Objective

Version 11 adds an NDV (number of distinct values) field to the per-partition, per-column statistics in the
catalog JSON. The version 10 engine reads catalogs with a default Jackson `ObjectMapper`, which fails on unknown
properties (`UnrecognizedPropertyException`). After a rollback from version 11 to version 10, every catalog
written by version 11 would fail to load: the `StorageEngine` constructor wraps the failure in an
`IllegalStateException` and the engine cannot open its storage directory.

**Objective:** make the version 10 engine tolerate unknown fields in the persisted catalog format, so a rollback
keeps every table usable. The NDV values are lost on the next commit (accepted; version 11 recomputes missing
NDV on roll forward).

### Decision: per-record annotation, not a global mapper setting

`@JsonIgnoreProperties(ignoreUnknown = true)` goes on each persisted record instead of disabling
`FAIL_ON_UNKNOWN_PROPERTIES` on the `ObjectMapper`:

- The guarantee belongs to the on-disk format and holds for any mapper that reads it.
- It is explicit at the record and scoped to the persisted catalog types.
- The cost (remembering to annotate a new nested record) is covered by tests that inject an unknown field at
  every nesting level.

## 3. Requirements & Invariants

### Functional
| ID | Requirement |
|----|-------------|
| F1 | A catalog file with unknown fields at the catalog, column, partition or statistics level loads at engine startup without an exception. |
| F2 | `readCatalogVersion` reads a historical catalog version that contains unknown fields. |
| F3 | Known fields (version, schema, partitions, row counts, min/max) are read unchanged; partition data stays readable. |
| F4 | A `COPY` after the rollback commits the next version; the new catalog file no longer contains the unknown fields (accepted data loss). |

### Invariants
- **I1** Every record persisted in a catalog file (`Catalog`, `Column`, `Partition`, `Statistics`) carries
  `@JsonIgnoreProperties(ignoreUnknown = true)`.
- **I2** No behaviour change for catalogs without unknown fields; serialization output is unchanged.

## 4. Proposed Changes

| File | Change |
|------|--------|
| `engine/StorageEngine.java` | Add `@JsonIgnoreProperties(ignoreUnknown = true)` to `Catalog`, `Column`, `Partition`, `Statistics`; Javadoc notes the rollback reason. |
| `CatalogRollbackCompatibilityTest` *(new)* | Writes a table with version 10, injects unknown fields (including `ndv` in statistics) into the catalog JSON at every level with Jackson's tree model, reopens the engine and checks F1–F4. |
## 5. Verification Plan

```bash
mvn clean compile
mvn -B verify
```

Expected: the new tests fail before the annotations are added (the constructor throws
`IllegalStateException` caused by `UnrecognizedPropertyException`) and pass afterwards; `mvn -B verify` reports
0 failures and 0 errors.
