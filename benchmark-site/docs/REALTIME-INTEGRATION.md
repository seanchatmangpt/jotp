# Real-Time Integration Guide

This guide explains the real-time integration between benchmark data and XState-based flow visualization.

## Architecture Overview

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   Benchmark     │────▶│   Connector      │────▶│  XState Machine │
│   Results       │     │  (WebSocket/SSE) │     │  (flow-machine) │
└─────────────────┘     └──────────────────┘     └─────────────────┘
                                                              │
                                                              ▼
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   Metrics       │◀────│   React Hooks    │◀────│   Flow          │
│   Overlay       │     │  (useMachine)   │     │   Visualizer    │
└─────────────────┘     └──────────────────┘     └─────────────────┘
```

## Components

### 1. State Machines (`lib/state-machines/`)

#### `flow-machine.ts`
Core XState machine for flow orchestration:

**States:**
- `idle`: Initial state, no flow loaded
- `loading`: Flow data being fetched
- `running`: Active flow with live updates
- `paused`: Flow suspended but receiving updates

**Context:**
- `currentFlow`: Active flow ID
- `flowData`: Flow structure (nodes, edges)
- `selectedNode`: Currently selected node
- `metrics`: Real-time metrics per node
- `animationSpeed`: Playback speed multiplier
- `metricsHistory`: Historical metrics for sparklines

**Events:**
- `LOAD_FLOW`: Load flow structure
- `START`: Start flow execution
- `PAUSE`: Pause flow
- `RESUME`: Resume flow
- `RESET`: Reset to initial state
- `SELECT_NODE`: Select node for details
- `UPDATE_METRICS`: Receive live metrics
- `SET_SPEED`: Change animation speed

#### `selection-machine.ts`
Manages component selection state:

**Features:**
- Single/multi-select modes
- Selection history (undo capability)
- Type-based filtering (primitive/pattern/system)
- Deselection tracking

**Helper Functions:**
- `getSelectedIds()`: Get selected node IDs
- `getSelectedItems()`: Get selected node objects
- `isSelected()`: Check if node selected
- `getSelectionByType()`: Filter by type

### 2. Real-Time Connectors (`lib/realtime/`)

#### `benchmark-connector.ts`
Three connector types for different scenarios:

**WebSocket Connector** (`BenchmarkConnector`):
- Bidirectional communication
- Auto-reconnection with exponential backoff
- Message buffering (max 1000 events)
- Connection status callbacks

**SSE Connector** (`SSEConnector`):
- One-way streaming from server
- Automatic reconnection
- Lower overhead than WebSocket
- Best for read-only metrics

**Polling Connector** (`PollingConnector`):
- REST API polling
- Configurable interval (default 5s)
- Data change detection
- Works with any HTTP endpoint

**Usage:**
```typescript
const connector = createBenchmarkConnector('websocket', {
  url: 'ws://localhost:8080/benchmarks',
  reconnectInterval: 1000,
  maxReconnectAttempts: 10,
  onMessage: (event) => console.log(event),
  onStatusChange: (status) => console.log(status)
});

await connector.connect();
```

### 3. Flow Components (`components/flows/`)

#### `metrics-overlay.tsx`
Real-time metrics display overlay:

**Features:**
- Live throughput (ops/sec)
- Latency percentiles (p50, p95, p99)
- Error rate with color coding
- CPU and memory usage
- Sparkline mini-charts for trends
- Progress bars for memory usage
- Auto-positioning near nodes

**Metric Cards:**
```typescript
<MetricCard
  label="Throughput"
  value={metrics.throughput.toLocaleString()}
  unit="ops/s"
  trend={getTrend('throughput')}
  color="#10b981"
/>
```

#### `flow-controls.tsx`
Playback and speed controls:

**Features:**
- Play/Pause toggle
- Stop and reset
- Step forward/backward
- Speed presets (0.25x - 4x)
- Custom speed slider (0.1x - 5x)
- Status indicator with animation

**Mini Version:**
```typescript
<MiniFlowControls
  isPlaying={isPlaying}
  onPlayPause={handlePlayPause}
  speed={speed}
  onSpeedChange={setSpeed}
/>
```

#### `flow-visualizer.tsx`
Main orchestration component:

**Features:**
- Flow node rendering with grid layout
- Real-time metric updates
- Node selection and hover
- Connection lines between nodes
- Full integration with XState machines
- Responsive design

**Usage:**
```typescript
<FlowVisualizer
  flowId="supervisor-tree"
  connectorType="polling"
  connectorUrl="/api/benchmarks/metrics"
