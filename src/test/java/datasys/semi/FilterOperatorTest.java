package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import datasys.semi.models.BoundPredicate;
import datasys.semi.operators.FilterOperator;
import datasys.semi.operators.Operator;
import datasys.semi.schema.Comparison;

/**
 * Exercise 4 §6 test 1: FilterOperator over a stub child.
 */
class FilterOperatorTest {

    // --- Golden Rows (city, distance, price) ---
    private static final List<Object[]> ROWS = List.of(
            new Object[] { "Copenhagen", 12L, 23.5 },
            new Object[] { "Aarhus", 187L, 301.0 },
            new Object[] { "Odense", 95L, 120.75 },
            new Object[] { "Copenhagen", 140L, 210.0 });

    @Test
    void emitsOnlyTheRowsPassingThePredicate() {
        Operator filter = new FilterOperator(
                new TestListOperator(ROWS),
                new BoundPredicate(1, Comparison.GREATER_THAN, 100L));

        List<Object[]> emitted = new ArrayList<>();
        filter.open();
        Object[] row;
        while ((row = filter.next()) != null) {
            emitted.add(row);
        }
        filter.close();

        assertEquals(2, emitted.size());
        assertArrayEquals(new Object[] { "Aarhus", 187L, 301.0 }, emitted.get(0));
        assertArrayEquals(new Object[] { "Copenhagen", 140L, 210.0 }, emitted.get(1));
    }
}
