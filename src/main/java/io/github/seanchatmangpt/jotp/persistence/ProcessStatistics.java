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

import java.time.Instant;

/**
 * Statistics for a process computed from its event log entries.
 *
 * <p>Useful for monitoring and observability dashboards:
 * - Process health: crash counts, restart frequency
 * - Performance: message throughput, latency
 * - Lifetime: duration since startup, last activity
 *
 * <p>All counts are computed by filtering the event log for a specific processId.
 */
public record ProcessStatistics(
    long totalMessages,           // MESSAGE_SENT + MESSAGE_RECEIVED + MESSAGE_DELIVERED
    long totalCrashes,            // Count of PROCESS_CRASHED events
    long totalRestarts,           // Count of PROCESS_RESTARTED events
    long totalDurationMs,         // Elapsed time since first event to last event
    Instant lastActive            // Timestamp of the most recent event
) {

    /**
     * Compact constructor for validation.
     */
    public ProcessStatistics {
        if (totalMessages < 0) {
            throw new IllegalArgumentException("totalMessages must be non-negative");
        }
        if (totalCrashes < 0) {
            throw new IllegalArgumentException("totalCrashes must be non-negative");
        }
        if (totalRestarts < 0) {
            throw new IllegalArgumentException("totalRestarts must be non-negative");
        }
        if (totalDurationMs < 0) {
            throw new IllegalArgumentException("totalDurationMs must be non-negative");
        }
    }
}
