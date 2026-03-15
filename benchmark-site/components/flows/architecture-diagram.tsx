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

import { ComponentNode } from './nodes/component-node';
import { createArchitectureDiagram } from '@/lib/flow-utils';

const nodeTypes: any = {
  component: ComponentNode
};

export function ArchitectureDiagramFlow() {
  const [nodes, setNodes, onNodesChange] = useNodesState(
    createArchitectureDiagram().nodes
  );
  const [edges, setEdges, onEdgesChange] = useEdgesState(
    createArchitectureDiagram().edges
  );
  const [selectedNode, setSelectedNode] = useState<string | null>(null);

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const onNodeClick = useCallback((_event: React.MouseEvent, node: Node) => {
    setSelectedNode(node.id);
  }, []);

  const getNodeDetails = (nodeId: string) => {
    const details: Record<string, { title: string; description: string; features: string[] }> = {
      proc: {
        title: 'Proc - Lightweight Process',
        description: 'Core primitive implementing Erlang/OTP spawn/3 with virtual threads and mailbox-based message passing.',
        features: [
          'Virtual thread-based execution',
          'Mailbox message queue',
          'Pure state handler functions',
          'Link and trap_exit support',
          'Synchronous ask() with timeout'
        ]
      },
      supervisor: {
        title: 'Supervisor - Fault Tolerance Tree',
        description: 'Implements OTP supervision strategies: ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE, SIMPLE_ONE_FOR_ONE.',
        features: [
          'Multiple restart strategies',
          'ChildSpec with restart intensity/period',
          'Dynamic child management',
          'Graceful shutdown handling',
          'AutoShutdown on significant exit'
        ]
      },
      'state-machine': {
        title: 'StateMachine - gen_statem',
        description: 'Full parity with Erlang gen_statem: state transitions, postponed events, timeout handling.',
        features: [
          'State enter/exit callbacks',
          'Event postponement and replay',
          'State/Event/Generic timeouts',
          'Internal events',
          'Reply actions'
        ]
      },
      'event-bus': {
        title: 'FrameworkEventBus - Observability',
        description: 'Publish-subscribe event bus for framework-wide event distribution and monitoring.',
        features: [
          'Type-safe event publishing',
          'Subscriber lifecycle management',
          'Event filtering and routing',
          'Async event delivery',
          'Integration with OpenTelemetry'
        ]
      },
      registry: {
        title: 'ProcRegistry - Name Registration',
        description: 'Global process registry with automatic cleanup on process termination.',
        features: [
          'Global name registration',
          'PID lookup by name',
          'Auto-deregistration on exit',
          'Process name collision detection',
          'Distributed registry support'
        ]
      }
    };

    return details[nodeId] || null;
  };

  const details = selectedNode ? getNodeDetails(selectedNode) : null;

  return (
    <div className="w-full h-[700px] border rounded-lg overflow-hidden">
      {details && (
        <div className="absolute top-4 left-4 z-10 w-96 bg-white border rounded-lg shadow-lg p-4 max-h-[600px] overflow-y-auto">
          <button
            onClick={() => setSelectedNode(null)}
            className="absolute top-2 right-2 text-gray-400 hover:text-gray-600"
          >
            ✕
          </button>
          <h3 className="font-bold text-lg mb-2">{details.title}</h3>
          <p className="text-sm text-gray-600 mb-4">{details.description}</p>
          <div>
            <h4 className="font-semibold text-sm mb-2">Key Features:</h4>
            <ul className="space-y-1">
              {details.features.map((feature, i) => (
                <li key={i} className="text-xs flex items-start gap-2">
                  <span className="text-green-500 mt-0.5">✓</span>
                  <span>{feature}</span>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}

      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onNodeClick={onNodeClick}
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
