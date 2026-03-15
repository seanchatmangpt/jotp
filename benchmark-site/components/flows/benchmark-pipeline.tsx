'use client';

import React, { useCallback, useState } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  addEdge,
  Connection,
  Edge,
  Node
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

import { BenchmarkNode } from './nodes/benchmark-node';
import { createBenchmarkPipeline, generateBenchmarkMetrics } from '@/lib/flow-utils';

const nodeTypes: any = {
  benchmark: BenchmarkNode
};

export function BenchmarkPipelineFlow() {
  const [nodes, setNodes, onNodesChange] = useNodesState(
    createBenchmarkPipeline().nodes
  );
  const [edges, setEdges, onEdgesChange] = useEdgesState(
    createBenchmarkPipeline().edges
  );
  const [isRunning, setIsRunning] = useState(false);

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const runPipeline = useCallback(() => {
    if (isRunning) return;

    setIsRunning(true);

    // Simulate pipeline execution
    let currentIndex = 0;

    const updateNode = () => {
      if (currentIndex >= nodes.length) {
        setIsRunning(false);
        return;
      }

      setNodes((nds) =>
        nds.map((node) => {
          if (node.id === nodes[currentIndex].id) {
            return {
              ...node,
              data: {
                ...node.data,
                status: 'running' as const,
                ...generateBenchmarkMetrics()
              }
            };
          }
          if (Number(node.id.split('-')[1] || 0) < currentIndex) {
            return {
              ...node,
              data: {
                ...node.data,
                status: 'completed' as const,
                ...generateBenchmarkMetrics()
              }
            };
          }
          return node;
        })
      );

      setTimeout(() => {
        setNodes((nds) =>
          nds.map((node) => {
            if (node.id === nodes[currentIndex].id) {
              return {
                ...node,
                data: {
                  ...node.data,
                  status: 'completed' as const,
                  ...generateBenchmarkMetrics()
                }
              };
            }
            return node;
          })
        );

        currentIndex++;
        setTimeout(updateNode, 1000);
      }, 2000);
    };

    updateNode();
  }, [nodes, setNodes, isRunning]);

  const resetPipeline = useCallback(() => {
    setNodes((nds) =>
      nds.map((node) => ({
        ...node,
        data: {
          ...node.data,
          status: 'pending' as const
        }
      }))
    );
    setIsRunning(false);
  }, [setNodes]);

  return (
    <div className="w-full h-[600px] border rounded-lg overflow-hidden">
      <div className="absolute top-4 right-4 z-10 flex gap-2">
        <button
          onClick={runPipeline}
          disabled={isRunning}
          className="px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600 disabled:bg-gray-300 disabled:cursor-not-allowed transition-colors"
        >
          {isRunning ? 'Running...' : '▶️ Run Pipeline'}
        </button>
        <button
          onClick={resetPipeline}
          className="px-4 py-2 bg-gray-500 text-white rounded hover:bg-gray-600 transition-colors"
        >
          🔄 Reset
        </button>
      </div>

      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        nodeTypes={nodeTypes}
        fitView
      >
        <Background />
        <Controls />
        <MiniMap />
      </ReactFlow>
    </div>
  );
}
