package io.github.seanchatmangpt.jotp.messagepatterns.transformation;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Message Translator pattern: transforms a message from one format to another.
 *
 * <p>Enterprise Integration Pattern: <em>Message Translator</em> (EIP §7.1). Erlang analog: a
 * process that receives a message in one format, transforms it, and sends the translated version to
 * the next process.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka).
 *
 * @param <A> input message type
 * @param <B> output message type
 */
public final class MessageTranslator<A, B> {

    private final Function<A, B> translator;
    private final Proc<Void, A> proc;

    /**
     * Creates a message translator.
     *
     * @param translator the translation function
     * @param destination the consumer for translated messages
     */
    public MessageTranslator(Function<A, B> translator, Consumer<B> destination) {
        this.translator = translator;
        this.proc = new Proc<>(null, (state, msg) -> {
            B translated = translator.apply(msg);
            destination.accept(translated);
            return state;
        });
    }

    /** Translate and forward a message. */
    public void translate(A message) {
        proc.tell(message);
    }

    /** Apply the translation synchronously without sending. */
    public B apply(A message) {
        return translator.apply(message);
    }

    /** Stop the translator. */
    public void stop() {
        proc.stop();
    }
}
