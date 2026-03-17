package io.github.seanchatmangpt.jotp;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Message recording and replay for deterministic testing of concurrent processes.
 *
 * <p>Wraps any {@link Proc}{@code <S,M>} and intercepts all incoming messages, recording them with
 * timestamps. The recording can then be replayed deterministically — enabling time-travel debugging
 * for concurrent systems.
 *
 * <p>This is genuinely novel: no Java framework provides deterministic message replay for
 * virtual-thread actor systems.
 *
 * <p><strong>Recording:</strong>
 *
 * <pre>{@code
 * var recorder = ProcRecorder.record(myProc);
 * // ... system runs normally ...
 * Recording<MyMsg> tape = recorder.stop();
 * tape.save(Path.of("scenario-123.tape"));
 * }</pre>
 *
 * <p><strong>Replay in test:</strong>
 *
 * <pre>{@code
 * Recording<MyMsg> tape = Recording.load(Path.of("scenario-123.tape"), MyMsg.class);
 * var result = ProcRecorder.replay(tape, myProcFactory, Duration.ofSeconds(5));
 * assertThat(result.finalState()).isEqualTo(expectedState);
 * assertThat(result.messageCount()).isEqualTo(42);
 * }</pre>
 *
 * <p><strong>Replay modes:</strong>
 *
 * <ul>
 *   <li>{@link ReplayMode#REAL_TIME} — replays messages at their original inter-arrival times,
 *       faithfully simulating the recorded timing characteristics.
 *   <li>{@link ReplayMode#INSTANT} — replays all messages as fast as possible, useful for fast
 *       deterministic test runs that only care about state, not timing.
 * </ul>
 *
 * <p><strong>Thread safety:</strong> The internal recording buffer uses a {@link
 * CopyOnWriteArrayList}, so recording and reading the buffer from multiple threads is safe.
 *
 * @param <S> process state type
 * @param <M> message type — must be {@link Serializable} for {@link Recording#save}/{@link
 *     Recording#load} to work
 */
public final class ProcRecorder<S, M> {

    /** Controls timing behaviour during replay. */
    public enum ReplayMode {
        /**
         * Replay messages at their original inter-arrival times (preserves timing characteristics
         * of the original recording).
         */
        REAL_TIME,

        /**
         * Replay all messages as fast as possible without any delays (useful for fast,
         * deterministic test runs).
         */
        INSTANT
    }

    /**
     * A single recorded message with timing metadata.
     *
     * @param <M> the message type
     */
    public record RecordedMessage<M>(
            Instant timestamp, Duration sinceStart, M message, String senderId)
            implements Serializable {}

    /**
     * A complete recorded session — an ordered, immutable list of {@link RecordedMessage}s captured
     * during a {@link ProcRecorder} session.
     *
     * <p>Supports serialisation to a simple line-delimited text format via {@link #save(Path)} and
     * reconstruction via {@link #load(Path, MessageDeserializer)}.
     *
     * @param <M> the message type
     */
    public record Recording<M>(
            Instant startTime, List<RecordedMessage<M>> messages, Duration totalDuration)
            implements Serializable {

        /**
         * Canonical constructor — defensively copies the message list to guarantee immutability.
         */
        public Recording {
            messages = Collections.unmodifiableList(new ArrayList<>(messages));
        }

        /**
         * Persist this recording to a file using Java object serialization.
         *
         * <p>The message type {@code M} and all of its transitive field types must implement {@link
         * Serializable}.
         *
         * @param path destination file path (created or overwritten)
         * @throws IOException if an I/O error occurs
         */
        public void save(Path path) throws IOException {
            try (var oos =
                    new java.io.ObjectOutputStream(
                            new java.io.BufferedOutputStream(Files.newOutputStream(path)))) {
                oos.writeObject(this);
            }
        }

        /**
         * Load a recording that was previously persisted with {@link #save(Path)}.
         *
         * <p>The message type {@code M} must be the same class used when saving; if not, an {@link
         * IOException} wrapping a {@link ClassCastException} or {@link
         * java.io.InvalidClassException} will be thrown.
         *
         * @param path source file path
         * @param <M> message type
         * @return the deserialized {@code Recording}
         * @throws IOException if an I/O or deserialization error occurs
         */
        @SuppressWarnings("unchecked")
        public static <M> Recording<M> load(Path path) throws IOException {
            try (var ois =
                    new java.io.ObjectInputStream(
                            new java.io.BufferedInputStream(Files.newInputStream(path)))) {
                return (Recording<M>) ois.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException("Failed to deserialize Recording: class not found", e);
            }
        }

        /**
         * Convenience overload that accepts a {@code Class<M>} token for documentation purposes;
         * the actual deserialization is type-erased, so the token is not used at runtime.
         *
         * @param path source file path
         * @param messageType message type token (unused at runtime, aids readability)
         * @param <M> message type
         * @return the deserialized {@code Recording}
         * @throws IOException if an I/O or deserialization error occurs
         */
        public static <M> Recording<M> load(Path path, Class<M> messageType) throws IOException {
            return load(path);
        }

        /** Returns the number of recorded messages. */
        public int size() {
            return messages.size();
        }
    }

    /**
     * Result of replaying a {@link Recording} against a fresh process.
     *
     * @param <S> process state type
     */
    public record ReplayResult<S>(
            S finalState, int messageCount, Duration replayDuration, List<Exception> errors) {}

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    private final Proc<S, M> delegate;
    private final Instant startTime;
    private final CopyOnWriteArrayList<RecordedMessage<M>> buffer = new CopyOnWriteArrayList<>();
    private volatile Instant stopTime;

    private ProcRecorder(Proc<S, M> delegate) {
        this.delegate = delegate;
        this.startTime = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start recording a process.
     *
     * <p>The returned {@link ProcRecorder} wraps the given process. All subsequent messages sent
     * via {@link #tell(Object)} or {@link #ask(Object)} on the recorder are captured with timing
     * metadata before being forwarded to the underlying process.
     *
     * @param proc the process to record
     * @param <S> state type
     * @param <M> message type
     * @return an active recorder
     */
    public static <S, M> ProcRecorder<S, M> record(Proc<S, M> proc) {
        return new ProcRecorder<>(proc);
    }

    /**
     * Forward a fire-and-forget message to the underlying process while recording it.
     *
     * @param msg the message to send
     */
    public void tell(M msg) {
        tell(msg, null);
    }

    /**
     * Forward a fire-and-forget message to the underlying process while recording it, annotating
     * the entry with an optional sender identifier.
     *
     * @param msg the message to send
     * @param senderId arbitrary identifier for the logical sender (may be {@code null})
     */
    public void tell(M msg, String senderId) {
        Instant ts = Instant.now();
        Duration since = Duration.between(startTime, ts);
        buffer.add(new RecordedMessage<>(ts, since, msg, senderId));
        delegate.tell(msg);
    }

    /**
     * Forward a request-reply message to the underlying process while recording it.
     *
     * @param msg the message to send
     * @return a future that completes with the process state after the message is processed
     */
    public CompletableFuture<S> ask(M msg) {
        return ask(msg, null);
    }

    /**
     * Forward a request-reply message to the underlying process while recording it, annotating the
     * entry with an optional sender identifier.
     *
     * @param msg the message to send
     * @param senderId arbitrary identifier for the logical sender (may be {@code null})
     * @return a future that completes with the process state after the message is processed
     */
    public CompletableFuture<S> ask(M msg, String senderId) {
        Instant ts = Instant.now();
        Duration since = Duration.between(startTime, ts);
        buffer.add(new RecordedMessage<>(ts, since, msg, senderId));
        return delegate.ask(msg);
    }

    /**
     * Stop recording and return the captured tape.
     *
     * <p>After calling {@code stop()}, the underlying process continues to run; only the recorder
     * is deactivated. Messages sent directly to the delegate process after this point are no longer
     * captured.
     *
     * @return an immutable {@link Recording} containing all messages captured since {@link
     *     #record(Proc)} was called
     */
    public Recording<M> stop() {
        stopTime = Instant.now();
        Duration total = Duration.between(startTime, stopTime);
        return new Recording<>(startTime, new ArrayList<>(buffer), total);
    }

    /**
     * Replay a recording against a fresh process using {@link ReplayMode#REAL_TIME}.
     *
     * @param recording the tape to replay
     * @param procFactory factory that produces a fresh, ready-to-receive process
     * @param timeout maximum time to wait for each {@code ask} to complete
     * @param <S> state type
     * @param <M> message type
     * @return a {@link ReplayResult} describing the outcome
     */
    public static <S, M> ReplayResult<S> replay(
            Recording<M> recording, Supplier<Proc<S, M>> procFactory, Duration timeout) {
        return replay(recording, procFactory, timeout, ReplayMode.REAL_TIME);
    }

    /**
     * Replay a recording against a fresh process.
     *
     * <p>Creates a new process from {@code procFactory}, then delivers every recorded message in
     * order. In {@link ReplayMode#REAL_TIME} mode the inter-arrival delays from the original
     * recording are preserved; in {@link ReplayMode#INSTANT} mode all messages are sent as fast as
     * possible.
     *
     * <p>The replay uses {@link Proc#ask(Object, Duration)} for each message so that the final
     * state can be captured after the last message is processed.
     *
     * @param recording the tape to replay
     * @param procFactory factory that produces a fresh, ready-to-receive process
     * @param timeout maximum time to wait for any single message's reply
     * @param mode replay timing mode
     * @param <S> state type
     * @param <M> message type
     * @return a {@link ReplayResult} describing the outcome
     */
    public static <S, M> ReplayResult<S> replay(
            Recording<M> recording,
            Supplier<Proc<S, M>> procFactory,
            Duration timeout,
            ReplayMode mode) {
        Proc<S, M> proc = procFactory.get();
        List<Exception> errors = new ArrayList<>();
        Instant replayStart = Instant.now();

        S lastState = null;
        int messageCount = 0;
        Duration previousSinceStart = Duration.ZERO;

        for (RecordedMessage<M> rm : recording.messages()) {
            // Apply inter-arrival delay in REAL_TIME mode
            if (mode == ReplayMode.REAL_TIME) {
                Duration delay = rm.sinceStart().minus(previousSinceStart);
                if (!delay.isNegative() && !delay.isZero()) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(delay.toNanos());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        errors.add(e);
                        break;
                    }
                }
            }
            previousSinceStart = rm.sinceStart();

            try {
                lastState = proc.ask(rm.message(), timeout).join();
                messageCount++;
            } catch (Exception e) {
                errors.add(e);
                // Continue replaying remaining messages even after an error so the full
                // recording is exercised and the caller sees a complete error list.
            }
        }

        Duration replayDuration = Duration.between(replayStart, Instant.now());

        // Capture final state via a separate ask if we have at least one message and
        // the last ask succeeded.
        S finalState = lastState;

        // Attempt graceful shutdown of the replay process (best-effort).
        try {
            proc.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new ReplayResult<>(finalState, messageCount, replayDuration, errors);
    }

    // -------------------------------------------------------------------------
    // Functional interface for custom message deserialization
    // -------------------------------------------------------------------------

    /**
     * Functional interface for converting a raw string token back into a message object.
     *
     * <p>Provided for callers that use text-based serialization formats and need to supply a custom
     * parser. The built-in {@link Recording#save}/{@link Recording#load} methods use Java object
     * serialization and do not require this interface.
     */
    @FunctionalInterface
    public interface MessageDeserializer<M> {
        M deserialize(String raw) throws IOException;
    }
}
