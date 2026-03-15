'use client';

import React, { useCallback } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  Edge,
  getBezierPath,
  EdgeLabelRenderer,
  BaseEdge
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

import { TimingNode } from './nodes/timing-node';
import { createPerformanceFlow } from '@/lib/flow-utils';

const nodeTypes: any = {
  timing: TimingNode
};

// Custom edge component to show timing annotations
function TimingEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  style = {},
  markerEnd,
  data
}: Edge & any) {
  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition
  });

  return (
    <>
      <BaseEdge id={id} path={edgePath} markerEnd={markerEnd} style={style} />
      <EdgeLabelRenderer>
        <div
          style={{
            position: 'absolute',
            transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
            fontSize: 12,
            fontWeight: 600,
            pointerEvents: 'all'
          }}
          className="bg-white px-2 py-1 rounded shadow text-xs"
        >
          {data?.label || ''}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

const edgeTypes = {
  timing: TimingEdge
};

export function PerformanceFlow() {
  const [nodes, setNodes, onNodesChange] = useNodesState(
    createPerformanceFlow().nodes
  );
  const [edges, setEdges, onEdgesChange] = useEdgesState(
    createPerformanceFlow().edges.map((edge) => ({
      ...edge,
      type: 'timing',
      data: {
        label: edge.source === 'message-receive' ? '0.5ms' :
               edge.source === 'state-transition' ? '1.5ms' :
               edge.source === 'message-send' ? '0.3ms' : '0.7ms'
      }
    }))
  );

  const totalLatency = nodes.reduce((sum, node) => sum + node.data.duration, 0);
  const hotPathLatency = nodes
    .filter((node) => node.data.isHotPath)
    .reduce((sum, node) => sum + node.data.duration, 0);

  return (
    <div className="w-full h-[600px] border rounded-lg overflow-hidden">
      <div className="absolute top-4 right-4 z-10 bg-white border rounded-lg shadow-lg p-4">
        <h3 className="font-bold text-sm mb-2">Performance Summary</h3>
        <div className="space-y-1 text-xs">
          <div className="flex justify-between gap-4">
            <span className="text-gray-600">Total Latency:</span>
            <span className="font-mono font-semibold">{totalLatency.toFixed(2)}ms</span>
          </div>
          <div className="flex justify-between gap-4">
            <span className="text-gray-600">Hot Path:</span>
            <span className="font-mono font-semibold text-green-600">{hotPathLatency.toFixed(2)}ms</span>
          </div>
          <div className="flex justify-between gap-4">
            <span className="text-gray-600">Overhead:</span>
            <span className="font-mono font-semibold text-gray-600">
              {((totalLatency - hotPathLatency)).toFixed(2)}ms
            </span>
          </div>
        </div>
        <div className="mt-3 pt-3 border-t">
          <div className="flex items-center gap-2 text-xs">
            <div className="w-3 h-3 rounded bg-green-500"></div>
            <span className="text-gray-600">Hot Path (Critical)</span>
          </div>
          <div className="flex items-center gap-2 text-xs mt-1">
            <div className="w-3 h-3 rounded bg-gray-400"></div>
            <span className="text-gray-600">Cold Path (Non-critical)</span>
          </div>
        </div>
      </div>

      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        fitView
        defaultEdgeOptions={{
          style: { stroke: '#22c55e', strokeWidth: 2 }
        }}
      >
        <Background />
        <Controls />
        <MiniMap />
      </ReactFlow>
    </div>
  );
}
