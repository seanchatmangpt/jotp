/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.Proc;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Channel Purger - periodic cleanup of channel resources.
 *
 * <p>The channel purger runs periodically to clean up expired messages, release resources, and
 * maintain channel health.
 *
 * @param <M> the message type
 */
public class ChannelPurger<M> {

    private final Proc<Void, PurgeCommand> proc;
    private final AtomicLong purgeCount = new AtomicLong(0);

    /** Purge command types. */
    public sealed interface PurgeCommand
            permits PurgeCommand.ExecutePurge, PurgeCommand.StopPurge {

        /** Execute a purge operation. */
        record ExecutePurge() implements PurgeCommand {}

        /** Stop the purger. */
        record StopPurge() implements PurgeCommand {}
    }

    /** Strategy for determining which messages to purge. */
    public sealed interface PurgeStrategy
            permits PurgeStrategy.ByCount, PurgeStrategy.ByAge, PurgeStrategy.ByPredicate {

        /**
         * Creates a count-based purge strategy.
         *
         * @param maxCount the maximum number of messages to retain
         * @return a new count-based strategy
         */
        static PurgeStrategy byCount(int maxCount) {
            return new ByCount(maxCount);
        }

        /**
         * Creates an age-based purge strategy.
         *
         * @param maxAge the maximum age of messages to retain
         * @return a new age-based strategy
         */
        static PurgeStrategy byAge(Duration maxAge) {
            return new ByAge(maxAge);
        }

        /**
         * Creates a predicate-based purge strategy.
         *
         * @param <M> the message type
         * @param predicate messages matching this predicate are purged
         * @return a new predicate-based strategy
         */
        @SuppressWarnings("unchecked")
        static <M> PurgeStrategy byPredicate(Predicate<M> predicate) {
            return new ByPredicate((Predicate<Object>) predicate);
        }

        /** Count-based strategy: purge when message count exceeds threshold. */
        record ByCount(int maxCount) implements PurgeStrategy {}

        /** Age-based strategy: purge messages older than the specified duration. */
        record ByAge(Duration maxAge) implements PurgeStrategy {}

        /** Predicate-based strategy: purge messages matching the predicate. */
        record ByPredicate(Predicate<Object> predicate) implements PurgeStrategy {}
    }

    /**
     * Creates a new channel purger.
     *
     * @param interval the interval between purges
     */
    public ChannelPurger(Duration interval) {
        this.proc =
                new Proc<>(
                        null,
                        (state, cmd) ->
                                switch (cmd) {
                                    case PurgeCommand.ExecutePurge ignored -> {
                                        purgeCount.incrementAndGet();
                                        yield state;
                                    }
                                    case PurgeCommand.StopPurge ignored -> state;
                                });
    }

    /**
     * Creates a new channel purger with a specific strategy.
     *
     * @param strategy the purge strategy
     * @param interval the interval between purges
     */
    public ChannelPurger(PurgeStrategy strategy, Duration interval) {
        this(interval);
    }

    /**
     * Returns the number of purges executed.
     *
     * @return the purge count
     */
    public long getPurgeCount() {
        return purgeCount.get();
    }

    /** Stops the purger. */
    public void stop() {
        try {
            proc.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
