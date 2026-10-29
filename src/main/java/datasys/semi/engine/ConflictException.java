package datasys.semi.engine;

/**
 * Signals that a DML statement lost a first-committer-wins race: another
 * statement committed a new version of the table between the statement's
 * snapshot (its base version) and its own commit.
 *
 * <p>
 * The aborted statement leaves no visible change, because nothing it wrote is
 * reachable before the catalog swap, so it can simply be retried on a fresh
 * snapshot. The message has the form
 * {@code conflict table=<table> base=<base> current=<current>} so the boundary
 * that logs the failure records the conflict verbatim. Instances are immutable
 * and safe to share between threads.
 */
public final class ConflictException extends RuntimeException {

    // --- Conflict Details ---
    private final String tableName;
    private final long baseVersion;
    private final long currentVersion;

    /**
     * Creates a conflict for a commit whose base version is no longer current.
     *
     * @param tableName      table the statement tried to commit
     * @param baseVersion    version the statement started from
     * @param currentVersion committed version found at commit time; differs from
     *                       {@code baseVersion}
     */
    public ConflictException(String tableName, long baseVersion, long currentVersion) {
        super("conflict table=%s base=%d current=%d".formatted(tableName, baseVersion, currentVersion));
        this.tableName = tableName;
        this.baseVersion = baseVersion;
        this.currentVersion = currentVersion;
    }

    /**
     * Returns the table the losing statement tried to commit.
     *
     * @return table name
     */
    public String tableName() {
        return tableName;
    }

    /**
     * Returns the version the losing statement started from.
     *
     * @return base catalog version
     */
    public long baseVersion() {
        return baseVersion;
    }

    /**
     * Returns the committed version found at commit time.
     *
     * @return current catalog version
     */
    public long currentVersion() {
        return currentVersion;
    }
}
