package datasys.semi.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import datasys.semi.UtilsTest;
import datasys.semi.engine.StorageEngine.Catalog;
import datasys.semi.engine.StorageEngine.Partition;
import datasys.semi.executor.Executor;

/**
 * Required tests 2–4 of Exercise 8 (§3, §5): two writers race for the same
 * table on two threads, and the interleaving is forced with the
 * {@link StorageEngine#beforeCommit} seam and two latches instead of sleeps.
 *
 * <p>
 * The loser runs on a worker thread and parks in the hook, after it wrote its
 * partitions and before its commit's validation. The test thread then commits
 * the winner and releases the loser, which must abort with
 * {@link ConflictException} and leave exactly the winner's table behind. Each
 * thread uses its own {@link Executor} over the shared engine. Every latch wait
 * carries a timeout, so a broken engine fails the test instead of hanging.
 * Lives in the engine's package to reach the package-private seam.
 */
class TwoWriterConflictTest {

    // --- Test Configuration ---
    private static final int MAX_ROWS_PER_PARTITION = 2;
    private static final long LATCH_TIMEOUT_IN_SECONDS = 30;
    private static final String DELETE_SQL = "DELETE FROM trips WHERE distance = 95;";
    private static final String CONFLICT_MESSAGE = "conflict table=trips base=1 current=2";

    // --- Storage Under Test ---
    private Path directory;
    private StorageEngine engine;
    private Executor executor;
    private String copySql;
    private List<String> versionOneRows;

    // --- Concurrency ---
    private ExecutorService writerPool;

    /**
     * Creates {@code trips} and loads the eight golden rows as version 1 in four
     * partitions, and starts the single-thread pool that runs the loser.
     *
     * @param tempDirectory JUnit-managed temporary directory
     * @throws IOException if the CSV resource cannot be copied
     */
    @BeforeEach
    void setUp(@TempDir Path tempDirectory) throws IOException {
        directory = tempDirectory;
        Path csvFile = UtilsTest.copyResource(directory, "trips.csv");
        copySql = "COPY trips FROM '%s';".formatted(csvFile);
        engine = new StorageEngine(directory, MAX_ROWS_PER_PARTITION);
        executor = new Executor(engine);
        executor.execute("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);" + copySql);
        versionOneRows = readSortedRows();
        writerPool = Executors.newSingleThreadExecutor();
    }

    /**
     * Stops the loser's thread, interrupting it if a failed test left it parked.
     */
    @AfterEach
    void tearDown() {
        writerPool.shutdownNow();
    }

    /**
     * Required test 2: a {@code DELETE} parked before its commit aborts with
     * ConflictException once a {@code COPY} committed in between.
     *
     * @throws InterruptedException if the test thread is interrupted while
     *                              waiting
     */
    @Test
    void parkedDeleteAbortsWhenCopyCommitsFirst() throws InterruptedException {
        ExecutionException failure = raceParkedLoserAgainstWinner(DELETE_SQL, copySql);

        ConflictException conflict = assertInstanceOf(ConflictException.class, failure.getCause());
        assertEquals(CONFLICT_MESSAGE, conflict.getMessage());
    }

    /**
     * Required test 3: of two racing {@code COPY} statements, the parked one
     * aborts with ConflictException.
     *
     * @throws InterruptedException if the test thread is interrupted while
     *                              waiting
     */
    @Test
    void parkedCopyAbortsWhenCopyCommitsFirst() throws InterruptedException {
        ExecutionException failure = raceParkedLoserAgainstWinner(copySql, copySql);

        ConflictException conflict = assertInstanceOf(ConflictException.class, failure.getCause());
        assertEquals(CONFLICT_MESSAGE, conflict.getMessage());
    }

    /**
     * Required test 4 for the {@code DELETE}/{@code COPY} race: the table equals
     * the winner's result, and rerunning the aborted {@code DELETE} on the fresh
     * base commits.
     *
     * @throws InterruptedException if the test thread is interrupted while
     *                              waiting
     * @throws IOException          if the storage directories cannot be listed
     */
    @Test
    void abortedDeleteLeavesWinnerResultAndSucceedsOnRetry() throws InterruptedException, IOException {
        raceParkedLoserAgainstWinner(DELETE_SQL, copySql);
        assertTableEqualsWinnerResult();

        executor.execute(DELETE_SQL);

        assertEquals(3L, engine.snapshot("trips").version());
        List<Object[]> rows = executor.executeQuery("SELECT * FROM trips;");
        assertEquals(14, rows.size());
        assertTrue(rows.stream().noneMatch(row -> row[1].equals(95L)));
    }

