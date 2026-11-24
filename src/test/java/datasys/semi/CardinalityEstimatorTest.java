package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import datasys.semi.engine.StorageEngine.Partition;
import datasys.semi.engine.StorageEngine.Statistics;
import datasys.semi.models.Predicate;
import datasys.semi.optimizer.CardinalityEstimator;
import datasys.semi.schema.ColumnSpec;
import datasys.semi.schema.ColumnType;
import datasys.semi.schema.Comparison;

/**
 * Tests the cardinality estimator on hand-made catalog statistics, without data
 * files (Exercise 11 §3, required test §8.2).
 */
class CardinalityEstimatorTest {

    // --- Constants ---
    private static final double TOLERANCE = 1e-9;
    private static final ColumnSpec DISTANCE = new ColumnSpec("distance", ColumnType.LONG);
    private static final ColumnSpec FARE = new ColumnSpec("fare", ColumnType.DOUBLE);
    private static final ColumnSpec CITY = new ColumnSpec("city", ColumnType.STRING);
    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(CITY, DISTANCE, FARE);

    /**
     * Builds a partition with statistics for {@code city} and {@code distance}.
     *
     * @param rowCount       rows in the partition
     * @param cityNdv        NDV of {@code city}
     * @param minDistance    smallest {@code distance}
     * @param maxDistance    largest {@code distance}
     * @param distanceNdv    NDV of {@code distance}
     * @return the partition descriptor
     */
    private static Partition tripsPartition(int rowCount, long cityNdv, long minDistance, long maxDistance,
            long distanceNdv) {
        return new Partition("trips/p.bin", rowCount, Map.of(
                "city", new Statistics("Aarhus", "Odense", cityNdv),
                "distance", new Statistics(Long.toString(minDistance), Long.toString(maxDistance), distanceNdv)));
    }

    /**
     * Verifies that the base table estimate is the sum of the surviving
     * partitions' row counts.
     */
    @Test
    void baseTableIsSumOfSurvivingPartitionRows() {
        List<Partition> surviving = List.of(tripsPartition(100, 5, 0, 10, 10), tripsPartition(250, 5, 0, 10, 10));

        assertEquals(350L, CardinalityEstimator.baseTableRows(surviving));
    }

    /**
     * Verifies that a table where every partition is pruned is estimated at 0 rows.
     */
    @Test
    void baseTableWithoutSurvivingPartitionsIsZero() {
        assertEquals(0L, CardinalityEstimator.baseTableRows(List.of()));
    }

    /**
     * Verifies that column NDV is the merged NDV of the surviving partitions only.
     */
    @Test
    void columnNdvMergesSurvivingPartitionNdvs() {
        List<Partition> surviving = List.of(tripsPartition(100, 4, 0, 10, 10), tripsPartition(100, 6, 0, 10, 10));

        assertEquals(10L, CardinalityEstimator.columnNdv(surviving, "city"));
    }

    /**
     * Verifies that a partition written before NDV was collected counts with its
     * row count.
     */
    @Test
    void missingNdvCountsAsRowCount() {
        Partition legacy = new Partition("trips/old.bin", 40, Map.of("city", new Statistics("A", "Z", null)));

        assertEquals(40L + 6L,
                CardinalityEstimator.columnNdv(List.of(legacy, tripsPartition(100, 6, 0, 10, 10)), "city"));
    }

    /**
     * Verifies the equality filter {@code |R| / ndv(col)}: 400 rows with 20
     * distinct cities keep 20 rows.
     */
    @Test
    void equalityFilterKeepsRowsDividedByNdv() {
        List<Partition> surviving = List.of(tripsPartition(200, 10, 0, 10, 10), tripsPartition(200, 10, 0, 10, 10));
        List<Predicate> filters = List.of(new Predicate("city", Comparison.EQUALS, "Aarhus"));

        assertEquals(20.0, CardinalityEstimator.filteredTableRows(surviving, TRIPS_SCHEMA, filters), TOLERANCE);
    }

    /**
     * Verifies that {@code <} interpolates between min and max: distance in
     * [0, 100], {@code distance < 25} keeps a quarter.
     */
    @Test
    void lessThanInterpolatesBetweenMinAndMax() {
        List<Partition> surviving = List.of(tripsPartition(200, 5, 0, 100, 100));
        List<Predicate> filters = List.of(new Predicate("distance", Comparison.LESS_THAN, 25L));

        assertEquals(50.0, CardinalityEstimator.filteredTableRows(surviving, TRIPS_SCHEMA, filters), TOLERANCE);
    }

