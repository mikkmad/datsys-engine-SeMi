# Storage Design

## Catalog storage:

- One catalog file per table
- Catalog format: JSON
- Catalog files are placed in a folder called "catalogs" in the root directory, i.e. `./catalogs`.
- Data files are placed in a folder named after the table. That folder is placed in the "data" folder in the root directory, i.e. `./data`.
- Root directory is the folder wherein the engine lives. I.e., if the engine lives in `~/tmp/SeMi`, then the catalogs and data folders will be in `~/tmp/SeMi/catalogs` and `~/tmp/SeMi/data`, respectively.

## Catalog contents:

- Should contain the schema of the table.
- Should contain the relative path for the `.dat` file that contain the data for each partition. I.e. there is a `.dat` file for each partition
- Should contain the statistics for all the partitions of that table (note: for much larger codebases, these should be placed elsewhere, e.g., in a separate statistics file).
- Statistics consists of:
  - min/max summaries per column per partition

### Justification for placing statistics in the catalog

Statistics are placed **in the catalog only**. This was chosen because it allows for pruning without reading the data files, which is important for performance. It may introduce performance hits when the catalog scales to a large number of partitions, but this is not a concern for the current implementation.

## Restart: what does a fresh StorageEngine on the same directory have to read before it can answer a `select` query?

- The DBMS need to read the relevant catalog files based on which table the `select` query is asking for, and then read the relevant data files based on which partitions are needed to answer the query.

## Layout inside a partition:

- Will have a row-wise format.

## Partition size: maximum rows per partition.

_OBS: Partition size **must be** a configurable parameter_.

Default is: 16 MB (Subject to change.)

## Value encodings and framing:

- `LONG` as 8-byte two's-complement
- `DOUBLE` as 8-byte IEEE754
- `STRING` as length-prefixed ASCII bytes
- Each partition file begins with a fixed-size header consisting of:
  - Magic bytes: "SEMI" (0x53 0x45 0x4d 0x49)
  - 2-byte format version (`uint16`), 
  - 4-byte row count (`uint32`),
  - 2-byte column count (`uint16`)
- A reader locates row data by seeking past the fixed-size header then reading rows sequentially in schema-defined column order.
- No column chunck - since we are making a row store

## Byte order: ByteBuffer defaults to big-endian, while the machines you run on are little-endian.

// TODO: Fix this. 
Pick one and document the choice.