    /**
     * Required test 4 for the {@code COPY}/{@code COPY} race: the table equals
     * the winner's result, and rerunning the aborted {@code COPY} on the fresh
     * base commits.
     *
     * @throws InterruptedException if the test thread is interrupted while
     *                              waiting
     * @throws IOException          if the storage directories cannot be listed
     */
    @Test
    void abortedCopyLeavesWinnerResultAndSucceedsOnRetry() throws InterruptedException, IOException {
        raceParkedLoserAgainstWinner(copySql, copySql);
        assertTableEqualsWinnerResult();

        executor.execute(copySql);

        assertEquals(3L, engine.snapshot("trips").version());
        assertEquals(24, executor.executeQuery("SELECT * FROM trips;").size());
    }

    /**
     * Verifies that the session log tells the race's story: the winner's
     * {@code version=2} line and the loser's conflict {@code ERROR} line come
     * from two different threads, and the loser never logs {@code version=2}.
     *
     * @throws InterruptedException if the test thread is interrupted while
     *                              waiting
     * @throws IOException          if the log file cannot be read
     */
    @Test
    void raceLogShowsWinnerVersionAndLoserErrorOnDifferentThreads() throws InterruptedException, IOException {
        String sessionId = UUID.randomUUID().toString();
        MDC.put("sessionId", sessionId);
        try {
            raceParkedLoserAgainstWinner(DELETE_SQL, copySql, sessionId);
        } finally {
            MDC.remove("sessionId");
        }

        List<String[]> sessionLines = readSessionLogLines(sessionId);
        String winnerThreadId = findThreadId(sessionLines,
                line -> line[6].startsWith("statement=COPY") && line[6].contains("version=2"));
        String loserThreadId = findThreadId(sessionLines,
                line -> line[4].equals("ERROR") && line[6].contains(CONFLICT_MESSAGE));

        assertNotEquals(winnerThreadId, loserThreadId);
        assertTrue(sessionLines.stream()
                .filter(line -> line[3].equals(loserThreadId))
                .noneMatch(line -> line[6].contains("version=2")),
                "the loser must not log the version only the winner commits");
    }

    /**
     * Verifies that an aborted {@code COPY} leaves exactly one {@code ERROR} line,
     * written by the {@link Executor} boundary, so each failure is counted once
     * (AGENTS §5.4).
     *
     * @throws InterruptedException if the test thread is interrupted while
     *                              waiting
     * @throws IOException          if the log file cannot be read
     */
    @Test
    void abortedCopyLeavesExactlyOneErrorLineAtTheBoundary() throws InterruptedException, IOException {
        String sessionId = UUID.randomUUID().toString();
        raceParkedLoserAgainstWinner(copySql, copySql, sessionId);

        List<String[]> errorLines = readSessionLogLines(sessionId).stream()
                .filter(line -> line[4].equals("ERROR"))
                .toList();
        assertEquals(1, errorLines.size(), "expected exactly one ERROR line for one failed COPY");
        assertEquals("Executor", errorLines.getFirst()[5]);
        assertEquals("statement_failed operation=COPY reason=" + CONFLICT_MESSAGE, errorLines.getFirst()[6]);
    }

    // --- Race Choreography ---

    /**
     * Runs the §3 choreography without a log session: see
     * {@link #raceParkedLoserAgainstWinner(String, String, String)}.
     *
     * @param loserSql  statement that parks before its commit and must abort
     * @param winnerSql statement that commits while the loser is parked
     * @return the failure of the loser's future
     * @throws InterruptedException if the test thread is interrupted while
     *                              waiting
     */
    private ExecutionException raceParkedLoserAgainstWinner(String loserSql, String winnerSql)
            throws InterruptedException {
        return raceParkedLoserAgainstWinner(loserSql, winnerSql, null);
    }

    /**
     * Parks {@code loserSql} on the worker thread right before its commit's
     * validation, commits {@code winnerSql} on the test thread, then releases the
     * loser and returns how it failed.
     *
     * @param loserSql  statement that parks before its commit and must abort
     * @param winnerSql statement that commits while the loser is parked
     * @param sessionId log session id for the loser's thread, or {@code null} to
     *                  leave its MDC without one
     * @return the failure of the loser's future
     * @throws InterruptedException if the test thread is interrupted while
     *                              waiting
     */
    private ExecutionException raceParkedLoserAgainstWinner(String loserSql, String winnerSql, String sessionId)
            throws InterruptedException {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);
        engine.beforeCommit = () -> {
            parked.countDown();
            awaitOrFail(winnerDone);
        };

        Future<?> loser = writerPool.submit(() -> runOnOwnExecutor(loserSql, sessionId));
        assertTrue(parked.await(LATCH_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS), "loser never reached its commit");
        engine.beforeCommit = () -> {
        };
        executor.execute(winnerSql);
        assertEquals(2L, engine.snapshot("trips").version());
        assertFalse(loser.isDone(), "loser must still be parked when the winner commits");
        winnerDone.countDown();

