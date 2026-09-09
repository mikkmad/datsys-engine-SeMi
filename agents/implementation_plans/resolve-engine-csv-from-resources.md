# Implementation Plan: Read CSV from Resources in Engine.java

## Motivation
Previously, [`Engine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/Engine.java) hardcoded the golden CSV lines as a multiline string and wrote them to `semi-demo/trips.csv` on each run. Since the golden dataset is already provided as an existing resource file at [`src/main/resources/trips.csv`](file:///workspaces/datsys-engine-SeMi/src/main/resources/trips.csv), `Engine.java` should resolve and read the existing file directly rather than regenerating it.

---

## Proposed Changes

### [`Engine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/Engine.java)
1. **Remove hardcoded CSV string and file creation**:
   - Remove `Files.writeString(csvFile, "Copenhagen,12,23.5\n...")`.
2. **Add `resolveCsvFile()`**:
   - Check direct filesystem path `Path.of("src", "main", "resources", "trips.csv")`.
   - Fall back to classpath resource lookup `Engine.class.getResource("/trips.csv")` converting `URL` to `Path` via `.toURI()`.
   - Pass the resolved path directly to `storage.copyFile("trips", csvFile.toString())`.

---

## Verification Plan

1. **Unit & Integration Tests**:
   - Run `mvn -B verify` to ensure project compilation and all test suites pass.
2. **Golden Demo Execution**:
   - Run `mvn compile exec:java` and confirm `StorageEngine.copyFile` logs reading directly from `src/main/resources/trips.csv`.
   - Confirm all three query outputs match expected golden results.

