export const flowStyles = {
  background: '#f8fafc',
  connectionLineStyle: { stroke: '#cbd5e1', strokeWidth: 2 },
  defaultEdgeOptions: {
    animated: false,
    style: { stroke: '#cbd5e1', strokeWidth: 2 }
  },
  nodeStyles: {
    default: {
      background: '#ffffff',
      border: '#e2e8f0',
      color: '#1e293b'
    },
    hotPath: {
      background: '#dcfce7',
      border: '#22c55e',
      color: '#15803d'
    },
    coldPath: {
      background: '#f1f5f9',
      border: '#94a3b8',
      color: '#475569'
    }
  }
};

export const nodeTypes = {
  benchmark: 'benchmarkNode',
  metric: 'metricNode',
  component: 'componentNode',
  timing: 'timingNode'
};

export const edgeTypes = {
  default: 'defaultedge',
  smoothstep: 'smoothstepedge',
  step: 'stepedge'
};

export const markerEnd = {
  type: 'arrowclosed' as const,
  color: '#94a3b8',
  width: 20,
  height: 20
};

export const connectionLineStyle = {
  stroke: '#94a3b8',
  strokeWidth: 2
};
