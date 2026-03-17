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
 * Enumeration of all event types that can be recorded in the event log.
 *
 * <p>Categories:
 * - <b>Message Events</b>: Message delivery through mailboxes
 * - <b>Process Lifecycle</b>: Startup, shutdown, crashes, restarts
 * - <b>Supervision</b>: Restart strategies, supervision decisions
 * - <b>Link/Monitor Events</b>: Bidirectional links, one-way monitors
 * - <b>Node Events</b>: Cluster topology changes
 * - <b>Migration Events</b>: Process migration across nodes
 * - <b>Custom Events</b>: Application-defined event types
 */
public enum EventType {
    // Message events
    MESSAGE_SENT,
    MESSAGE_RECEIVED,
    MESSAGE_DELIVERED,
    MESSAGE_FAILED,

    // Process lifecycle
    PROCESS_STARTED,
    PROCESS_STOPPED,
    PROCESS_CRASHED,
    PROCESS_RESTARTED,

    // Supervision
    SUPERVISOR_DECISION,
    RESTART_STRATEGY_APPLIED,

    // Link and monitor
    PROCESS_LINKED,
    PROCESS_MONITOR_SETUP,
    PROCESS_MONITOR_DOWN,
    LINK_BROKEN,

    // Node and topology
    NODE_JOINED,
    NODE_FAILED,
    NODE_REMOVED,

    // Migration
    MIGRATION_STARTED,
    MIGRATION_IN_PROGRESS,
    MIGRATION_COMPLETED,
    MIGRATION_FAILED,

    // Custom/application events
    CUSTOM
}
