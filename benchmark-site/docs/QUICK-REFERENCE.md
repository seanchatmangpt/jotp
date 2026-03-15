# Real-Time Integration Quick Reference

## Import Statements

```typescript
// State Machines
import { flowMachine, FlowMetrics } from '@/lib/state-machines/flow-machine';
import { selectionMachine } from '@/lib/state-machines/selection-machine';

// Connectors
import {
  createBenchmarkConnector,
  BenchmarkConnector,
  SSEConnector,
  PollingConnector
} from '@/lib/realtime/benchmark-connector';

// React Integration
import { useMachine } from '@xstate/react';

// Components
import { FlowVisualizer } from '@/components/flows/flow-visualizer';
import { MetricsOverlay } from '@/components/flows/metrics-overlay';
import { FlowControls } from '@/components/flows/flow-controls';
```

## Basic Usage

### Flow Visualization
```typescript
<FlowVisualizer flowId="supervisor-tree" connectorType="polling" />
```

### XState Machine
```typescript
const [state, send] = useMachine(flowMachine);

// Send events
send({ type: 'START', flowId: 'test' });
send({ type: 'PAUSE' });
send({ type: 'UPDATE_METRICS', nodeId: 'node1', metrics });
```

### Connector
```typescript
const connector = createBenchmarkConnector('websocket', {
  url: 'ws://localhost:8080/benchmarks',
  onMessage: (event) => console.log(event),
  onStatusChange: (status) => console.log(status)
});

await connector.connect();
```

## Flow Machine Events

| Event | Payload | Purpose |
|-------|---------|---------|
| `LOAD_FLOW` | `{ flowId }` | Load flow structure |
| `START` | `{ flowId }` | Start flow execution |
| `PAUSE` | - | Pause flow |
| `RESUME` | - | Resume flow |
| `RESET` | - | Reset to initial state |
| `SELECT_NODE` | `{ nodeId }` | Select node for details |
| `UPDATE_METRICS` | `{ nodeId, metrics }` | Update node metrics |
| `SET_SPEED` | `{ speed }` | Change animation speed |

## Flow Machine States

| State | Description | Valid Events |
|-------|-------------|--------------|
| `idle` | No flow loaded | `START`, `LOAD_FLOW` |
| `loading` | Loading flow data | - (async) |
| `running` | Active with updates | `PAUSE`, `SELECT_NODE`, `UPDATE_METRICS` |
| `paused` | Suspended | `RESUME`, `RESET`, `SELECT_NODE` |

## FlowMetrics Type

```typescript
interface FlowMetrics {
  timestamp: number
  throughput: number // ops/sec
  latency: {
    p50: number
    p95: number
    p99: number
  }
  errorRate: number // percentage
  memory: {
    used: number
    total: number
  }
  cpu: number // percentage
}
```

## Connector Types

| Type | Use Case | Bidirectional | Auto-Reconnect |
|------|----------|----------------|----------------|
| `websocket` | Real-time production | Yes | Yes |
| `sse` | Simple streaming | No | Yes |
| `polling` | REST API | No | N/A |

## API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/benchmarks/metrics` | GET | Get all/specific metrics |
| `/api/flows/[flowId]` | GET | Get flow structure |

## Common Patterns

### Check Machine State
```typescript
if (state.matches('running')) {
  // Flow is running
}

if (state.context.isPlaying) {
  // Flow is playing
}
```

### Access Context
```typescript
const metrics = state.context.metrics;
const selectedNode = state.context.selectedNode;
const speed = state.context.animationSpeed;
```

### Selection Machine
```typescript
const [selState, selSend] = useMachine(selectionMachine);

// Select item
selSend({ type: 'TOGGLE', item: { id: 'node1', type: 'primitive', label: 'Node 1' }});

// Enable multi-select
selSend({ type: 'SET_MULTI_SELECT', enabled: true });

// Get selected items
const selected = Array.from(selState.context.selectedItems.values());
```

### React Hook for Connector
```typescript
const { status, connector } = useBenchmarkConnector({
  type: 'websocket',
  url: 'ws://localhost:8080/benchmarks',
  onMetrics: (nodeId, metrics) => {
    send({ type: 'UPDATE_METRICS', nodeId, metrics });
  }
});
```

## Styling Classes

```typescript
// Status colors
'bg-green-500'  // Running/Good
'bg-yellow-500' // Paused/Warning
'bg-red-500'    // Error
'bg-gray-500'   // Idle

// Backgrounds
'bg-gray-900/95'  // Dark backdrop
'bg-gray-800/50'  // Semi-transparent
'backdrop-blur-md' // Blur effect

// Borders
'border-gray-700/50'  // Subtle border
'ring-2 ring-blue-500' // Selection ring
```

## Performance Tips

1. **Debounce Updates**
   ```typescript
   const debouncedUpdate = useMemo(
     () => debounce((metrics) => send({ type: 'UPDATE_METRICS', metrics }), 100),
     [send]
   );
   ```

2. **Limit History**
   ```typescript
   const [state, send] = useMachine(flowMachine, {
     context: { maxHistoryLength: 50 }
   });
   ```

3. **Memoize Components**
   ```typescript
   const MemoizedOverlay = React.memo(MetricsOverlay);
   ```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Metrics not updating | Check connector status: `status === 'connected'` |
| Type errors | Verify XState v5.28.0 is installed |
| Build failures | Run `npm install` and check dependencies |
| Missing metrics | Verify API endpoint returns data |
| State not changing | Check event type matches machine schema |

## File Locations

```
lib/state-machines/
  ├── flow-machine.ts
  └── selection-machine.ts

lib/realtime/
  └── benchmark-connector.ts

components/flows/
  ├── flow-visualizer.tsx
  ├── metrics-overlay.tsx
  └── flow-controls.tsx

app/api/
  ├── benchmarks/metrics/route.ts
  └── flows/[flowId]/route.ts

docs/
  ├── REALTIME-INTEGRATION.md
  ├── SETUP-GUIDE.md
  └── DEPENDENCIES.md
```

## Key Commands

```bash
# Install dependencies
npm install xstate@5.28.0 @xstate/react framer-motion

# Start dev server
npm run dev

# Build for production
npm run build

# Type check
npx tsc --noEmit
```

## Version Requirements

- **XState**: 5.28.0 (exact)
- **@xstate/react**: Latest v4.x
- **framer-motion**: Latest v11.x
- **Next.js**: v15.x
- **React**: v18.x
- **TypeScript**: v5.x

## Need Help?

1. Read full documentation: `docs/REALTIME-INTEGRATION.md`
2. Check setup guide: `docs/SETUP-GUIDE.md`
3. Review examples: `components/flows/flow-visualizer.tsx`
4. Test API: `curl http://localhost:3000/api/benchmarks/metrics`
