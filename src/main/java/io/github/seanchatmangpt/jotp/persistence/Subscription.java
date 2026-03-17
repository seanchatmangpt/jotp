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

package io.github.seanchatmangpt.jotp.persistence;

/**
 * Handle to a subscription to event stream changes.
 *
 * <p>Subscriptions are created via {@link EventQuery#subscribe(EventType, java.util.function.Consumer)}
 * or similar methods. They deliver events to a handler function as they are appended to the log.
 *
 * <p>Implementations must:
 * - Deliver events in order (no reordering)
 * - Handle concurrent subscribers without interference
 * - Support cancellation via {@link #unsubscribe()}
 * - Allow checking subscription status via {@link #isActive()}
 *
 * <p>Handler execution:
 * - Runs on a virtual thread to avoid blocking the event log
 * - Exceptions in handlers are isolated and logged but don't kill the subscription
 */
public interface Subscription {

    /**
     * Unsubscribe from the event stream.
     *
     * <p>After calling this, no more events will be delivered to the handler.
     * Safe to call multiple times.
     */
    void unsubscribe();

    /**
     * Check if this subscription is still active.
     *
     * @return true if the subscription will receive future events
     */
    boolean isActive();
}
