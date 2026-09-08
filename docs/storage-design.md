# Storage Design

- Version: 1.0
- Date: 08/09/2026

## Catalog storage:

- One catalog file per table
- Catalog format: JSON
- Catalog files are placed in a folder called "catalogs" in the root directory, i.e. `./catalogs`.
- Data files are placed in a folder named after the table. That folder is placed in the "data" folder in the root directory, i.e. `./data`.
- Root directory is the folder wherein the engine lives. I.e., if the engine lives in `~/tmp/SeMi`, then the catalogs and data folders will be in `~/tmp/SeMi/catalogs` and `~/tmp/SeMi/data`, respectively.

Example of a Catalog file for a table named `users`:
```
{
  "table": "users",
  "schema": [
    {
      "column_name": "id",
      "column_type": "LONG"
    },
    {
      "column_name": "name",
      "column_type": "STRING"
    },
    {
      "column_name": "age",
      "column_type": "LONG"
    }
  ],
  "partitions": [
    {
      "path": "data/users/partition_0.dat",
      "rowCount": 1000,
      "statistics": {
        "id": {
          "min": 1,
          "max": 1000
        },
        "age": {
          "min": 18,
          "max": 65
        }
      }
    }
  ]
}
```

## Catalog contents:

- Should contain the schema of the table.
- Should contain the relative path for the `.dat` file that contain the data for each partition. I.e. there is a `.dat` file for each partition
- Should contain the statistics for all the partitions of that table (note: for much larger codebases, these should be placed elsewhere, e.g., in a separate statistics file).
- Statistics consists of:
  - min/max summaries per column per partition

Directory and file-naming example:
```txt
<dataDirectory>/
  catalogs/
    users.json
  data/
    users/
      partition-0.dat
      partition-1.dat
```

### Justification for placing statistics in the catalog

Statistics are placed **in the catalog only**. This was chosen because it allows for pruning without reading the data files, which is important for performance. It may introduce performance hits when the catalog scales to a large number of partitions, but this is not a concern for the current implementation.

## Restart: what does a fresh StorageEngine on the same directory have to read before it can answer a `select` query?

- The DBMS need to read the relevant catalog files based on which table the `select` query is asking for, and then read the relevant data files based on which partitions are needed to answer the query.

## On Failure:
- If parsing or writing fails, no new partition files are registered and the catalog remains unchanged.

## Layout inside a partition:

- Will have a row-wise format.

## Partition size

_OBS: Partition size **must be** a configurable parameter_.

Default is: 16 MB (Subject to change.)

## Value encodings and framing:

- `LONG` as 8-byte two's-complement
- `DOUBLE` as 8-byte IEEE754
- `STRING` as 4-byte length prefix followed by UTF-8 bytes
- Each partition file begins with a fixed-size header consisting of:
  - Magic bytes: "SEMI" (0x53 0x45 0x4d 0x49)
  - 2-byte format version (`uint16`), 
  - 4-byte row count (`uint32`),
  - 2-byte column count (`uint16`)
- A reader locates row data by seeking past the fixed-size header then reading rows sequentially in schema-defined column order.
- No column chunck - since we are making a row store

Example:
```txt
Header:
4 bytes  magic: SEMI
2 bytes  version: 1, unsigned big-endian
4 bytes  row count, unsigned big-endian
2 bytes  column count, unsigned big-endian

Each LONG:
8 bytes  two's-complement signed integer, big-endian

Each DOUBLE:
8 bytes  IEEE 754, big-endian

Each STRING:
4 bytes  byte length, unsigned big-endian
N bytes  UTF-8 payload
```

## Byte order: ByteBuffer defaults to big-endian, while the machines you run on are little-endian.

We have chosen to go with Big-Endian as it is Java's Default. It is convenient to use, as we will not have to explicitly state the byte order when using ByteBuffer, as it defaults to Big-Endian.
