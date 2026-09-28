package datasys.semi.operators;

/**
 * A node in a Volcano-style pull pipeline.
 *
 * <p>
 * Every operator is a stream of rows that its parent drains one row at a time.
 * The lifecycle is strictly {@code open()} once, {@code next()} until it
 * returns {@code null}, then {@code close()} once. Calling {@code next()}
 * before {@code open()} is a programming error, and an operator that has
 * returned {@code null} keeps returning {@code null}.
 *
 * <p>
 * Implementations are not thread-safe: one pipeline belongs to one statement on
 * one thread, and an operator holds the cursor state of that single traversal.
 */
public interface Operator {

    /**
     * Prepares this operator for iteration, propagating to any child operators.
     *
     * @throws IllegalStateException if the operator has already been opened
     */
    void open();

    /**
     * Produces the next row of this operator's output.
     *
     * @return one row in schema column order, or {@code null} when the stream is
     *         exhausted
     * @throws IllegalStateException if the operator has not been opened
     */
    Object[] next();

    /**
     * Releases the resources held by this operator and propagates to any child
     * operators. Repeated calls are harmless.
     *
     * @throws IllegalStateException if the operator has not been opened
     */
    void close();
}
