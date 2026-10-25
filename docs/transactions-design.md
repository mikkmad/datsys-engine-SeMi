# 1. Versioning
> Each table gets a version number that a committed `COPY` or `DELETE` increments. Where does the counter live, and what exactly is a version in your metadata: one rewritten catalog file, one catalog file per version, or metadata held in memory only and flushed when the engine exits? The in-memory variant keeps all live versions of all tables in data structures that are safe under concurrent modification. It is the cheapest to commit and the one that loses the most if the engine crashes.

**Choice**: One catalog file per version. The catalog filename is `<table>-v<version>.json`. The catalog file also holds the version number. The counter lives in memory at the Catalog instance, and is persisted in the JSON file's "version" field.

**Reason**: Keeping one catalog file per version is the safest option; if the engine crashes, the last committed version is still on disk, and the engine can recover by reading the latest catalog file. The in-memory version number is for keeping track of the version counter to avoid reparsing JSON from disk on every statement.

# 2. Commit Atomicity
> A commit swaps the table's partition list and increments the version, atomically. Atomic how, in one JVM and on disk? (Writing a temporary file and renaming it over `catalog.json` is atomic on POSIX; an in-memory swap is one reference assignment.)

**Choice**: On disk, `StorageEngine.moveAtomically` is used to atomically rename a temporary file to the new `<table>-v<version>.json` catalog file. In memory, a single reference assignment replaces the Catalog instance in catalogs, swapping both the partition list and the incremented version together.

**Reason**: Writing to a temporary file first and using `StorageEngine.moveAtomically` (Files.move with ATOMIC_MOVE) ensures that `<table>-v<version>.json` only becomes visible on disk once it is completely written. In memory, since the `Catalog` object holds both the partition list and the version number, replacing the `Catalog` reference in the map makes both changes visible at the exact same instant.

# 3. Snapshot
> A statement pins the version it reads when it starts and never re-reads metadata. What exactly does it pin: a reference to a catalog version, or its own copy of the partition list with offsets and counts? And when: at statement start, or when the plan opens?

**Choice**: A statement pins a reference to the table's current immutable `Catalog` version at statement start, before binding and planning, and passes that snapshot through planning and execution without ever re-reading metadata from `StorageEngine`.

**Reason**: `Catalog` instances and partition files are immutable. Therefore, holding a reference to the `Catalog` version guarantees that the table state seen by a running `SELECT`, `DELETE`, or `COPY` cannot change mid-statement even if a newer version is committed concurrently.

# 4. Retention
> How many versions back does the engine keep? A version must not be dropped while a `SELECT` or a `DELETE` still reads it, so how does the engine know that no statement still uses it? Reference counting, a list of running statements, and a fixed number of retained versions are all defensible. Write down which one you pick, and what a statement sees when it asks for a version that is gone.

**Choice**: The engine keeps a fixed number of versions (10), and tracks the number of running readers for each version. Should a reader attempt to read a version that has been dropped, it will throw `IllegalStateException` with a message indicating that the version is no longer available.

**Reason**: Keeping a fixed number of versions allows the engine to reclaim disk space while still supporting concurrent readers. Reference counting ensures that a version is not dropped while actively in use, and throwing `IllegalStateException` over `IllegalArgumentException` makes it clear that an illegal state was reached by accessing a version that no longer exists. We went with 10 to provide a "buffer" between currently used versions and unused, old versions, such that it is only in rare cases with extremely slow statement executions, that an old version is still in use.

# 5. The Rewrite Set
> `DELETE` must rewrite exactly the partitions that may contain matches, which is the complement of week 4's pruning decision. Where does the shared helper live so that the planner and `DELETE` decide from the same code? And what happens to a partition that may contain a match but turns out to contain none: do you write an identical copy, or carry the original over?

**Choice**: The shared helper lives in the `Planner` class, which is responsible for both pruning and determining the rewrite set. Currently, the method is private; it must be made public so that `DELETE` can call it. If a partition may contain a match but turns out to contain none, we carry over the existing `Partition` entry into the new catalog without writing a new partition file.

**Reason**: Keeping the shared helper in the `Planner` class ensures **Single Responsibility Principle**. `StorageEngine`, as an example, should not be doing "planning" work. Lastly, by carrying over the original partition, we avoid unnecessary I/O and disk writes.

# 6. New Version
> Does a `DELETE` that matches nothing still create a new version?

**Choice**: Yes, a `DELETE` that matches nothing still creates a new version.

**Reason**: Creating a new version ensures that the versioning system remains consistent and predictable, and provides consistency with the logging mechanism. Furthermore, it aligns with Exercise 7 §2, which states that "Every committed `COPY` or `DELETE` builds a new partition list, increments the version, and commits both with one atomic swap."

# 7. Partition Placement
> Do new partitions go at the end of the table's single data file, or into a file of their own? Either answer can be defended; write yours down.

**Choice**: New partitions are written into a file of their own under `data/<table>/`, named `partition-<uuid>.dat`. 

**Reason**: Storing each partition in its own immutable `.dat` file aligns with our Part 1 storage design. It allows untouched partitions to be carried over across versions simply by retaining their path in the new catalog, and allows obsolete or orphaned partitions to be reclaimed via a simple file deletion without compacting a monolithic data file. Furthermore, any partition not listed in any `<table>-v<version>.json` catalog file is considered an orphan and may safely be deleted. 

To accomplish this, we change the naming scheme of partition files from `partition-<n>.dat` to `partition-<uuid>.dat`, where `<uuid>` is a randomly generated UUID. This ensures that each partition file has a unique name, preventing collisions and overwrites, without the need for a global counter.