package io.github.seanchatmangpt.jotp.messaging.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Composed Message Processor pattern (EIP): chains multiple routers (transformations), filters,
 * and side-effect observers into a single processing pipeline.
 *
 * <p>The pipeline applies transformations in registration order, then evaluates filters (any
 * failing filter short-circuits and returns {@code null}), and finally applies additional routes.
 * Peek observers are interspersed between stages for non-destructive side effects.
 *
 * <p>This class is immutable — each builder method returns a new instance, making it safe to share
 * and reuse pipelines.
 *
 * @param <M> the message type flowing through the pipeline
 */
public final class ComposedMessageProcessor<M> {

    private final List<Function<M, M>> routers;
    private final List<Predicate<M>> filters;
    private final List<Object> stages; // ordered list of Stage objects

    private sealed interface Stage<M> {}

    private record RouterStage<M>(Function<M, M> fn) implements Stage<M> {}

    private record FilterStage<M>(Predicate<M> predicate) implements Stage<M> {}

    private record PeekStage<M>(Consumer<M> observer) implements Stage<M> {}

    private ComposedMessageProcessor(
            List<Function<M, M>> routers,
            List<Predicate<M>> filters,
            List<Object> stages) {
        this.routers = List.copyOf(routers);
        this.filters = List.copyOf(filters);
        this.stages = List.copyOf(stages);
    }

    /**
     * Create a new pipeline with the given router functions applied in order.
     *
     * <p>Null entries in {@code routers} are silently skipped.
     *
     * @param <M> message type
     * @param routers transformation functions
     * @return new pipeline
     */
    @SafeVarargs
    public static <M> ComposedMessageProcessor<M> compose(Function<M, M>... routers) {
        List<Object> stages = new ArrayList<>();
        List<Function<M, M>> routerList = new ArrayList<>();
        List<Predicate<M>> filterList = new ArrayList<>();
        for (Function<M, M> r : routers) {
            if (r != null) {
                stages.add(new RouterStage<>(r));
                routerList.add(r);
            }
        }
        return new ComposedMessageProcessor<>(routerList, filterList, stages);
    }

    /**
     * Add a filter to the pipeline. Messages that do not satisfy the predicate are dropped
     * (subsequent stages are not executed).
     *
     * @param predicate the filter criterion
     * @return new pipeline with the filter appended
     */
    public ComposedMessageProcessor<M> thenFilter(Predicate<M> predicate) {
        List<Object> newStages = new ArrayList<>(stages);
        newStages.add(new FilterStage<>(predicate));
        List<Predicate<M>> newFilters = new ArrayList<>(filters);
        newFilters.add(predicate);
        return new ComposedMessageProcessor<>(new ArrayList<>(routers), newFilters, newStages);
    }

    /**
     * Add a transformation route to the pipeline.
     *
     * <p>Null routes are silently skipped.
     *
     * @param router the transformation function
     * @return new pipeline with the route appended
     */
    public ComposedMessageProcessor<M> thenRoute(Function<M, M> router) {
        if (router == null) {
            return this;
        }
        List<Object> newStages = new ArrayList<>(stages);
        newStages.add(new RouterStage<>(router));
        List<Function<M, M>> newRouters = new ArrayList<>(routers);
        newRouters.add(router);
        return new ComposedMessageProcessor<>(newRouters, new ArrayList<>(filters), newStages);
    }

    /**
     * Add a peek observer to the pipeline. The observer sees the current message value at this
     * point in the pipeline and may perform side effects; it does not transform the message.
     *
     * @param observer the side-effect consumer
     * @return new pipeline with the peek appended
     */
    public ComposedMessageProcessor<M> peek(Consumer<M> observer) {
        List<Object> newStages = new ArrayList<>(stages);
        newStages.add(new PeekStage<>(observer));
        return new ComposedMessageProcessor<>(
                new ArrayList<>(routers), new ArrayList<>(filters), newStages);
    }

    /**
     * Chain this pipeline with another. The result of this pipeline (or {@code null} if filtered)
     * is passed to the other pipeline.
     *
     * @param next the next pipeline to chain
     * @return a new pipeline representing the composition
     */
    public ComposedMessageProcessor<M> andThen(ComposedMessageProcessor<M> next) {
        return new ComposedMessageProcessor<M>(
                new ArrayList<>(routers),
                new ArrayList<>(filters),
                new ArrayList<>(stages)) {
            @Override
            public M apply(M message) {
                M intermediate = ComposedMessageProcessor.this.apply(message);
                if (intermediate == null) {
                    return null;
                }
                return next.apply(intermediate);
            }
        };
    }

    /**
     * Apply the pipeline to a message.
     *
     * @param message the input message (may be null — null propagates through)
     * @return the transformed message, or {@code null} if the message was filtered out
     */
    @SuppressWarnings("unchecked")
    public M apply(M message) {
        if (message == null) {
            return null;
        }
        M current = message;
        for (Object stage : stages) {
            if (current == null) {
                return null;
            }
            switch (stage) {
                case RouterStage<?> r -> current = ((RouterStage<M>) r).fn().apply(current);
                case FilterStage<?> f -> {
                    if (!((FilterStage<M>) f).predicate().test(current)) {
                        return null;
                    }
                }
                case PeekStage<?> p -> ((PeekStage<M>) p).observer().accept(current);
            }
        }
        return current;
    }

    /**
     * Apply the pipeline and wrap the result in an {@link Optional}.
     *
     * @param message the input message
     * @return {@code Optional.of(result)} if the message passed all filters; {@code Optional.empty()}
     *     if filtered out
     */
    public Optional<M> applyOptional(M message) {
        return Optional.ofNullable(apply(message));
    }
}
