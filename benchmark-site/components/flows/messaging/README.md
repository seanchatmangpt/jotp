# Messaging Pattern Flows

Interactive visualizations of enterprise messaging patterns for JOTP.

## Overview

This directory contains 6 React Flow components that visualize key messaging patterns from the Enterprise Integration Patterns catalog. Each flow demonstrates real-time message routing, transformation, and processing with animated tokens, metrics, and Java implementation examples.

## Available Flows

### 1. Point-to-Point Flow (`point-to-point-flow.tsx`)

**Pattern**: One-to-one message delivery with competing consumers

**Features**:
- Animated message flow from producer to consumer via queue
- Competing consumers pattern with load balancing
- Real-time metrics: message rate, queue depth, processing time
- Load balance distribution visualization

**Use Cases**: Order processing, task queues, job distribution

**Key Visual Elements**:
- 📤 Producer (green) - generates messages
- 📨 Channel (blue) - queues messages
- 📥 Consumers (purple) - compete for messages
- Animated edges show message routing

### 2. Publish-Subscribe Flow (`publish-subscribe-flow.tsx`)

**Pattern**: Topic-based broadcast to multiple subscribers

**Features**:
- Topic hierarchy (orders.created, orders.updated, payments.completed)
- Subscriber isolation - each processes independently
- Fan-out animation showing broadcast to all subscribers
- Subscriber delivery counts per topic

**Use Cases**: Event notifications, data synchronization, analytics pipelines

**Key Visual Elements**:
- 📢 Publishers (green) - broadcast to topics
- 📡 Topics (blue) - organize messages by subject
- 👂 Subscribers (purple) - receive topic broadcasts
- Multiple edges from single topic to many subscribers

### 3. Content-Based Router Flow (`content-based-router-flow.tsx`)

**Pattern**: Route messages based on content and conditions

**Features**:
- Conditional routing (priority: GOLD, SILVER, BRONZE)
- Routing table with distribution counts
- Current route indicator with color coding
- Dynamic edge animation showing selected path

**Use Cases**: Priority routing, geographic routing, order type routing

**Key Visual Elements**:
- 📥 Input (green) - receives messages
- 🔀 Router (purple) - evaluates conditions
- 📨 Channels (blue) - condition-specific destinations
- Edge labels show routing conditions

### 4. Scatter-Gather Flow (`scatter-gather-flow.tsx`)

**Pattern**: Broadcast to multiple recipients and aggregate responses

**Features**:
- Two-phase animation: Scatter → Gather
- Response progress bar in aggregator
- Parallel processing visualization
- Timeout handling and partial results

**Use Cases**: Best-price queries, parallel API calls, data enrichment

**Key Visual Elements**:
- 📤 Requester (green) - initiates request
- 📡 Scatter (purple) - broadcasts to all
- 🔧 Recipients (blue) - process in parallel
- 📊 Aggregator (orange) - collects responses

### 5. Aggregator Flow (`aggregator-flow.tsx`)

**Pattern**: Aggregate related messages by correlation ID

**Features**:
- Correlation ID matching (e.g., Order ID)
- Multiple completion strategies (count, timeout, sequence)
- Message buffer visualization with message preview
- Flush animation when aggregation complete

**Use Cases**: Order assembly, batch processing, report generation

**Key Visual Elements**:
- 📥 Inputs (green) - related message streams
- 📊 Aggregator (purple) - buffers by correlation ID
- 📤 Output (blue) - emits aggregated result
- Message list shows buffered messages

### 6. Wire Tap Flow (`wire-tap-flow.tsx`)

**Pattern**: Non-invasive monitoring of message flows

**Features**:
- Main flow vs. tap flow visualization
- Parallel processing (non-blocking)
- Tap overhead measurement (~1-2ms)
- Message copying without consumption

**Use Cases**: Monitoring, logging, analytics, debugging, audit trails

**Key Visual Elements**:
- Solid green edges - main production flow
- Dashed orange edges - monitoring tap flow
- 🔌 Wire Tap node - copies messages
- Yellow monitoring consumer - receives copies

