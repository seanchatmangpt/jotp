# Flow Visualization with @xyflow/react

This document describes the interactive flow visualization components for the JOTP benchmark site.

## Overview

The benchmark site uses [@xyflow/react](https://reactflow.dev/) (formerly React Flow) to create interactive, animated diagrams for:

1. **Benchmark Pipeline Flow** - Visualize the benchmark execution process
2. **Architecture Diagram** - Interactive JOTP framework architecture
3. **Performance Flow** - Hot path analysis with timing annotations

## Components

### Custom Node Types

Located in `/components/flows/nodes/`:

#### BenchmarkNode
**File:** `benchmark-node.tsx`

Visualizes benchmark execution stages with status indicators.

**Features:**
- Status badges (pending, running, completed, failed)
- Duration display
- Metrics: throughput, latency, error rate
- Animated pulse effect for running status

**Data Interface:**
```typescript
interface BenchmarkNodeData {
  label: string;
  status?: 'pending' | 'running' | 'completed' | 'failed';
  duration?: number;
  description?: string;
  metrics?: {
    throughput?: number;
    latency?: number;
    errorRate?: number;
  };
}
```

**Usage:**
```tsx
<BenchmarkNode
  data={{
    label: 'Execution',
    status: 'running',
    duration: 2500,
    metrics: {
      throughput: 5000,
      latency: 45.2,
      errorRate: 0.001
    }
  }}
/>
```

#### MetricNode
**File:** `metric-node.tsx`

Displays performance metrics with threshold-based coloring.

**Features:**
- Multiple format types: number, percentage, bytes, throughput
- Threshold-based coloring (green/yellow/red)
- Change indicators (up/down arrows)
- Compact display for dashboards

**Data Interface:**
```typescript
interface MetricNodeData {
  label: string;
  value: number;
  unit: string;
  change?: number;
  threshold?: {
    warning: number;
    critical: number;
  };
  format?: 'number' | 'percentage' | 'bytes' | 'throughput';
}
```

#### ComponentNode
**File:** `component-node.tsx`

Represents JOTP framework components (Proc, Supervisor, StateMachine, etc.).

**Features:**
- Type-specific icons and colors
- Status indicator (active, idle, terminated, restarting)
- Process count for supervisors
- Children list with preview
- State display for state machines

**Component Types:**
- `proc` - Lightweight process (blue)
- `supervisor` - Supervisor tree (purple)
- `state-machine` - gen_statem (green)
- `event-bus` - FrameworkEventBus (orange)
- `registry` - ProcRegistry (yellow)
- `monitor` - ProcMonitor (pink)

**Data Interface:**
```typescript
interface ComponentNodeData {
  label: string;
  type: 'proc' | 'supervisor' | 'state-machine' | 'event-bus' | 'registry' | 'monitor';
  state?: string;
  processes?: number;
  children?: string[];
  status?: 'active' | 'idle' | 'terminated' | 'restarting';
}
```

#### TimingNode
**File:** `timing-node.tsx`

Shows execution timing with breakdown of child operations.

**Features:**
- Start and end timestamps
- Duration display
- Hot path indicator (green background)
- Child operation breakdown with timing bars
- Visual percentage representation

**Data Interface:**
```typescript
interface TimingNodeData {
  label: string;
  startTime: number;
  endTime: number;
  duration: number;
  isHotPath?: boolean;
  children?: Array<{
    label: string;
    duration: number;
  }>;
}
```

### Flow Components

Located in `/components/flows/`:

#### BenchmarkPipelineFlow
**File:** `benchmark-pipeline.tsx`

Interactive visualization of the benchmark execution pipeline.

**Stages:**
1. Configuration - Load and validate benchmark settings
2. Warm-up - JVM warm-up and JIT compilation
3. Execution - Run benchmark iterations
4. Data Collection - Gather performance metrics
5. Analysis - Statistical analysis
6. Report Generation - Create visualizations

**Features:**
- ▶️ "Run Pipeline" button with animated execution
- 🔄 "Reset" button to reset all stages
- Real-time status updates
- Simulated metrics during execution
- Animated edges between stages

**Usage:**
```tsx
import { BenchmarkPipelineFlow } from '@/components/flows/benchmark-pipeline';

<BenchmarkPipelineFlow />
```

#### ArchitectureDiagramFlow
**File:** `architecture-diagram.tsx`

Interactive JOTP framework architecture with component details.

**Components Displayed:**
- **Proc** - Core lightweight process primitive
- **Supervisor** - Fault tolerance supervision tree
- **StateMachine** - gen_statem implementation
- **FrameworkEventBus** - Event-driven observability
- **ProcRegistry** - Global process name registry

**Features:**
- Click nodes to see detailed descriptions
- Key features list for each component
- Animated edges showing data flow
- Status indicators for live systems
- MiniMap for navigation

**Usage:**
```tsx
import { ArchitectureDiagramFlow } from '@/components/flows/architecture-diagram';

<ArchitectureDiagramFlow />
```

#### PerformanceFlow
**File:** `performance-flow.tsx`

Hot path visualization with timing annotations.

**Stages:**
1. Message Receive (Hot Path) - 0.5ms
   - Queue Dequeue: 0.3ms
   - Validation: 0.2ms
2. State Transition (Hot Path) - 1.5ms
   - Pattern Match: 0.5ms
   - State Update: 0.7ms
   - Action Exec: 0.3ms
3. Message Send (Hot Path) - 0.3ms
   - Queue Enqueue: 0.2ms
   - Notification: 0.1ms
4. Monitoring (Cold Path) - 0.7ms
   - Metrics Collection: 0.5ms
   - Event Publish: 0.2ms

**Features:**
- Color-coded paths (green = hot/critical, gray = cold/non-critical)
- Timing labels on edges
- Child operation breakdown with visual bars
- Performance summary panel
- Total latency and hot path calculation

**Usage:**
```tsx
import { PerformanceFlow } from '@/components/flows/performance-flow';

<PerformanceFlow />
```

## Utilities

### flow-utils.ts
**File:** `/lib/flow-utils.ts`

Helper functions for creating flows:

**Functions:**

- `createGridLayout(nodes, columns)` - Arrange nodes in a grid
- `createSequentialEdges(nodeIds, options)` - Create edges between sequential nodes
- `createHierarchicalLayout(hierarchy)` - Create tree structure layout
- `generateBenchmarkMetrics()` - Generate random benchmark data
- `createBenchmarkPipeline()` - Create benchmark pipeline flow
- `createArchitectureDiagram()` - Create architecture diagram flow
- `createPerformanceFlow()` - Create performance flow with timing

**Example:**
```typescript
import { createBenchmarkPipeline } from '@/lib/flow-utils';

const { nodes, edges } = createBenchmarkPipeline();
```

### flow-styles.ts
**File:** `/lib/flow-styles.ts`

Centralized style definitions for flows:

**Exports:**
- `flowStyles` - Main style configuration
- `nodeTypes` - Node type mappings
- `edgeTypes` - Edge type mappings
- `markerEnd` - Arrow marker configuration
- `connectionLineStyle` - Default connection line style

## Styling

All nodes use Tailwind CSS classes for styling:

**Colors:**
- Blue (`bg-blue-50`, `border-blue-500`) - Proc nodes
- Purple (`bg-purple-50`, `border-purple-500`) - Supervisor nodes
- Green (`bg-green-50`, `border-green-500`) - StateMachine / Success
- Red (`bg-red-50`, `border-red-500`) - Failed status
- Orange (`bg-orange-50`, `border-orange-500`) - EventBus
- Yellow (`bg-yellow-50`, `border-yellow-500`) - Registry / Warning
- Pink (`bg-pink-50`, `border-pink-500`) - Monitor

**Animations:**
- `animate-pulse` - Running status
- Transitions on hover and color changes

## Integration with Benchmark Data

### Real-time Updates

The flows can be connected to real benchmark data:

```typescript
useEffect(() => {
  // Subscribe to benchmark updates
  const unsubscribe = benchmarkService.onUpdate((data) => {
    setNodes((nds) =>
      nds.map((node) => {
        if (node.id === data.stageId) {
          return {
            ...node,
            data: {
              ...node.data,
              status: data.status,
              metrics: data.metrics
            }
          };
        }
        return node;
      })
    );
  });

  return unsubscribe;
}, []);
```

### Custom Metrics

Nodes can display custom metrics:

```typescript
<BenchmarkNode
  data={{
    label: 'Custom Benchmark',
    metrics: {
      throughput: 10000,
      latency: 25.5,
      errorRate: 0.0001,
      // Custom metrics
      memoryUsage: 1024000,
      cpuUsage: 0.75
    }
  }}
/>
```

## Best Practices

1. **Keep nodes focused** - Each node should represent a single concept
2. **Use consistent colors** - Follow the established color scheme
3. **Provide context** - Include descriptions and labels
4. **Animate appropriately** - Use animations to draw attention, not distract
5. **Handle errors** - Show failed states clearly
6. **Responsive design** - Ensure flows work on different screen sizes
7. **Accessibility** - Add ARIA labels where appropriate

## Future Enhancements

- [ ] Export flows as images (PNG/SVG)
- [ ] Save/load flow configurations
- [ ] Real-time WebSocket updates
- [ ] Custom node templates
- [ ] Zoom-to-fit on node click
- [ ] Search and filter nodes
- [ ] Side panel with detailed metrics
- [ ] Comparison mode (before/after)
- [ ] Historical data view
- [ ] Integration with Grafana/Prometheus

## Resources

- [@xyflow/react Documentation](https://reactflow.dev/)
- [Examples](https://reactflow.dev/examples)
- [API Reference](https://reactflow.dev/api-reference)
- [JOTP Framework Docs](../README.md)

## License

These components are part of the JOTP project and use the same license.