    /**
     * Verifies that {@code >} interpolates from the top: distance in [0, 100],
     * {@code distance > 25} keeps three quarters.
     */
    @Test
    void greaterThanInterpolatesBetweenMinAndMax() {
        List<Partition> surviving = List.of(tripsPartition(200, 5, 0, 100, 100));
        List<Predicate> filters = List.of(new Predicate("distance", Comparison.GREATER_THAN, 25L));

        assertEquals(150.0, CardinalityEstimator.filteredTableRows(surviving, TRIPS_SCHEMA, filters), TOLERANCE);
    }

    /**
     * Verifies that interpolation uses each partition's own min and max: the
     * first partition lies entirely below 50, the second is cut in half.
     */
    @Test
    void rangeFilterInterpolatesPerPartition() {
        List<Partition> surviving = List.of(tripsPartition(100, 5, 0, 40, 40), tripsPartition(100, 5, 40, 60, 20));
        List<Predicate> filters = List.of(new Predicate("distance", Comparison.LESS_THAN, 50L));

        assertEquals(100.0 + 50.0,
                CardinalityEstimator.filteredTableRows(surviving, TRIPS_SCHEMA, filters), TOLERANCE);
    }

    /**
     * Verifies that a partition holding a single value keeps all or nothing.
     */
    @Test
    void rangeFilterOnSingleValuePartitionKeepsAllOrNothing() {
        List<Partition> surviving = List.of(tripsPartition(100, 5, 7, 7, 1));

        assertEquals(1.0, CardinalityEstimator.filterSelectivity(surviving, DISTANCE, Comparison.LESS_THAN, 8L),
                TOLERANCE);
        assertEquals(0.0, CardinalityEstimator.filterSelectivity(surviving, DISTANCE, Comparison.LESS_THAN, 7L),
                TOLERANCE);
    }

    /**
     * Verifies that a constant outside a partition's range clamps to 0 or 1.
     */
    @Test
    void rangeFractionIsClampedToZeroAndOne() {
        List<Partition> surviving = List.of(tripsPartition(100, 5, 10, 20, 10));

        assertEquals(1.0, CardinalityEstimator.filterSelectivity(surviving, DISTANCE, Comparison.LESS_THAN, 99L),
                TOLERANCE);
        assertEquals(0.0, CardinalityEstimator.filterSelectivity(surviving, DISTANCE, Comparison.GREATER_THAN, 99L),
                TOLERANCE);
    }

    /**
     * Verifies that a {@code DOUBLE} column interpolates like a {@code LONG}
     * column.
     */
    @Test
    void doubleColumnInterpolates() {
        Partition partition = new Partition("trips/p.bin", 100, Map.of("fare", new Statistics("0.0", "8.0", 50L)));

        assertEquals(0.75, CardinalityEstimator.filterSelectivity(List.of(partition), FARE,
                Comparison.GREATER_THAN, 2.0), TOLERANCE);
    }

    /**
     * Verifies that a range filter on a {@code STRING} column uses Selinger's
     * magic constant 1/3.
     */
    @Test
    void stringRangeFilterUsesSelingerConstant() {
        List<Partition> surviving = List.of(tripsPartition(300, 5, 0, 10, 10));
        List<Predicate> filters = List.of(new Predicate("city", Comparison.LESS_THAN, "M"));

        assertEquals(100.0, CardinalityEstimator.filteredTableRows(surviving, TRIPS_SCHEMA, filters), TOLERANCE);
    }

    /**
     * Verifies that a partition without statistics for the column uses 1/3 for a
     * range filter and its row count as NDV for an equality filter.
     */
    @Test
    void partitionWithoutColumnStatisticsFallsBack() {
        List<Partition> surviving = List.of(new Partition("trips/p.bin", 30, Map.of()));

        assertEquals(CardinalityEstimator.SELINGER_RANGE_SELECTIVITY,
                CardinalityEstimator.filterSelectivity(surviving, DISTANCE, Comparison.GREATER_THAN, 1L), TOLERANCE);
        assertEquals(1.0 / 30.0,
                CardinalityEstimator.filterSelectivity(surviving, DISTANCE, Comparison.EQUALS, 1L), TOLERANCE);
    }

