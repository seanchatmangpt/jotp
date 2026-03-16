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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Control Bus - system management and monitoring for messaging components.
 *
 * <p>A control bus provides a centralized way to manage and monitor all messaging components in the
 * system. It allows registration, lifecycle management, and statistics collection.
 *
 * @param <M> the message type
 */
public class ControlBus<M> {

    private final Map<String, Proc<?, M>> processes = new ConcurrentHashMap<>();
    private final Map<String, ProcessStatistics> statistics = new ConcurrentHashMap<>();

    /** Creates a new control bus. */
    public ControlBus() {}

    /**
     * Registers a process with the control bus.
     *
     * @param name the process name
     * @param process the process to register
     */
    public void register(String name, Proc<?, M> process) {
        processes.put(name, process);
        statistics.put(name, new ProcessStatistics(name, 0, 0.0));
    }

    /**
     * Unregisters a process from the control bus.
     *
     * @param name the process name
     */
    public void unregister(String name) {
        processes.remove(name);
        statistics.remove(name);
    }

    /**
     * Lists all registered process names.
     *
     * @return list of process names
     */
    public List<String> listProcesses() {
        return new ArrayList<>(processes.keySet());
    }

    /**
     * Returns statistics for all registered processes.
     *
     * @return map of process names to their statistics
     */
    public Map<String, ProcessStatistics> getStats() {
        return new ConcurrentHashMap<>(statistics);
    }

    /**
     * Process statistics.
     *
     * @param name the process name
     * @param messagesProcessed the number of messages processed
     * @param avgProcessingTime the average processing time in milliseconds
     */
    public record ProcessStatistics(
            String name, long messagesProcessed, double avgProcessingTime) {}
}
