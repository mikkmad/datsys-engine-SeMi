# Storage Design


## Catalog storage: 
* One catalog file per table
* Catalog format: JSON
* Catalog files are placed in a folder called "catalogs" in the root directory, i.e. `./catalogs`.
* Data files are placed in a folder called "data" in the root directory, i.e. `./data`.
* Root directory is the folder wherein the engine lives. I.e., if the engine lives in `~/tmp/SeMi`, then the catalogs and data folders will be in `~/tmp/SeMi/catalogs` and `~/tmp/SeMi/data`, respectively.

## Catalog contents: 
* per table, at least the schema and the list of data files and partitions that belong to it.


## Where the min/max summaries live. The requirement is only that they exist per column per partition and that select can consult them without reading the column data they describe. 

Three designs are defensible:
- **A footer after the data** is Parquet's choice and is natural for a single-pass writer. 
- **A header at the front** is convenient for the reader, but the writer must buffer the partition or seek back to fill it in. 
- **In the catalog only** means that pruning needs no data-file I/O at all, as in Snowflake and Iceberg, but a data file is then no longer self-describing. Pick one and justify it.

## ## Restart: what does a fresh StorageEngine on the same directory have to read before it can
answer a select ?

## Layout inside a partition: choose either row-wise or columnar format.

## # Partition size: maximum rows per partition, as a configurable parameter (your tests will use tiny
values like 2; pick a sensible default).

## # Value encodings and framing: e.g. LONG as 8-byte two's-complement, DOUBLE as 8-byte IEEE
754, STRING as length-prefixed ASCII bytes; magic bytes and a format version number at the start
of each file; how a reader finds a given partition's column chunk.

## # Byte order: ByteBuffer defaults to big-endian, while the machines you run on are little-endian.
Pick one and document the choice. 