    /**
     * Verifies that several filters multiply their selectivities (independence):
     * 1000 rows, 1/10 for the city and 1/2 for the distance keep 50 rows.
     */
    @Test
    void multipleFiltersMultiplySelectivities() {
        List<Partition> surviving = List.of(tripsPartition(1000, 10, 0, 100, 100));
        List<Predicate> filters = List.of(
                new Predicate("city", Comparison.EQUALS, "Aarhus"),
                new Predicate("distance", Comparison.LESS_THAN, 50L));

        assertEquals(50.0, CardinalityEstimator.filteredTableRows(surviving, TRIPS_SCHEMA, filters), TOLERANCE);
    }

    /**
     * Verifies that a table without filters is estimated at its base size.
     */
    @Test
    void noFiltersKeepsBaseTableRows() {
        List<Partition> surviving = List.of(tripsPartition(70, 5, 0, 10, 10));

        assertEquals(70.0, CardinalityEstimator.filteredTableRows(surviving, TRIPS_SCHEMA, List.of()), TOLERANCE);
    }

    /**
     * Verifies that filters on empty input estimate 0 rows instead of dividing by
     * zero.
     */
    @Test
    void filtersOnEmptyInputEstimateZero() {
        List<Predicate> filters = List.of(
                new Predicate("city", Comparison.EQUALS, "Aarhus"),
                new Predicate("distance", Comparison.GREATER_THAN, 5L));

        assertEquals(0.0, CardinalityEstimator.filteredTableRows(List.of(), TRIPS_SCHEMA, filters), TOLERANCE);
    }

    /**
     * Verifies the equi-join formula {@code |R| * |S| / max(ndv)}: 1000 orders
     * with 100 distinct customers join 200 customers with 200 keys into 1000 rows.
     */
    @Test
    void equiJoinDividesByLargerNdv() {
        assertEquals(1000.0, CardinalityEstimator.equiJoinRows(1000.0, 200.0, 100L, 200L), TOLERANCE);
        assertEquals(1000.0, CardinalityEstimator.equiJoinRows(200.0, 1000.0, 200L, 100L), TOLERANCE);
    }

    /**
     * Verifies that an empty join input estimates 0 rows, including when both NDVs
     * are 0.
     */
    @Test
    void equiJoinWithEmptyInputIsZero() {
        assertEquals(0.0, CardinalityEstimator.equiJoinRows(0.0, 0.0, 0L, 0L), TOLERANCE);
        assertEquals(0.0, CardinalityEstimator.equiJoinRows(0.0, 50.0, 0L, 10L), TOLERANCE);
    }

    /**
     * Verifies that a join of a filtered table with a base table composes the
     * estimates: 50 filtered trips join 20 cities on a 10-value key.
     */
    @Test
    void filteredTableJoinComposesEstimates() {
        List<Partition> trips = List.of(tripsPartition(1000, 10, 0, 100, 100));
        double filteredTrips = CardinalityEstimator.filteredTableRows(trips, TRIPS_SCHEMA,
                List.of(new Predicate("distance", Comparison.LESS_THAN, 5L)));

        assertEquals(50.0, filteredTrips, TOLERANCE);
        assertEquals(100.0, CardinalityEstimator.equiJoinRows(filteredTrips, 20.0,
                CardinalityEstimator.columnNdv(trips, "city"), 10L), TOLERANCE);
    }

    /**
     * Verifies that invalid arguments throw IllegalArgumentException.
     */
    @Test
    void invalidInputThrowsIllegalArgumentException() {
        List<Partition> surviving = List.of(tripsPartition(10, 5, 0, 10, 10));

        assertThrows(IllegalArgumentException.class, () -> CardinalityEstimator.baseTableRows(null));
        assertThrows(IllegalArgumentException.class, () -> CardinalityEstimator.filteredTableRows(surviving,
                TRIPS_SCHEMA, List.of(new Predicate("unknown", Comparison.EQUALS, 1L))));
        assertThrows(IllegalArgumentException.class, () -> CardinalityEstimator.filterSelectivity(surviving,
                DISTANCE, Comparison.LESS_THAN, "not a number"));
        assertThrows(IllegalArgumentException.class, () -> CardinalityEstimator.equiJoinRows(-1.0, 1.0, 1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> CardinalityEstimator.equiJoinRows(1.0, 1.0, 1L, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> CardinalityEstimator.equiJoinRows(Double.NaN, 1.0, 1L, 1L));
    }
}