        return assertThrows(ExecutionException.class,
                () -> loser.get(LATCH_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS));
    }

    /**
     * Executes a statement on the calling thread with a fresh {@link Executor}
     * over the shared engine, tagging its log lines with {@code sessionId}.
     *
     * @param sql       statement to execute
     * @param sessionId log session id, or {@code null} for none
     */
    private void runOnOwnExecutor(String sql, String sessionId) {
        if (sessionId != null) {
            MDC.put("sessionId", sessionId);
        }
        try {
            new Executor(engine).execute(sql);
        } finally {
            MDC.remove("sessionId");
        }
    }

    /**
     * Waits for a latch inside the commit hook, failing the parked commit if the
     * test never releases it.
     *
     * @param latch latch the test counts down to release the writer
     * @throws IllegalStateException if the wait times out or is interrupted
     */
    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(LATCH_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("parked writer was never released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("parked writer was interrupted", exception);
        }
    }

    // --- Table & Disk Assertions ---

    /**
     * Asserts that the table is exactly the winner's {@code COPY} applied to
     * version 1: version 2, version 1's rows twice, and on disk only the partition
     * files that version 2 references, with no temporary files.
     *
     * @throws IOException if the storage directories cannot be listed
     */
    private void assertTableEqualsWinnerResult() throws IOException {
        Catalog current = engine.snapshot("trips");
        assertEquals(2L, current.version());

        List<String> expectedRows = new ArrayList<>(versionOneRows);
        expectedRows.addAll(versionOneRows);
        expectedRows.sort(null);
        assertEquals(expectedRows, readSortedRows());

        assertEquals(referencedPartitionFiles(current), listPartitionFiles());
        assertTrue(listTemporaryFiles().isEmpty(), "aborted commit left temporary files behind");
    }

    /**
     * Reads every row of the current version as sorted strings, so tables can be
     * compared independent of partition order.
     *
     * @return the table's rows rendered with {@link Arrays#toString(Object[])},
     *         sorted
     */
    private List<String> readSortedRows() {
        return executor.executeQuery("SELECT * FROM trips;").stream()
                .map(Arrays::toString)
                .sorted()
                .toList();
    }

    /**
     * Collects the file names of the partitions a catalog references.
     *
     * @param catalog catalog version
     * @return partition file names
     */
    private static Set<String> referencedPartitionFiles(Catalog catalog) {
        return catalog.partitions().stream()
                .map(Partition::path)
                .map(path -> Path.of(path).getFileName().toString())
                .collect(Collectors.toSet());
    }

    /**
     * Lists the partition file names of the {@code trips} table on disk.
     *
     * @return file names in {@code data/trips/} ending in {@code .dat}
     * @throws IOException if the directory cannot be listed
     */
    private Set<String> listPartitionFiles() throws IOException {
        try (Stream<Path> paths = Files.list(directory.resolve("data").resolve("trips"))) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".dat"))
                    .collect(Collectors.toSet());
        }
    }

    /**
     * Lists leftover temporary files anywhere under the storage directories.
     *
     * @return paths whose file name contains {@code .tmp-}
     * @throws IOException if the directories cannot be walked
     */
    private List<Path> listTemporaryFiles() throws IOException {
        try (Stream<Path> paths = Stream.concat(Files.walk(directory.resolve("catalogs")),
                Files.walk(directory.resolve("data")))) {
            return paths.filter(path -> path.getFileName().toString().contains(".tmp-")).toList();
        }
    }

    // --- Log Assertions ---

    /**
     * Reads the CSV log lines of one session, split into the seven schema columns
     * {@code timestamp, sessionId, statementNumber, threadId, logLevel,
     * className, logMessage}.
     *
     * @param sessionId session whose lines to keep
     * @return the session's log lines as column arrays
     * @throws IOException if the log file cannot be read
     */
    private static List<String[]> readSessionLogLines(String sessionId) throws IOException {
        return Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8).stream()
                .map(line -> line.split(",", 7))
                .filter(columns -> columns.length == 7 && columns[1].equals(sessionId))
                .toList();
    }

    /**
     * Finds the thread id of the first log line matching a condition.
     *
     * @param lines     log lines as column arrays
     * @param condition condition on the columns
     * @return the {@code threadId} column of the first match
     * @throws AssertionError if no line matches
     */
    private static String findThreadId(List<String[]> lines, Predicate<String[]> condition) {
        return lines.stream()
                .filter(condition)
                .map(columns -> columns[3])
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected log line not found"));
    }
}
