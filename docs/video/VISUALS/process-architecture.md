# Visual Aids for JOTP Video Tutorials

This document describes all visual aids, diagrams, and animations needed for the JOTP video tutorial series.

---

## Visual 1: Process Architecture Diagram

**Used In:** Video 2 (Your First Process)

**Type:** Animated diagram

**Duration:** 30 seconds

**Description:**
A comprehensive diagram showing the internal architecture of a JOTP process, with animated message flow.

**Components:**

1. **Mailbox Component** (left side)
   - Visual: Queue icon with vertical stack of message envelopes
   - Color: Blue (#3B82F6)
   - Label: "Mailbox" (above, bold white text)
   - Animation: Messages enter from left, queue in mailbox

2. **Handler Component** (center)
   - Visual: Gear/cog icon with function notation
   - Text: "f(state, msg) → newState"
   - Color: Green (#10B981)
   - Label: "Handler" (above, bold white text)
   - Animation: Gear rotates when processing message

3. **State Component** (right side)
   - Visual: Database icon or storage cylinder
   - Text: "Counter(value=8)"
   - Color: Purple (#8B5CF6)
   - Label: "State" (above, bold white text)
   - Animation: Updates when new state is returned

**Message Flow Animation:**
1. Message envelope (yellow) flies in from left into Mailbox (1s)
2. Handler pulls message from Mailbox (1s)
3. Handler processes message (gear rotates, 2s)
4. Handler returns new state (1s)
5. State component updates with new value (flash effect, 1s)
6. Loop repeats for next message (1s)

**Total:** 7 seconds per message, show 4-5 messages

**Transitions:**
- Fade in all components (0.5s)
- Start message flow loop
- Fade out (0.5s)

**Colors:**
- Background: Dark (#1F2937)
- Components: Blue, Green, Purple (as above)
- Messages: Yellow (#FBBF24)
- Arrows: White (#FFFFFF)

**Text:**
- Font: Inter or Roboto (sans-serif)
- Size: 18pt labels, 14pt code
- Color: White (#FFFFFF)

---

## Visual 2: Sealed Interface Pattern Matching

**Used In:** Video 2 (Your First Process)

**Type:** Animated code visualization

**Duration:** 25 seconds

**Description:**
Shows how sealed interfaces enable compile-time exhaustive pattern matching.

**Layout:** Split screen (left: code, right: visualization)

**Left Side (Code):**
```java
sealed interface CounterMsg permits Increment, Reset, Snapshot {}

record Increment(int by) implements CounterMsg {}
record Reset() implements CounterMsg {}
record Snapshot() implements CounterMsg {}

(state, msg) -> switch (msg) {
    case Increment(var by) -> new Counter(state.value() + by);
    case Reset _           -> new Counter(0);
    case Snapshot _        -> state;
}
```

**Right Side (Visualization):**

1. **Sealed Hierarchy Tree** (0-5s)
   - Root: "CounterMsg" (sealed interface)
   - Children: "Increment", "Reset", "Snapshot" (records)
   - Animation: Tree grows from root to children

2. **Pattern Matching Animation** (5-20s)
   - Highlight "Increment" in code
   - Show corresponding "Increment(var by)" case in switch
   - Green checkmark appears (compiler verifies it's handled)
   - Repeat for "Reset" and "Snapshot"

3. **Compiler Error Example** (20-25s)
   - Show "Decrement" record added to hierarchy
   - Show red squiggly line under switch expression
   - Popup: "Compiler Error: Missing case: Decrement"

**Transitions:**
- Fade in code (0.5s)
- Grow hierarchy tree (2s)
- Animate pattern matching (10s)
- Show compiler error (5s)
- Fade out (0.5s)

**Colors:**
- Background: Dark (#1F2937)
- Code syntax: Blue (types), Green (strings), Yellow (keywords)
- Hierarchy tree: Purple (#8B5CF6)
- Checkmark: Green (#10B981)
- Error: Red (#EF4444)

---

## Visual 3: Tell vs. Ask Comparison

**Used In:** Video 3 (Messaging Patterns)

**Type:** Split-screen animation

**Duration:** 25 seconds

**Description:**
Side-by-side comparison of tell() and ask() messaging patterns.

**Layout:** Split screen (left: tell(), right: ask())

**Left Side (tell):**
1. **Process A** (blue box)
2. **Process B** (green box)
3. **Message** (yellow envelope)

**Animation (tell):**
1. Message flies from Process A to Process B (1s)
2. Process B receives message (0.5s)
3. Process A continues immediately (no waiting)
4. Text: "tell() - Fire and Forget" (appears below)

**Right Side (ask):**
1. **Process A** (blue box)
2. **Process B** (green box)
3. **Request** (yellow envelope)
4. **Response** (green envelope)

**Animation (ask):**
1. Request flies from Process A to Process B (1s)
2. Process A shows "Waiting..." indicator
3. Process B processes request
4. Response flies back from Process B to Process A (1s)
5. Text: "ask() - Request/Reply" (appears below)

**Timeout Animation (ask only):**
- If no response within timeout:
  - Clock icon appears and counts down
  - After timeout: "TimeoutException" text appears in red
  - Process A stops waiting

**Comparison Table (appears below animations):**

| Feature | tell() | ask() |
|---------|--------|-------|
| Returns | void (immediate) | CompletableFuture<State> |
| Waits for response? | No | Yes (with timeout) |
| Use case | Notifications, events | Queries, requests |
| Backpressure? | No (mailbox fills) | Yes (timeout limits wait) |

**Transitions:**
- Fade in both sides (0.5s)
- Run tell() animation (3s)
- Run ask() animation (5s)
- Show timeout scenario (5s)
- Fade in comparison table (2s)
- Fade out (0.5s)

**Colors:**
- Background: Dark (#1F2937)
- Process A: Blue (#3B82F6)
- Process B: Green (#10B981)
- Messages: Yellow (#FBBF24)
- Responses: Green (#10B981)
- Errors: Red (#EF4444)

---

## Visual 4: Supervisor Restart Animation

**Used In:** Video 4 (Fault Tolerance)

**Type:** Animated diagram

**Duration:** 20 seconds

**Description:**
Shows how a supervisor detects crashes and restarts processes.

**Layout:**
- **Supervisor** (large box at top)
- **Child Processes** (3 boxes below)

**Animation Sequence:**

1. **Normal Operation** (0-5s)
   - Supervisor monitoring all children
   - Green "OK" status on all processes
   - Pulse animation: Supervisor checks health every second

2. **Process Crash** (5-8s)
   - Middle child process turns red
   - Explosion/break effect
   - Text: "Process crashed!" appears

3. **Supervisor Detection** (8-10s)
   - Supervisor detects crash (pulse turns red)
   - Text: "Crash detected in <1ms"

4. **Process Restart** (10-15s)
   - Supervisor creates new process instance
   - New process appears (green flash)
   - Text: "Process restarted in <500µs"

5. **Back to Normal** (15-20s)
   - All processes show green "OK" status
   - Supervisor pulse returns to normal (green)
   - Text: "System recovered"

**Labels:**
- Supervisor: "Supervisor (ONE_FOR_ONE)"
- Children: "Process A", "Process B", "Process C"
- Timers: Show elapsed time for each step

**Transitions:**
- Fade in all components (0.5s)
- Run crash/restart sequence (15s)
- Fade out (0.5s)

**Colors:**
- Background: Dark (#1F2937)
- Supervisor: Orange (#F59E0B)
- Processes (normal): Green (#10B981)
- Processes (crashed): Red (#EF4444)
- Pulse: Yellow (#FBBF24)

---

## Visual 5: Supervision Tree (Multi-Level)

**Used In:** Video 4 (Fault Tolerance)

**Type:** Animated tree structure

**Duration:** 25 seconds

**Description:**
Shows a hierarchical supervision tree with fault isolation.

**Layout:**
```
                    Root Supervisor
                           |
        +----------+-------+-------+----------+
        |          |       |       |          |
    Tenant A   Tenant B  Tenant C  Metrics   Audit
    Supervisor  Supervisor
    |       |
  Auth    Data
```

**Animation Sequence:**

1. **Grow Tree** (0-5s)
   - Root supervisor appears
   - Branches grow downward
   - Child supervisors and processes appear
   - All green (healthy)

2. **Tenant A Crash** (5-10s)
   - Tenant A Supervisor crashes (red)
   - All children under Tenant A turn red
   - Root supervisor detects crash

3. **Restart Tenant A** (10-15s)
   - Root supervisor restarts Tenant A Supervisor
   - Tenant A Supervisor restarts its children
   - All processes under Tenant A back to green
   - Text: "Tenant A restarted"

4. **Fault Isolation** (15-20s)
   - Highlight Tenant B, C, Metrics, Audit in yellow
   - Text: "Other tenants unaffected"
   - Show they remained green throughout

5. **Metrics Summary** (20-25s)
   - Show stats:
     - Downtime for Tenant A: <500ms
     - Downtime for others: 0ms
     - Total processes restarted: 4
     - Impact to users: Minimal

**Labels:**
- Root: "Root Supervisor"
- Level 1: "Tenant A", "Tenant B", "Tenant C", "Metrics", "Audit"
- Level 2 (under Tenant A): "Auth", "Data"
- Status indicators: "OK" (green), "CRASHED" (red)

**Transitions:**
- Fade in root (0.5s)
- Grow tree (4s)
- Animate crash and restart (10s)
- Highlight fault isolation (5s)
- Show metrics (2s)
- Fade out (0.5s)

**Colors:**
- Background: Dark (#1F2937)
- Supervisors: Orange (#F59E0B)
- Processes: Green (#10B981) / Red (#EF4444)
- Highlights: Yellow (#FBBF24)

---

## Visual 6: State Machine Diagram

**Used In:** Video 6 (State Machines)

**Type:** Animated state diagram

**Duration:** 30 seconds

**Description:**
Shows an order processing state machine with state transitions.

**Layout:**
```
    ┌─────────┐
    │ PENDING │
    └────┬────┘
         │ Pay
         ▼
    ┌─────────┐
    │  PAID   │
    └────┬────┘
         │ Ship
         ▼
    ┌─────────┐
    │ SHIPPED │
    └────┬────┘
         │ Confirm
         ▼
    ┌───────────┐
    │ DELIVERED │
    └───────────┘
```

**Animation Sequence:**

1. **Show States** (0-5s)
   - Fade in all states (PENDING, PAID, SHIPPED, DELIVERED)
   - Show transitions (arrows between states)
   - Label transitions with events

2. **Process Order** (5-25s)
   - Highlight PENDING state (yellow)
   - Show "Pay" event
   - Animate transition to PAID state (1s)
   - Highlight PAID state (yellow)
   - Show "Ship" event
   - Animate transition to SHIPPED state (1s)
   - Highlight SHIPPED state (yellow)
   - Show "Confirm" event
   - Animate transition to DELIVERED state (1s)
   - Highlight DELIVERED state (green, final)

3. **Show Events** (25-30s)
   - List events for each state:
     - PENDING: Pay, Cancel
     - PAID: Ship, Refund
     - SHIPPED: Confirm, Return
     - DELIVERED: (final state)

**Labels:**
- States: PENDING, PAID, SHIPPED, DELIVERED
- Transitions: Pay, Ship, Confirm
- Events: (as shown above)

**Transitions:**
- Fade in all states (2s)
- Draw arrows (1s)
- Animate state transitions (15s)
- Show event lists (5s)
- Fade out (0.5s)

**Colors:**
- Background: Dark (#1F2937)
- States: Blue (#3B82F6)
- Active state: Yellow (#FBBF24)
- Final state: Green (#10B981)
- Arrows: White (#FFFFFF)
- Text: White (#FFFFFF)

---

## Visual 7: Event Bus Diagram

**Used In:** Video 7 (Event-Driven Architecture)

**Type:** Animated pub-sub diagram

**Duration:** 25 seconds

**Description:**
Shows how EventManager broadcasts events to multiple subscribers.

**Layout:**
```
Publisher → EventManager → Subscriber 1
                          → Subscriber 2
                          → Subscriber 3
```

**Animation Sequence:**

1. **Setup** (0-5s)
   - Show EventManager (central hub)
   - Show Publisher (left)
   - Show 3 Subscribers (right)
   - Draw connections

2. **Subscribe** (5-10s)
   - Subscriber 1: "I want OrderCreated events"
   - Subscriber 2: "I want OrderCreated events"
   - Subscriber 3: "I want OrderCreated events"
   - Show subscription connections

3. **Publish Event** (10-15s)
   - Publisher sends OrderCreated event
   - Event flows to EventManager
   - EventManager broadcasts to all subscribers

4. **Handle Isolation** (15-20s)
   - Subscriber 2 crashes (turns red)
   - Subscribers 1 and 3 continue processing (green)
   - EventManager remains unaffected (green)
   - Text: "Handler crashes don't kill the bus"

5. **Unsubscribe** (20-25s)
   - Subscriber 1 unsubscribes
   - Connection disappears
   - Next event only goes to subscribers 2 and 3

**Labels:**
- EventManager: "EventManager<OrderEvent>"
- Publisher: "OrderService"
- Subscribers: "EmailService", "Analytics", "AuditLog"
- Events: "OrderCreated(orderId, customerId)"

**Transitions:**
- Fade in components (2s)
- Show subscriptions (3s)
- Publish and broadcast event (5s)
- Show crash isolation (5s)
- Show unsubscribe (3s)
- Fade out (0.5s)

**Colors:**
- Background: Dark (#1F2937)
- EventManager: Purple (#8B5CF6)
- Publisher: Blue (#3B82F6)
- Subscribers: Green (#10B981) / Red (#EF4444)
- Events: Yellow (#FBBF24)

---

## Visual 8: Performance Benchmark Chart

**Used In:** Video 1 (Introduction), Video 9 (Performance Tuning)

**Type:** Bar chart with animation

**Duration:** 20 seconds

**Description:**
Shows JOTP performance metrics compared to alternatives.

**Layout:**
Four bar charts side by side:
1. Memory per Process
2. Message Throughput
3. Process Restart Latency
4. Max Concurrent Processes

**Chart 1: Memory per Process**
```
JOTP:    ████ 3.9 KB
Erlang: █ 300 bytes
Go:      ██ 2 KB
```

**Chart 2: Throughput**
```
JOTP:    ████████ 4.6M msg/sec
Erlang:  ██████████ 45M msg/sec
Go:      ██████████████ 80M msg/sec
```

**Chart 3: Restart Latency**
```
JOTP:    █ 500 µs (p95)
Erlang:  █ 250 µs
Go:      N/A (no supervision)
```

**Chart 4: Max Concurrent**
```
JOTP:    ████████ 1M+ (tested)
Erlang:  ██████████████████ 134M
Go:      ████████ 10M
```

**Animation:**
1. Fade in chart axes (1s)
2. Animate bars growing (left to right, 1s each)
3. Fade in labels (0.5s)
4. Highlight JOTP bars in yellow (0.5s)
5. Fade out (0.5s)

**Labels:**
- Y-axis: Metric values
- X-axis: JOTP, Erlang, Go
- Chart titles: As above
- Values: Show actual numbers

**Colors:**
- Background: Dark (#1F2937)
- JOTP bars: Yellow (#FBBF24)
- Other bars: Blue (#3B82F6)
- Axes: Gray (#6B7280)
- Text: White (#FFFFFF)

---

## Visual 9: Distributed System Architecture

**Used In:** Video 8 (Distributed Systems)

**Type:** Animated network diagram

**Duration:** 30 seconds

**Description:**
Shows a distributed JOTP system with multiple JVMs.

**Layout:**
```
┌────────────┐         ┌────────────┐
│   JVM-1    │         │   JVM-2    │
│            │         │            │
│ ┌────────┐ │  gRPC   │ ┌────────┐ │
│ │Proc A  │ ├────────→│ │Proc B  │ │
│ └────────┘ │         │ └────────┘ │
└────────────┘         └────────────┘
```

**Animation Sequence:**

1. **Show JVMs** (0-5s)
   - JVM-1 appears (left)
   - JVM-2 appears (right)
   - Show processes inside each JVM

2. **Local Messaging** (5-10s)
   - Show messages within JVM-1 (fast)
   - Show messages within JVM-2 (fast)
   - Text: "Local messaging: <1 µs"

3. **Remote Messaging** (10-20s)
   - Process A sends message to Process B
   - Message travels through gRPC (slower)
   - Process B receives and responds
   - Text: "Remote messaging: 1-10 ms"

4. **Failure Scenario** (20-25s)
   - Network partition (dashed line between JVMs)
   - Process A can't reach Process B
   - Timeout exception
   - Text: "Network partition detected"

5. **Recovery** (25-30s)
   - Network heals
   - Messaging resumes
   - Text: "Automatic reconnection"

**Labels:**
- JVM-1: "jvm-1.example.com"
- JVM-2: "jvm-2.example.com"
- Processes: "OrderService", "PaymentService"
- Network: "gRPC over HTTP/2"

**Transitions:**
- Fade in JVMs (2s)
- Show local messaging (3s)
- Show remote messaging (8s)
- Show failure scenario (5s)
- Show recovery (3s)
- Fade out (0.5s)

**Colors:**
- Background: Dark (#1F2937)
- JVMs: Blue (#3B82F6)
- Processes: Green (#10B981)
- gRPC connection: Yellow (#FBBF24)
- Partition: Red (#EF4444) dashed line

---

## Production Notes

### Animation Tools

**Recommended Tools:**
- **After Effects** (Adobe) - Professional motion graphics
- **Lottie** (free) - JSON-based animation format
- **Keynote** (Apple) - Simple animations
- **PowerPoint** (Microsoft) - Basic animations

**Free Alternatives:**
- **Blender** - 3D and 2D animation
- **Synfig Studio** - 2D animation
- **OpenToonz** - Professional 2D animation

### Diagram Tools

**Recommended Tools:**
- **draw.io** (free) - Diagram creation
- **PlantUML** (free) - Text-to-diagram
- **Lucidchart** (paid) - Professional diagrams
- **Figma** (free) - UI design and diagrams

### Color Scheme

**JOTP Brand Colors:**
- Primary: #3B82F6 (Blue)
- Secondary: #10B981 (Green)
- Accent: #FBBF24 (Yellow)
- Warning: #F59E0B (Orange)
- Error: #EF4444 (Red)
- Dark: #1F2937 (Gray)
- Light: #F9FAFB (Off-white)

### Typography

**Font Families:**
- Headings: Inter, Roboto, or SF Pro Display
- Code: JetBrains Mono, Fira Code, or SF Mono
- Body: Inter or Roboto

**Font Sizes:**
- Titles: 32-48pt
- Headings: 24-32pt
- Body: 16-18pt
- Code: 14-16pt
- Captions: 12-14pt

### Accessibility

**Guidelines:**
- High contrast colors (WCAG AA: 4.5:1)
- Large, readable fonts
- Clear visual hierarchy
- Descriptive alt text
- Audio descriptions for key visuals

---

**Visual Aids Status:** Ready for Production
**Last Updated:** 2026-03-16
**Maintainer:** JOTP Community
