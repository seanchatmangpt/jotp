import { Node, Edge } from '@xyflow/react';

export type FlowNode = Node<{
  label: string;
  [key: string]: any;
}>;

export type FlowEdge = Edge;

/**
 * Create a grid layout for nodes
 */
export function createGridLayout(
  nodes: Array<{ id: string; label: string }>,
  columns: number,
  nodeWidth: number = 200,
  nodeHeight: number = 100,
  gap: number = 50
): FlowNode[] {
  return nodes.map((node, index) => ({
    id: node.id,
    type: 'benchmark',
    data: { label: node.label },
    position: {
      x: (index % columns) * (nodeWidth + gap),
      y: Math.floor(index / columns) * (nodeHeight + gap)
    }
  }));
}

/**
 * Create edges between sequential nodes
 */
export function createSequentialEdges(
  nodeIds: string[],
  options?: {
    animated?: boolean;
    style?: React.CSSProperties;
    label?: string;
  }
): FlowEdge[] {
  const edges: FlowEdge[] = [];

  for (let i = 0; i < nodeIds.length - 1; i++) {
    edges.push({
      id: `e${nodeIds[i]}-${nodeIds[i + 1]}`,
      source: nodeIds[i],
      target: nodeIds[i + 1],
      animated: options?.animated ?? false,
      style: options?.style,
      label: options?.label
    });
  }

  return edges;
}

/**
 * Create a hierarchical layout (tree structure)
 */
export function createHierarchicalLayout(
  hierarchy: {
    id: string;
    label: string;
    children?: Array<{ id: string; label: string }>;
  }[],
  levelHeight: number = 150,
  siblingGap: number = 220
): { nodes: FlowNode[]; edges: FlowEdge[] } {
  const nodes: FlowNode[] = [];
  const edges: FlowEdge[] = [];

  hierarchy.forEach((root, rootIndex) => {
    // Add root node
    nodes.push({
      id: root.id,
      type: 'component',
      data: { label: root.label },
      position: { x: rootIndex * siblingGap * 2, y: 0 }
    });

    // Add children
    root.children?.forEach((child, childIndex) => {
      nodes.push({
        id: child.id,
        type: 'component',
        data: { label: child.label },
        position: {
          x: rootIndex * siblingGap * 2 + childIndex * siblingGap,
          y: levelHeight
        }
      });

      edges.push({
        id: `e${root.id}-${child.id}`,
        source: root.id,
        target: child.id,
        animated: true,
        style: { stroke: '#3b82f6' }
      });
    });
  });

  return { nodes, edges };
}

/**
 * Generate random benchmark metrics
 */
export function generateBenchmarkMetrics() {
  return {
    throughput: Math.random() * 10000 + 1000,
    latency: Math.random() * 100 + 1,
    errorRate: Math.random() * 0.05,
    duration: Math.random() * 5000 + 1000
  };
}

/**
 * Create benchmark pipeline nodes
 */
export function createBenchmarkPipeline(): { nodes: FlowNode[]; edges: FlowEdge[] } {
  const stages = [
    { id: 'config', label: 'Configuration', type: 'benchmark' as const },
    { id: 'warmup', label: 'Warm-up', type: 'benchmark' as const },
    { id: 'execute', label: 'Execution', type: 'benchmark' as const },
    { id: 'collect', label: 'Data Collection', type: 'benchmark' as const },
    { id: 'analyze', label: 'Analysis', type: 'benchmark' as const },
    { id: 'report', label: 'Report Generation', type: 'benchmark' as const }
  ];

  const nodes: FlowNode[] = stages.map((stage, index) => ({
    id: stage.id,
    type: stage.type,
    data: {
      label: stage.label,
      status: index === 0 ? 'running' : 'pending',
      description: getStageDescription(stage.id),
      ...generateBenchmarkMetrics()
    },
    position: { x: index * 250, y: 0 }
  }));

  const edges: FlowEdge[] = createSequentialEdges(
    stages.map(s => s.id),
    { animated: true }
  );

  return { nodes, edges };
}

/**
 * Create architecture diagram nodes
 */
