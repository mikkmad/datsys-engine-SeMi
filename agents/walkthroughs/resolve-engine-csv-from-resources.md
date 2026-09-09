# Walkthrough: Read CSV from Resources in Engine.java

## Overview
Updated [`Engine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/Engine.java) to load the existing dataset from [`src/main/resources/trips.csv`](file:///workspaces/datsys-engine-SeMi/src/main/resources/trips.csv) instead of writing a hardcoded string to `semi-demo/trips.csv`.

---

## Changes Made

### [`Engine.java`](file:///workspaces/datsys-engine-SeMi/src/main/java/datasys/semi/Engine.java)

1. **Replaced Hardcoded CSV String**:
   - Removed lines that wrote `semi-demo/trips.csv` via `Files.writeString(...)`.
   - Replaced with `Path csvFile = resolveCsvFile();`.

2. **Added `resolveCsvFile()` Helper**:
   ```java
   private static Path resolveCsvFile() throws Exception {
       Path directPath = Path.of("src", "main", "resources", "trips.csv");
       if (Files.exists(directPath)) {
           return directPath;
       }
       var resource = Engine.class.getResource("/trips.csv");
       if (resource != null) {
           return Path.of(resource.toURI());
       }
       throw new IllegalStateException("Could not locate src/main/resources/trips.csv");
   }
   ```

---

## Verification Results

### 1. `mvn -B verify`
```text
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 (Unit Tests)
...
[INFO] Running datasys.semi.StorageEngineIT
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 (Integration Tests)
[INFO] BUILD SUCCESS
```

### 2. `mvn compile exec:java`
Log line confirms reading directly from `src/main/resources/trips.csv`:
```text
12:39:31.189 DEBUG StorageEngine - api=copyFile table=trips file=src/main/resources/trips.csv
...
distance > 100: [[Aarhus, 187, 301.0], [Copenhagen, 140, 210.0], [Aalborg, 210, 340.5], [Esbjerg, 299, 450.25]]
city = Copenhagen: [[Copenhagen, 12, 23.5], [Copenhagen, 140, 210.0], [Copenhagen, 88, 99.99]]
price < 50.0: [[Copenhagen, 12, 23.5], [Roskilde, 31, 45.0]]
12:39:31.401 DEBUG Engine - engine stopped
[INFO] BUILD SUCCESS
```