/>
```

## API Endpoints

### `/api/benchmarks/metrics`
Returns current benchmark metrics.

**Query Parameters:**
- `node`: Return specific node metrics

**Response:**
```typescript
{
  "proc": {
    "timestamp": 1678900000000,
    "throughput": 125000,
    "latency": { "p50": 0.8, "p95": 1.2, "p99": 2.5 },
    "errorRate": 0.01,
    "memory": { "used": 268435456, "total": 1073741824 },
    "cpu": 35.5
  }
}
```

### `/api/flows/[flowId]`
Returns flow structure for visualization.

**Response:**
```typescript
{
  "id": "supervisor-tree",
  "name": "Supervisor Tree",
  "description": "OTP supervisor tree",
  "nodes": [
    {
      "id": "root-supervisor",
      "label": "Root Supervisor",
      "type": "system",
      "status": "running"
    }
  ],
  "edges": [
    {
      "from": "root-supervisor",
      "to": "child-supervisor-1",
      "label": "supervises"
    }
  ]
}
```

## Integration Examples

### Example 1: Basic Flow Visualization

```typescript
'use client';

import { FlowVisualizer } from '@/components/flows/flow-visualizer';

export default function Page() {
  return (
    <div className="w-full h-screen p-4">
      <FlowVisualizer
        flowId="supervisor-tree"
        connectorType="polling"
      />
    </div>
  );
}
```

### Example 2: Custom Metrics Handler

```typescript
'use client';

import { useMachine } from '@xstate/react';
import { flowMachine } from '@/lib/state-machines/flow-machine';
import { useBenchmarkConnector } from '@/lib/realtime/benchmark-connector';

export function CustomFlowViewer() {
  const [state, send] = useMachine(flowMachine);

  useBenchmarkConnector({
    type: 'websocket',
    url: 'ws://localhost:8080/benchmarks',
    onMetrics: (nodeId, metrics) => {
      // Custom processing
      console.log(`${nodeId}: ${metrics.throughput} ops/s`);

      // Update machine
      send({
        type: 'UPDATE_METRICS',
        nodeId,
        metrics
      });
    }
  });

  return (
    <div>
      <h1>Custom Flow Viewer</h1>
      <p>Status: {state.value}</p>
    </div>
  );
}
```

### Example 3: Multi-Flow Dashboard

```typescript
'use client';

import { FlowVisualizer } from '@/components/flows/flow-visualizer';

export function Dashboard() {
  const flows = ['supervisor-tree', 'state-machine-flow', 'pipeline'];

  return (
    <div className="grid grid-cols-2 gap-4">
      {flows.map(flowId => (
        <div key={flowId} className="aspect-video">
          <FlowVisualizer flowId={flowId} />
        </div>
      ))}
    </div>
  );
}
```

## Performance Considerations

### Metrics Update Frequency
- **Polling**: 5-10 seconds (configurable)
- **SSE**: 1-5 seconds (server push)
- **WebSocket**: Real-time (sub-second)

### Data Optimization
- Debounce rapid updates (100-200ms)
- Buffer historical metrics (max 100 points)
- Lazy load sparkline data
- Virtualize large node lists

### Memory Management
- Limit metrics history (default 100 points)
- Clear disconnected connectors
- Use React.memo for expensive renders
- Implement pagination for large flows

## Troubleshooting

### Connection Issues
```typescript
// Check connector status
const { status } = useBenchmarkConnector({ ... });

console.log('Status:', status); // 'connecting' | 'connected' | 'disconnected' | 'error'
```

### Missing Metrics
```typescript
// Verify metrics are available
const metrics = flowState.context.metrics[nodeId];

if (!metrics) {
  console.log('No metrics for', nodeId);
}
```

### State Synchronization
```typescript
// Ensure machine is in correct state
if (flowState.matches('running')) {
  // Safe to send updates
  send({ type: 'UPDATE_METRICS', nodeId, metrics });
}
```

## Future Enhancements

- [ ] React Flow integration for advanced layouts
- [ ] Custom node types with specialized visualizations
- [ ] Time-based playback controls (scrubber)
- [ ] Export/import flow configurations
- [ ] Multi-user collaborative editing
- [ ] Alert thresholds and notifications
- [ ] Performance anomaly detection
- [ ] Historical replay capabilities

## Related Documentation

- [XState v5 Documentation](https://xstate.js.org/docs/)
- [Framer Motion](https://www.framer.com/motion/)
- [WebSocket API](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket)
- [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)
