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

package io.github.seanchatmangpt.jotp.benchmark;

/**
 * Ideal zero-cost abstraction reference implementation.
 *
 * <p>This represents the theoretical minimum overhead for a feature-flagged event bus. The fast
 * path should compile to a single branch instruction that returns immediately.
 *
 * <p><strong>Expected Assembly (fast path, disabled):</strong>
 *
 * <pre>
 *   test byte [ENABLED], 1           ; Check static final boolean
 *   je   return                       ; Branch if not enabled
 *   return                            ; Exit function
 * </pre>
 *
 * <p><strong>Expected Cost:</strong> <50ns (single CPU cycle, branch prediction friendly)
 *
 * <p>This implementation serves as the "north star" for optimizing FrameworkEventBus. Any deviation
 * from this ideal should be justified by necessary functionality.
 */
public final class IdealEventBus {

    /**
     * Feature flag: compile-time constant for zero-cost fast path.
     *
     * <p><strong>Key optimization:</strong> {@code static final} allows JIT to inline and eliminate
     * dead code. When {@code false}, the entire body becomes unreachable.
     */
    static final boolean ENABLED = false;

    /**
     * Ideal publish method - single branch check, zero overhead when disabled.
     *
     * <p><strong>Fast path (disabled):</strong>
     *
     * <ul>
     *   <li>Single boolean check (branch prediction friendly)
     *   <li>No method calls
     *   <li>No memory accesses
     *   <li>No allocations
     *   <li>Compiles to ~2 CPU instructions
     * </ul>
     *
     * <p><strong>Expected assembly:</strong>
     *
     * <pre>
     *   # When ENABLED = false (JIT optimizes to:)
     *   return
     *
     *   # When ENABLED = true (runtime check):
     *   mov eax, [ENABLED]
     *   test eax, eax
     *   je   epilog
     *   ; ... event delivery ...
     *   epilog:
     *   ret
     * </pre>
     *
     * @param event the event to publish (unused when disabled)
     */
    public void publish(Object event) {
        if (!ENABLED) {
            return; // Single branch - should be <50ns
        }
        // Slow path: event delivery code goes here
        // This entire block is eliminated by JIT when ENABLED = false
    }

    /**
     * Creates an ideal event bus instance.
     *
     * @return a new instance
     */
    public static IdealEventBus create() {
        return new IdealEventBus();
    }
}
