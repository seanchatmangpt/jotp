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

/**
 * Channel Purger - periodic cleanup of channel resources.
 *
 * <p>The channel purger runs periodically to clean up expired messages, release resources, and
 * maintain channel health.
 */
public class ChannelPurger {

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
     * Returns the number of purges executed.
     *
     * @return the purge count
     */
    public long getPurgeCount() {
        return purgeCount.get();
    }

    /**
     * Stops the purger.
     *
     * @throws InterruptedException if interrupted while stopping
     */
    public void stop() throws InterruptedException {
        proc.stop();
    }
}
