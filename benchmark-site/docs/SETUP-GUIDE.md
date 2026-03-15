# Real-Time Integration Setup Guide

## Installation

Install required dependencies for real-time integration:

```bash
cd benchmark-site
npm install xstate@5.28.0 @xstate/react framer-motion
```

## File Structure

```
benchmark-site/
├── lib/
│   ├── state-machines/
│   │   ├── flow-machine.ts          # XState flow orchestration
│   │   └── selection-machine.ts     # Component selection state
│   ├── realtime/
│   │   └── benchmark-connector.ts   # WebSocket/SSE/Polling connectors
│   └── hooks/
│       └── use-measure.ts           # DOM measurement hook
├── components/
│   └── flows/
│       ├── flow-visualizer.tsx      # Main orchestration component
│       ├── metrics-overlay.tsx      # Real-time metrics display
│       └── flow-controls.tsx        # Playback controls
├── app/
│   └── api/
│       ├── benchmarks/
│       │   └── metrics/
│       │       └── route.ts         # Mock metrics endpoint
│       └── flows/
│           └── [flowId]/
│               └── route.ts         # Flow data endpoint
└── docs/
    └── REALTIME-INTEGRATION.md      # Full documentation
```

## Quick Start

### 1. Create a Flow Visualization Page

Create `app/flows/page.tsx`:

```typescript
'use client';

import { FlowVisualizer } from '@/components/flows/flow-visualizer';

export default function FlowsPage() {
  return (
    <div className="w-full h-screen bg-gray-950 p-4">
      <FlowVisualizer
        flowId="supervisor-tree"
        connectorType="polling"
        connectorUrl="/api/benchmarks/metrics"
      />
    </div>
  );
}
```

### 2. Run the Development Server

```bash
npm run dev
```

### 3. View the Flow Visualization

Navigate to `http://localhost:3000/flows`

## Configuration

### WebSocket Configuration

For real-time WebSocket connections:

```typescript
const connector = createBenchmarkConnector('websocket', {
  url: 'ws://localhost:8080/benchmarks',
  reconnectInterval: 1000,        // 1 second
  maxReconnectAttempts: 10,       // 10 attempts
  bufferSize: 1000,               // 1000 messages
  onMessage: (event) => {
    console.log('Received:', event);
  },
  onError: (error) => {
    console.error('Error:', error);
  },
  onStatusChange: (status) => {
    console.log('Status:', status);
  }
});
```

### Polling Configuration

For polling-based updates:

```typescript
const connector = new PollingConnector({
  url: '/api/benchmarks/metrics',
  interval: 5000,                 // 5 seconds
  onMessage: (event) => {
    console.log('Received:', event);
  },
  onError: (error) => {
    console.error('Error:', error);
  }
});

connector.start();
```

### Flow Machine Configuration

Customize flow machine behavior:

```typescript
const [state, send] = useMachine(flowMachine, {
  context: {
    animationSpeed: 1.5,           // 1.5x speed
    maxHistoryLength: 200,         // 200 metrics points
    // ... other context values
  },
  actions: {
    // Custom actions
  }
});
```

## Mock Data Endpoints

The mock endpoints provide sample data for development:

### Get All Metrics

```bash
curl http://localhost:3000/api/benchmarks/metrics
```

### Get Specific Node Metrics

```bash
curl http://localhost:3000/api/benchmarks/metrics?node=proc
```

### Get Flow Structure

```bash
curl http://localhost:3000/api/flows/supervisor-tree
```

Available flows:
- `supervisor-tree`
- `state-machine-flow`
- `pipeline`

## TypeScript Types

Key types for integration:

```typescript
import {
  FlowMetrics,
  FlowNode,
  FlowData,
  FlowMachine
} from '@/lib/state-machines/flow-machine';

import {
  SelectionItem,
  SelectionMachine,
  getSelectedIds,
  getSelectedItems
} from '@/lib/state-machines/selection-machine';

import {
  BenchmarkEvent,
  BenchmarkConnector,
  SSEConnector,
  PollingConnector
} from '@/lib/realtime/benchmark-connector';
```

## Performance Tuning

### Reduce Polling Frequency

```typescript
const connector = new PollingConnector({
  url: '/api/benchmarks/metrics',
  interval: 10000,  // 10 seconds instead of 5
  onMessage: handleMetrics
});
```

### Limit Metrics History

```typescript
const [state, send] = useMachine(flowMachine, {
  context: {
    maxHistoryLength: 50  // Keep fewer historical points
  }
});
```

### Disable Animations

```typescript
<FlowVisualizer
  flowId="supervisor-tree"
  connectorType="polling"
  // Add animationSpeed={0} to disable
/>
```

## Testing

### Test Flow Machine

```typescript
import { flowMachine } from '@/lib/state-machines/flow-machine';

const machine = flowMachine.withContext({});

// Start loading
const state1 = machine.transition(undefined, { type: 'LOAD_FLOW', flowId: 'test' });

// Update metrics
const state2 = machine.transition(state1, {
  type: 'UPDATE_METRICS',
  nodeId: 'test-node',
  metrics: {
    timestamp: Date.now(),
    throughput: 1000,
    latency: { p50: 1, p95: 2, p99: 3 },
    errorRate: 0,
    memory: { used: 100, total: 1000 },
    cpu: 50
  }
});

console.log(state2.value); // 'loading'
console.log(state2.context.metrics); // { 'test-node': {...} }
```

### Test Connector

```typescript
import { createBenchmarkConnector } from '@/lib/realtime/benchmark-connector';

const connector = createBenchmarkConnector('polling', {
  url: '/api/benchmarks/metrics',
  onMessage: (event) => {
    console.log('Received event:', event);
  }
});

// Start polling
(connector as PollingConnector).start();

// Stop after 10 seconds
setTimeout(() => {
  (connector as PollingConnector).stop();
}, 10000);
```

## Troubleshooting

### Build Errors

**Error:** "Cannot find module 'xstate'"
```bash
npm install xstate@5.28.0
```

**Error:** "Cannot find module '@xstate/react'"
```bash
npm install @xstate/react
```

### Runtime Errors

**Error:** "WebSocket connection failed"
- Check if WebSocket server is running
- Verify WebSocket URL is correct
- Check browser console for CORS errors

**Error:** "Metrics not updating"
- Verify API endpoint is returning data
- Check browser network tab for failed requests
- Ensure connector is connected: `connectorStatus === 'connected'`

### Performance Issues

**Problem:** UI is slow with many nodes
- Reduce metrics history length
- Increase polling interval
- Use React.memo for expensive components
- Implement virtualization for node lists

**Problem:** High memory usage
- Clear metrics history periodically
- Disconnect connectors when not needed
- Limit max reconnection attempts

## Next Steps

1. **Implement Custom Flows**: Add your own flow definitions in `/api/flows/[flowId]/route.ts`

2. **Connect Real Benchmarks**: Replace mock endpoints with actual benchmark data

3. **Add Custom Metrics**: Extend `FlowMetrics` type for additional metrics

4. **Implement WebSocket Server**: Set up real WebSocket server for production

5. **Add Authentication**: Secure API endpoints for multi-user scenarios

## Additional Resources

- [XState v5 Documentation](https://xstate.js.org/docs/)
- [Framer Motion](https://www.framer.com/motion/)
- [Next.js API Routes](https://nextjs.org/docs/api-routes/introduction)
- [WebSocket API](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket)
- [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)