## Common Features

All flows include:

1. **Interactive Controls**
   - Start/Stop automated message generation
   - Manual message sending buttons
   - Real-time flow control

2. **Metrics Dashboard**
   - Total message counts
   - Queue depths / buffer sizes
   - Average processing times
   - Distribution statistics

3. **Visual Feedback**
   - Animated message tokens
   - Color-coded node types
   - Edge animations for active paths
   - Status badges (idle, running, complete)

4. **Code Examples**
   - Java implementation snippets
   - JOTP primitive usage
   - Real-world usage patterns

5. **Pattern Documentation**
   - Description and purpose
   - Key characteristics
   - Use case examples
   - Enterprise integration context

## Node Type Color Coding

- **Green** (`bg-gradient-to-br from-green-50 to-green-100`): Producers, inputs, sources
- **Blue** (`bg-gradient-to-br from-blue-50 to-blue-100`): Channels, endpoints, destinations
- **Purple** (`bg-gradient-to-br from-purple-50 to-purple-100`): Routers, transformers, intermediaries
- **Orange** (`bg-gradient-to-br from-orange-50 to-orange-100`): Aggregators, collectors
- **Yellow** (`bg-gradient-to-br from-yellow-50 to-yellow-100`): Monitoring, tapping

## Edge Styling

- **Solid thick lines (3px)**: Main production flow
- **Dashed lines (2px)**: Tap/monitoring flow
- **Animated edges**: Active message transmission
- **Color coding**: Green (input), Blue (output), Orange (tap)

## Usage Example

```tsx
import { PointToPointFlow } from '@/components/flows/messaging';

export function MessagingPatternsPage() {
  return (
    <div className="space-y-8">
      <PointToPointFlow />
      <PublishSubscribeFlow />
      <ContentBasedRouterFlow />
      <ScatterGatherFlow />
      <AggregatorFlow />
      <WireTapFlow />
    </div>
  );
}
```

## Technical Implementation

### Dependencies

- `@xyflow/react` - React Flow for graph visualization
- `@/components/ui/card` - shadcn/ui Card component
- `@/components/ui/button` - shadcn/ui Button component
- `@/components/ui/badge` - shadcn/ui Badge component

### State Management

Each flow uses:
- `useNodesState` - React Flow node management
- `useEdgesState` - React Flow edge management
- `useState` - Metrics and control state
- `useCallback` - Event handlers
- `useEffect` - Automated message generation

### Animation Pattern

1. **Phase 1**: Animate source → intermediate
2. **Phase 2**: Animate intermediate → destination
3. **Phase 3**: Update metrics and reset animations

## Integration with JOTP

These flows demonstrate JOTP's implementation of Enterprise Integration Patterns:

- **Point-to-Point**: `PointToPointChannel<T>` with competing consumers
- **Pub/Sub**: `PublishSubscribeChannel<T>` with topic-based routing
- **Content Router**: `ContentBasedRouter<T>` with pattern matching
- **Scatter-Gather**: `ScatterGather<T>` with parallel execution
- **Aggregator**: `Aggregator<T>` with correlation ID grouping
- **Wire Tap**: `WireTap.tap()` for non-invasive monitoring

## Performance Characteristics

- **Rendering**: React Flow with 5-15 nodes, 4-10 edges
- **Animation**: CSS-based edge animations (60fps)
- **State Updates**: React state batching for smooth UI
- **Metrics**: Real-time updates with minimal overhead

## Future Enhancements

Potential additions:
- Message content inspection (click to view payload)
- Drag-and-drop message routing
- Custom routing condition builder
- Performance benchmarking mode
- Export flow as diagram/image
- Integration with live JOTP processes

## References

- Enterprise Integration Patterns (Gregor Hohpe, Bobby Woolf)
- JOTP Documentation: `/Users/sac/jotp/docs/`
- React Flow Documentation: https://reactflow.dev