export function createArchitectureDiagram(): { nodes: FlowNode[]; edges: FlowEdge[] } {
  const nodes: FlowNode[] = [
    {
      id: 'proc',
      type: 'component',
      data: {
        label: 'Proc',
        type: 'proc',
        state: 'active',
        status: 'active',
        processes: 1000
      },
      position: { x: 250, y: 0 }
    },
    {
      id: 'supervisor',
      type: 'component',
      data: {
        label: 'Supervisor',
        type: 'supervisor',
        status: 'active',
        children: ['proc', 'state-machine']
      },
      position: { x: 250, y: 200 }
    },
    {
      id: 'state-machine',
      type: 'component',
      data: {
        label: 'StateMachine',
        type: 'state-machine',
        state: 'processing',
        status: 'active'
      },
      position: { x: 550, y: 0 }
    },
    {
      id: 'event-bus',
      type: 'component',
      data: {
        label: 'FrameworkEventBus',
        type: 'event-bus',
        status: 'active'
      },
      position: { x: 50, y: 100 }
    },
    {
      id: 'registry',
      type: 'component',
      data: {
        label: 'ProcRegistry',
        type: 'registry',
        status: 'active'
      },
      position: { x: 50, y: 250 }
    }
  ];

  const edges: FlowEdge[] = [
    { id: 'e-supervisor-proc', source: 'supervisor', target: 'proc', animated: true },
    { id: 'e-supervisor-sm', source: 'supervisor', target: 'state-machine', animated: true },
    { id: 'e-eventbus-proc', source: 'event-bus', target: 'proc', animated: true },
    { id: 'e-eventbus-sm', source: 'event-bus', target: 'state-machine', animated: true },
    { id: 'e-registry-proc', source: 'registry', target: 'proc', animated: true }
  ];

  return { nodes, edges };
}

/**
 * Create performance flow nodes with timing
 */
export function createPerformanceFlow(): { nodes: FlowNode[]; edges: FlowEdge[] } {
  const nodes: FlowNode[] = [
    {
      id: 'message-receive',
      type: 'timing',
      data: {
        label: 'Message Receive',
        startTime: 0,
        endTime: 0.5,
        duration: 0.5,
        isHotPath: true,
        children: [
          { label: 'Queue Dequeue', duration: 0.3 },
          { label: 'Validation', duration: 0.2 }
        ]
      },
      position: { x: 0, y: 0 }
    },
    {
      id: 'state-transition',
      type: 'timing',
      data: {
        label: 'State Transition',
        startTime: 0.5,
        endTime: 2.0,
        duration: 1.5,
        isHotPath: true,
        children: [
          { label: 'Pattern Match', duration: 0.5 },
          { label: 'State Update', duration: 0.7 },
          { label: 'Action Exec', duration: 0.3 }
        ]
      },
      position: { x: 350, y: 0 }
    },
    {
      id: 'message-send',
      type: 'timing',
      data: {
        label: 'Message Send',
        startTime: 2.0,
        endTime: 2.3,
        duration: 0.3,
        isHotPath: true,
        children: [
          { label: 'Queue Enqueue', duration: 0.2 },
          { label: 'Notification', duration: 0.1 }
        ]
      },
      position: { x: 700, y: 0 }
    },
    {
      id: 'monitoring',
      type: 'timing',
      data: {
        label: 'Monitoring',
        startTime: 2.3,
        endTime: 3.0,
        duration: 0.7,
        isHotPath: false,
        children: [
          { label: 'Metrics Collection', duration: 0.5 },
          { label: 'Event Publish', duration: 0.2 }
        ]
      },
      position: { x: 1050, y: 0 }
    }
  ];

  const edges: FlowEdge[] = createSequentialEdges(
    ['message-receive', 'state-transition', 'message-send', 'monitoring'],
    { animated: true, style: { stroke: '#22c55e' } }
  );

  return { nodes, edges };
}

function getStageDescription(stageId: string): string {
  const descriptions: Record<string, string> = {
    config: 'Load and validate benchmark configuration',
    warmup: 'JVM warm-up and JIT compilation',
    execute: 'Run benchmark iterations',
    collect: 'Gather performance metrics',
    analyze: 'Statistical analysis of results',
    report: 'Generate visualization and reports'
  };
  return descriptions[stageId] || '';
}
