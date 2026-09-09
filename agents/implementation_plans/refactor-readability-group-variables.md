# Implementation Plan: Code Readability & Variable Grouping

## Motivation
Improve the readability, code organization, and visual hierarchy across all `.java` files in `src/main/` by grouping related fields/variables and introducing logical paragraph breaks (newlines) between conceptual blocks.

---

## Proposed Changes

### 1. [`src/main/java/datasys/semi/StorageEngine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/StorageEngine.java)
- **Field Grouping**:
  - Constants (`LOGGER`, `MAGIC`, `FORMAT_VERSION`, `DEFAULT_MAX_ROWS_PER_PARTITION`).
  - Directory paths (`dataDirectory`, `catalogsDirectory`, `dataFilesDirectory`).
  - Sizing, serialization, and catalog state (`maxRowsPerPartition`, `objectMapper`, `catalogs`).
  - Observable runtime metrics (`lastScanStats`).
- **Section Dividers & Newlines**:
  - Add descriptive section comments: Package-private test methods, storage & partition management, type parsing & serialization, predicate evaluation, validation & file operations, and inner data models.
  - Separate logical steps inside `copyFile`, `select`, `createTable`, `writePartition`, and `readMatchingRows` with clean blank lines.

### 2. [`src/main/java/datasys/semi/Engine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/Engine.java)
- Format `main()` with distinct visual blocks:
  - Session & logging initialization.
  - Demo directory cleanup and setup.
  - CSV resource resolution.
  - Table creation and ingestion.
  - Queries execution and formatted output.
  - Engine shutdown logging.

---

## Verification Plan
1. `mvn -B verify` to confirm unit and integration tests compile cleanly and pass with 0 failures.
2. `mvn compile exec:java` to ensure runnable application outputs are unaffected.

