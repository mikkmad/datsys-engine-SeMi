package datasys.semi;

import java.nio.file.Path;
import java.util.List;

// TODO: Implement the StorageEngine class. Names may differ, but signatures should not!
public final class StorageEngine {
    /** All persistent state (catalog + data files) lives under this directory */
    public StorageEngine(Path dataDirectory) {
        /* ... */
    }

    public void createTable(String tableName, List<ColumnSpec> columns) {
        /* ... */
    }

    public void copyFile(String tableName, String csvFilePath) {
        /* ... */
    }

    public List<Object[]> select(String tableName, String columnName, Comparison comparison, Object constant) {
        /* ... */

        // return null to satisfy the compiler, until method is implemented.
        return null;

    }
}
