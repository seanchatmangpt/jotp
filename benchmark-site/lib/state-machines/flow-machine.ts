import { setup, assign, fromPromise } from 'xstate';

export interface FlowMetrics {
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

export interface FlowNode {
  id: string
  label: string
  type: 'primitive' | 'pattern' | 'system'
  status: 'idle' | 'running' | 'paused' | 'error' | 'completed'
  metrics?: FlowMetrics
}

export interface FlowData {
  id: string
  name: string
  description: string
  nodes: FlowNode[]
  edges: Array<{
    from: string
    to: string
    label?: string
  }>
}

export const flowMachine = setup({
  types: {
    context: {} as {
      currentFlow: string | null
      flowData: FlowData | null
      selectedNode: string | null
      metrics: Record<string, FlowMetrics>
      animationSpeed: number
      isPlaying: boolean
      error: string | null
      metricsHistory: Record<string, FlowMetrics[]>
      maxHistoryLength: number
    },
    events: {} as
      | { type: 'START'; flowId: string }
      | { type: 'PAUSE' }
      | { type: 'RESUME' }
      | { type: 'RESET' }
      | { type: 'SELECT_NODE'; nodeId: string }
      | { type: 'UPDATE_METRICS'; nodeId: string; metrics: FlowMetrics }
      | { type: 'SET_SPEED'; speed: number }
      | { type: 'LOAD_FLOW'; flowId: string }
      | { type: 'FLOW_LOADED'; flowData: FlowData }
      | { type: 'FLOW_ERROR'; error: string }
      | { type: 'CLEAR_ERROR' }
  },
  actors: {
    loadFlowData: fromPromise(async ({ input }: { input: { flowId: string } }) => {
      const response = await fetch(`/api/flows/${input.flowId}`);
      if (!response.ok) {
        throw new Error(`Failed to load flow: ${response.statusText}`);
      }
      return await response.json() as FlowData;
    })
  },
  actions: {
    setSelectedNode: assign({
      selectedNode: ({ event }) => {
        if (event.type === 'SELECT_NODE') {
          return event.nodeId;
        }
        return null;
      }
    }),
    updateMetrics: assign({
      metrics: ({ context, event }) => {
        if (event.type === 'UPDATE_METRICS') {
          return {
            ...context.metrics,
            [event.nodeId]: event.metrics
          };
        }
        return context.metrics;
      },
      metricsHistory: ({ context, event }) => {
        if (event.type === 'UPDATE_METRICS') {
          const history = context.metricsHistory[event.nodeId] || [];
          const newHistory = [...history, event.metrics].slice(-context.maxHistoryLength);
          return {
            ...context.metricsHistory,
            [event.nodeId]: newHistory
          };
        }
        return context.metricsHistory;
      }
    }),
    setAnimationSpeed: assign({
      animationSpeed: ({ event }) => {
        if (event.type === 'SET_SPEED') {
          return Math.max(0.1, Math.min(5.0, event.speed));
        }
        return 1.0;
      }
    }),
    setPlaying: assign({
      isPlaying: () => true
    }),
    setPaused: assign({
      isPlaying: () => false
    }),
    setFlowData: assign({
      flowData: ({ event }) => {
        if (event.type === 'FLOW_LOADED') {
          return event.flowData;
        }
        return null;
      },
      currentFlow: ({ event }) => {
        if (event.type === 'FLOW_LOADED') {
          return event.flowData.id;
        }
        return null;
      }
    }),
    setError: assign({
      error: ({ event }) => {
        if (event.type === 'FLOW_ERROR') {
          return event.error;
        }
        return null;
      }
    }),
    clearError: assign({
      error: () => null
    }),
    resetState: assign({
      selectedNode: () => null,
      metrics: () => ({}),
      isPlaying: () => false,
      error: () => null,
      metricsHistory: () => ({})
    })
  },
  guards: {
    hasFlowData: ({ context }) => context.flowData !== null,
    isNodeInFlow: ({ context, event }) => {
      if (event.type === 'SELECT_NODE' && context.flowData) {
        return context.flowData.nodes.some(n => n.id === event.nodeId);
      }
      return false;
    }
  }
}).createMachine({
  id: 'flow',
  initial: 'idle',
  context: {
    currentFlow: null,
    flowData: null,
    selectedNode: null,
    metrics: {},
    animationSpeed: 1.0,
    isPlaying: false,
    error: null,
    metricsHistory: {},
    maxHistoryLength: 100
  },
  states: {
    idle: {
      on: {
        START: {
          target: 'loading',
          actions: 'clearError'
        },
        LOAD_FLOW: {
          target: 'loading',
          actions: 'clearError'
        }
      }
    },
    loading: {
      invoke: {
        src: 'loadFlowData',
        input: ({ event }) => {
          if (event.type === 'LOAD_FLOW') {
            return { flowId: event.flowId };
          }
          return { flowId: 'default' };
        },
        onDone: {
          target: 'running',
          actions: 'setFlowData'
        },
        onError: {
          target: 'idle',
          actions: 'setError'
        }
      }
    },
    running: {
      entry: 'setPlaying',
      on: {
        PAUSE: 'paused',
        RESET: {
          target: 'idle',
          actions: 'resetState'
        },
        SELECT_NODE: {
          guard: 'isNodeInFlow',
          actions: 'setSelectedNode'
        },
        UPDATE_METRICS: {
          actions: 'updateMetrics'
        },
        SET_SPEED: {
          actions: 'setAnimationSpeed'
        }
      }
    },
    paused: {
      entry: 'setPaused',
      on: {
        RESUME: 'running',
        RESET: {
          target: 'idle',
          actions: 'resetState'
        },
        SELECT_NODE: {
          guard: 'isNodeInFlow',
          actions: 'setSelectedNode'
        },
        UPDATE_METRICS: {
          actions: 'updateMetrics'
        }
      }
    }
  }
});

export type FlowMachine = typeof flowMachine;
