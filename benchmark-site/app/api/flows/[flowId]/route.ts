/**
 * Flow data API endpoint
 * Returns flow structure for visualization
 */

import { NextRequest, NextResponse } from 'next/server';
import { FlowData } from '@/lib/state-machines/flow-machine';

// Mock flow data
const flows: Record<string, FlowData> = {
  'supervisor-tree': {
    id: 'supervisor-tree',
    name: 'Supervisor Tree',
    description: 'OTP supervisor tree with process monitoring',
    nodes: [
      {
        id: 'root-supervisor',
        label: 'Root Supervisor',
        type: 'system',
        status: 'running'
      },
      {
        id: 'child-supervisor-1',
        label: 'Child Supervisor 1',
        type: 'system',
        status: 'running'
      },
      {
        id: 'child-supervisor-2',
        label: 'Child Supervisor 2',
        type: 'system',
        status: 'running'
      },
      {
        id: 'worker-1',
        label: 'Worker Process 1',
        type: 'primitive',
        status: 'running'
      },
      {
        id: 'worker-2',
        label: 'Worker Process 2',
        type: 'primitive',
        status: 'running'
      },
      {
        id: 'worker-3',
        label: 'Worker Process 3',
        type: 'primitive',
        status: 'paused'
      },
      {
        id: 'worker-4',
        label: 'Worker Process 4',
        type: 'primitive',
        status: 'running'
      }
    ],
    edges: [
      { from: 'root-supervisor', to: 'child-supervisor-1', label: 'supervises' },
      { from: 'root-supervisor', to: 'child-supervisor-2', label: 'supervises' },
      { from: 'child-supervisor-1', to: 'worker-1', label: 'manages' },
      { from: 'child-supervisor-1', to: 'worker-2', label: 'manages' },
      { from: 'child-supervisor-2', to: 'worker-3', label: 'manages' },
      { from: 'child-supervisor-2', to: 'worker-4', label: 'manages' }
    ]
  },
  'state-machine-flow': {
    id: 'state-machine-flow',
    name: 'State Machine Flow',
    description: 'Complex state machine with multiple states',
    nodes: [
      {
        id: 'idle',
        label: 'Idle',
        type: 'primitive',
        status: 'idle'
      },
      {
        id: 'processing',
        label: 'Processing',
        type: 'primitive',
        status: 'running'
      },
      {
        id: 'validation',
        label: 'Validation',
        type: 'pattern',
        status: 'running'
      },
      {
        id: 'enrichment',
        label: 'Enrichment',
        type: 'pattern',
        status: 'running'
      },
      {
        id: 'completed',
        label: 'Completed',
        type: 'primitive',
        status: 'completed'
      },
      {
        id: 'error-handler',
        label: 'Error Handler',
        type: 'pattern',
        status: 'idle'
      }
    ],
    edges: [
      { from: 'idle', to: 'processing', label: 'start' },
      { from: 'processing', to: 'validation', label: 'validate' },
      { from: 'validation', to: 'enrichment', label: 'enrich' },
      { from: 'validation', to: 'error-handler', label: 'error' },
      { from: 'enrichment', to: 'completed', label: 'complete' },
      { from: 'error-handler', to: 'idle', label: 'retry' }
    ]
  },
  'pipeline': {
    id: 'pipeline',
    name: 'Processing Pipeline',
    description: 'Multi-stage processing pipeline with parallel execution',
    nodes: [
      {
        id: 'input',
        label: 'Input Queue',
        type: 'primitive',
        status: 'running'
      },
      {
        id: 'splitter',
        label: 'Splitter',
        type: 'pattern',
        status: 'running'
      },
      {
        id: 'worker-1',
        label: 'Parallel Worker 1',
        type: 'primitive',
        status: 'running'
      },
      {
        id: 'worker-2',
        label: 'Parallel Worker 2',
        type: 'primitive',
        status: 'running'
      },
      {
        id: 'worker-3',
        label: 'Parallel Worker 3',
        type: 'primitive',
        status: 'running'
      },
      {
        id: 'aggregator',
        label: 'Aggregator',
        type: 'pattern',
        status: 'running'
      },
      {
        id: 'output',
        label: 'Output Queue',
        type: 'primitive',
        status: 'running'
      }
    ],
    edges: [
      { from: 'input', to: 'splitter', label: 'items' },
      { from: 'splitter', to: 'worker-1', label: 'split 1/3' },
      { from: 'splitter', to: 'worker-2', label: 'split 2/3' },
      { from: 'splitter', to: 'worker-3', label: 'split 3/3' },
      { from: 'worker-1', to: 'aggregator', label: 'result 1' },
      { from: 'worker-2', to: 'aggregator', label: 'result 2' },
      { from: 'worker-3', to: 'aggregator', label: 'result 3' },
      { from: 'aggregator', to: 'output', label: 'aggregated' }
    ]
  }
};

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ flowId: string }> }
) {
  const { flowId } = await params;

  // Simulate network delay
  await new Promise(resolve => setTimeout(resolve, 100));

  const flow = flows[flowId];

  if (!flow) {
    return NextResponse.json(
      { error: 'Flow not found', availableFlows: Object.keys(flows) },
      { status: 404 }
    );
  }

  return NextResponse.json(flow);
}
