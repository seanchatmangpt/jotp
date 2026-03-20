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

package io.github.seanchatmangpt.jotp.messaging;

/**
 * A message channel that supports queue depth monitoring.
 *
 * <p>This is a top-level convenience alias for {@link Messaging.MessageChannel}, making it
 * accessible without qualifying with the {@code Messaging} prefix.
 *
 * @param <M> the message type
 */
public interface MessageChannel<M> extends Channel<M> {

    /** Returns the current queue depth (number of pending messages). */
    int queueDepth();
}
