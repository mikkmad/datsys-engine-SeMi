package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import datasys.semi.schema.ColumnResolver;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;
import datasys.semi.schema.ResolvedColumn;
import datasys.semi.schema.TableSchema;

/**
 * Exercise 10 Required Test 3: the column resolution helper maps an
 * unqualified column name to its one owning table and to its index in the
 * joined row (the concatenation of the FROM-list schemas), and rejects unknown
 * and ambiguous names.
 */
class ColumnResolverTest {

    // --- Testbed Schemas (Exercise 10 section 5) ---
    private static final TableSchema ORDERS = new TableSchema("orders", List.of(
            new ColumnSpec("o_orderkey", ColumnType.LONG),
            new ColumnSpec("o_custname", ColumnType.STRING),
            new ColumnSpec("o_prodname", ColumnType.STRING),
            new ColumnSpec("o_quantity", ColumnType.LONG)));
    private static final TableSchema CUSTOMERS = new TableSchema("customers", List.of(
            new ColumnSpec("c_name", ColumnType.STRING),
            new ColumnSpec("c_city", ColumnType.STRING)));
    private static final TableSchema PRODUCTS = new TableSchema("products", List.of(
            new ColumnSpec("p_name", ColumnType.STRING),
            new ColumnSpec("p_price", ColumnType.DOUBLE)));
    private static final TableSchema CITIES = new TableSchema("cities", List.of(
            new ColumnSpec("ct_city", ColumnType.STRING),
            new ColumnSpec("ct_country", ColumnType.STRING),
            new ColumnSpec("ct_population", ColumnType.LONG)));

    // --- Resolver Under Test ---
    private final ColumnResolver resolver = new ColumnResolver(List.of(ORDERS, CUSTOMERS, PRODUCTS, CITIES));

    /**
     * Verifies table, table position, in-table index and joined-row index for
     * columns at the start, middle and end of each table.
     */
    @Test
    void resolvesNameToOwningTableAndJoinedRowIndex() {
        assertResolved("o_orderkey", "orders", 0, 0, 0);
        assertResolved("o_quantity", "orders", 0, 3, 3);
        assertResolved("c_name", "customers", 1, 0, 4);
        assertResolved("c_city", "customers", 1, 1, 5);
        assertResolved("p_price", "products", 2, 1, 7);
        assertResolved("ct_city", "cities", 3, 0, 8);
        assertResolved("ct_population", "cities", 3, 2, 10);
    }

    /**
     * Verifies the resolved column carries the column's name and type.
     */
    @Test
    void resolvedColumnCarriesNameAndType() {
        ResolvedColumn price = resolver.resolve("p_price");

        assertEquals(new ColumnSpec("p_price", ColumnType.DOUBLE), price.column());
        assertEquals(ColumnType.DOUBLE, price.type());
    }

    /**
     * Verifies the joined row width is the sum of the table widths.
     */
    @Test
    void joinedWidthIsSumOfTableWidths() {
        assertEquals(11, resolver.joinedWidth());
        assertEquals(4, new ColumnResolver(List.of(ORDERS)).joinedWidth());
    }

    /**
     * Verifies that positions follow the FROM-list order, not the order in
     * which tables were created.
     */
    @Test
    void positionsFollowFromListOrder() {
        ColumnResolver reordered = new ColumnResolver(List.of(CITIES, ORDERS));

        ResolvedColumn custname = reordered.resolve("o_custname");
        assertEquals(1, custname.tablePosition());
        assertEquals(1, custname.columnIndexInTable());
        assertEquals(4, custname.joinedIndex());
        assertEquals(0, reordered.resolve("ct_city").joinedIndex());
    }

    /**
     * Verifies that a name found in no listed table is an unknown column,
     * including a name owned by a table that is not in the FROM list.
     */
    @Test
    void unknownColumnThrows() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("missing"));
        assertEquals("unknown column: missing", missing.getMessage());

        ColumnResolver ordersOnly = new ColumnResolver(List.of(ORDERS));
        assertThrows(IllegalArgumentException.class, () -> ordersOnly.resolve("c_name"));
    }

    /**
     * Verifies that a name found in two or more tables is ambiguous and the
     * message names every owner, while unshared names still resolve.
     */
    @Test
    void ambiguousColumnThrowsAndNamesOwners() {
        TableSchema left = new TableSchema("left_t", List.of(
                new ColumnSpec("id", ColumnType.LONG), new ColumnSpec("l_x", ColumnType.STRING)));
        TableSchema right = new TableSchema("right_t", List.of(new ColumnSpec("id", ColumnType.LONG)));
        ColumnResolver clashing = new ColumnResolver(List.of(left, right));

        IllegalArgumentException ambiguous = assertThrows(IllegalArgumentException.class,
                () -> clashing.resolve("id"));
        assertEquals("ambiguous column: id (in left_t, right_t)", ambiguous.getMessage());
        assertEquals(1, clashing.resolve("l_x").joinedIndex());
    }

    /**
     * Verifies that invalid FROM lists are rejected when building the resolver.
     */
    @Test
    void invalidFromListsAreRejected() {
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> new ColumnResolver(List.of(ORDERS, CUSTOMERS, ORDERS)));
        assertEquals("duplicate table in FROM list: orders", duplicate.getMessage());

        assertThrows(IllegalArgumentException.class, () -> new ColumnResolver(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ColumnResolver(null));

        List<TableSchema> withNull = new ArrayList<>();
        withNull.add(ORDERS);
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> new ColumnResolver(withNull));
    }

    /**
     * Verifies the helper records reject invalid arguments.
     */
    @Test
    void helperRecordsRejectInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new TableSchema(" ", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TableSchema("t", null));
        ColumnSpec column = new ColumnSpec("x", ColumnType.LONG);
        assertThrows(IllegalArgumentException.class, () -> new ResolvedColumn("t", -1, 0, 0, column));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedColumn("t", 0, 2, 1, column));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedColumn("t", 0, 0, 0, null));
    }

    /**
     * Asserts the resolution of one column name.
     *
     * @param columnName         name to resolve
     * @param tableName          expected owning table
     * @param tablePosition      expected FROM-list position of the owner
     * @param columnIndexInTable expected index in the owner's schema
     * @param joinedIndex        expected index in the joined row
     */
    private void assertResolved(String columnName, String tableName, int tablePosition, int columnIndexInTable,
            int joinedIndex) {
        ResolvedColumn resolved = resolver.resolve(columnName);
        assertEquals(tableName, resolved.tableName(), columnName);
        assertEquals(tablePosition, resolved.tablePosition(), columnName);
        assertEquals(columnIndexInTable, resolved.columnIndexInTable(), columnName);
        assertEquals(joinedIndex, resolved.joinedIndex(), columnName);
    }
}
