'use client';

import React, { useState, useEffect } from 'react';
import { useMachine } from '@xstate/react';
import { flowMachine, FlowMetrics } from '@/lib/state-machines/flow-machine';
import { selectionMachine } from '@/lib/state-machines/selection-machine';
import { MetricsOverlay } from './metrics-overlay';
import { FlowControls } from './flow-controls';
import {
  createBenchmarkConnector,
  useBenchmarkConnector
} from '@/lib/realtime/benchmark-connector';

interface FlowVisualizerProps {
  flowId: string;
  connectorType?: 'websocket' | 'sse' | 'polling';
  connectorUrl?: string;
}

export const FlowVisualizer: React.FC<FlowVisualizerProps> = ({
  flowId,
  connectorType = 'polling',
  connectorUrl = '/api/benchmarks/metrics'
}) => {
  const [flowState, sendFlow] = useMachine(flowMachine);
  const [selectionState, sendSelection] = useMachine(selectionMachine);
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const [overlayPosition, setOverlayPosition] = useState({ x: 0, y: 0 });

  // Initialize flow on mount
  useEffect(() => {
    sendFlow({ type: 'LOAD_FLOW', flowId });
  }, [flowId, sendFlow]);

  // Connect to benchmark data stream
  const { status: connectorStatus } = useBenchmarkConnector({
    type: connectorType,
    url: connectorUrl,
    onMetrics: (nodeId, metrics) => {
      sendFlow({
        type: 'UPDATE_METRICS',
        nodeId,
        metrics
      });
    }
  });

  // Handle node selection
  const handleNodeClick = (nodeId: string, event: React.MouseEvent) => {
    const rect = event.currentTarget.getBoundingClientRect();
    setOverlayPosition({ x: rect.left + rect.width / 2, y: rect.top });

    sendFlow({
      type: 'SELECT_NODE',
      nodeId
    });

    sendSelection({
      type: 'TOGGLE',
      item: {
        id: nodeId,
        type: 'primitive',
        label: nodeId
      }
    });
  };

  // Handle node hover
  const handleNodeHover = (nodeId: string | null, event: React.MouseEvent) => {
    setHoveredNode(nodeId);
    if (nodeId && event.currentTarget) {
      const rect = event.currentTarget.getBoundingClientRect();
      setOverlayPosition({ x: rect.left + rect.width / 2, y: rect.top });
    }
  };

  // Get metrics for a specific node
  const getNodeMetrics = (nodeId: string): FlowMetrics | null => {
    return flowState.context.metrics[nodeId] || null;
  };

  // Get metrics history for sparklines
  const getNodeHistory = (nodeId: string): FlowMetrics[] => {
    return flowState.context.metricsHistory[nodeId] || [];
  };

  return (
    <div className="relative w-full h-full bg-gray-950 rounded-xl overflow-hidden">
      {/* Header */}
      <div className="absolute top-0 left-0 right-0 z-20 p-4 bg-gradient-to-b from-gray-900/90 to-transparent">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-xl font-bold text-white">{flowId}</h2>
            <div className="flex items-center gap-2 mt-1">
              <div className={`w-2 h-2 rounded-full ${
                connectorStatus === 'connected' ? 'bg-green-500' :
                connectorStatus === 'connecting' ? 'bg-yellow-500' :
                'bg-red-500'
              }`} />
              <span className="text-xs text-gray-400 capitalize">{connectorStatus}</span>
            </div>
          </div>
          <FlowControls
            machine={[flowState, sendFlow] as any}
            onStepForward={() => console.log('Step forward')}
            onStepBackward={() => console.log('Step backward')}
            onSpeedChange={(speed) => console.log('Speed:', speed)}
          />
        </div>
      </div>

      {/* Flow Canvas */}
      <div className="absolute inset-0 pt-24 pb-4 px-4 overflow-auto">
        {flowState.context.flowData ? (
          <div className="relative w-full h-full" style={{ minHeight: '600px' }}>
            {/* Render nodes */}
            {flowState.context.flowData.nodes.map((node, index) => {
              const isSelected = flowState.context.selectedNode === node.id;
              const isHovered = hoveredNode === node.id;
              const metrics = getNodeMetrics(node.id);
              const history = getNodeHistory(node.id);

              // Position nodes in a grid for now
              const x = (index % 4) * 250 + 50;
              const y = Math.floor(index / 4) * 150 + 50;

              return (
                <div key={node.id}>
                  {/* Node Card */}
                  <motion.div
                    initial={{ opacity: 0, scale: 0.9 }}
                    animate={{ opacity: 1, scale: isSelected ? 1.05 : 1 }}
                    whileHover={{ scale: 1.02 }}
                    className={`absolute cursor-pointer transition-all ${
                      isSelected
                        ? 'ring-2 ring-blue-500 bg-blue-950/50'
                        : isHovered
                        ? 'ring-2 ring-purple-500 bg-purple-950/30'
                        : 'bg-gray-800/50 hover:bg-gray-800/70'
                    } backdrop-blur-sm rounded-xl border border-gray-700/50 p-4`}
                    style={{
                      left: x,
                      top: y,
                      width: 220,
                      height: 120
                    }}
                    onClick={(e) => handleNodeClick(node.id, e)}
                    onMouseEnter={(e) => handleNodeHover(node.id, e)}
                    onMouseLeave={() => handleNodeHover(null, null as any)}
                  >
                    {/* Node Header */}
                    <div className="flex items-center justify-between mb-3">
                      <div className="flex items-center gap-2">
                        <div className={`w-2 h-2 rounded-full ${
                          node.status === 'running' ? 'bg-green-500' :
                          node.status === 'error' ? 'bg-red-500' :
                          node.status === 'paused' ? 'bg-yellow-500' :
                          'bg-gray-500'
                        } animate-pulse`} />
                        <h3 className="text-sm font-semibold text-white">{node.label}</h3>
                      </div>
                      <span className="text-xs text-gray-500 capitalize">{node.type}</span>
                    </div>

                    {/* Metrics Summary */}
                    {metrics && (
                      <div className="grid grid-cols-2 gap-2 text-xs">
                        <div>
                          <span className="text-gray-500">Throughput</span>
                          <div className="text-white font-semibold">
                            {metrics.throughput.toLocaleString()} ops/s
                          </div>
                        </div>
                        <div>
                          <span className="text-gray-500">Latency p95</span>
                          <div className="text-white font-semibold">
                            {metrics.latency.p95.toFixed(2)} ms
                          </div>
                        </div>
                        <div>
                          <span className="text-gray-500">Error Rate</span>
                          <div className={`font-semibold ${
                            metrics.errorRate > 1 ? 'text-red-400' :
                            metrics.errorRate > 0.1 ? 'text-yellow-400' :
                            'text-green-400'
                          }`}>
                            {metrics.errorRate.toFixed(2)}%
                          </div>
                        </div>
                        <div>
                          <span className="text-gray-500">CPU</span>
                          <div className="text-white font-semibold">
                            {metrics.cpu.toFixed(1)}%
                          </div>
                        </div>
                      </div>
                    )}

                    {!metrics && (
                      <div className="text-xs text-gray-500 text-center py-4">
                        No metrics available
                      </div>
                    )}
                  </motion.div>

                  {/* Metrics Overlay */}
                  {isSelected && (
                    <MetricsOverlay
                      nodeId={node.id}
                      metrics={metrics}
                      history={history}
                      position={overlayPosition}
                      isVisible={true}
                      onClose={() => sendFlow({ type: 'SELECT_NODE', nodeId: '' })}
                    />
                  )}
                </div>
              );
            })}

            {/* Render edges (simplified - use React Flow for production) */}
            {flowState.context.flowData.edges.map((edge, index) => {
              const fromNode = flowState.context.flowData!.nodes.find(n => n.id === edge.from);
              const toNode = flowState.context.flowData!.nodes.find(n => n.id === edge.to);

              if (!fromNode || !toNode) return null;

              const fromIndex = flowState.context.flowData!.nodes.indexOf(fromNode);
              const toIndex = flowState.context.flowData!.nodes.indexOf(toNode);

              const x1 = (fromIndex % 4) * 250 + 50 + 220;
              const y1 = Math.floor(fromIndex / 4) * 150 + 50 + 60;
              const x2 = (toIndex % 4) * 250 + 50;
              const y2 = Math.floor(toIndex / 4) * 150 + 50 + 60;

              return (
                <svg
                  key={index}
                  className="absolute top-0 left-0 w-full h-full pointer-events-none"
                  style={{ zIndex: 0 }}
                >
                  <defs>
                    <marker
                      id={`arrowhead-${index}`}
                      markerWidth="10"
                      markerHeight="7"
                      refX="9"
                      refY="3.5"
                      orient="auto"
                    >
                      <polygon
                        points="0 0, 10 3.5, 0 7"
                        fill="#6b7280"
                      />
                    </marker>
                  </defs>
                  <line
                    x1={x1}
                    y1={y1}
                    x2={x2}
                    y2={y2}
                    stroke="#6b7280"
                    strokeWidth="2"
                    markerEnd={`url(#arrowhead-${index})`}
                  />
                </svg>
              );
            })}
          </div>
        ) : (
          <div className="flex items-center justify-center h-full">
            <div className="text-center">
              <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-500 mx-auto mb-4" />
              <p className="text-gray-400">Loading flow...</p>
            </div>
          </div>
        )}
      </div>

      {/* Footer Status */}
      <div className="absolute bottom-0 left-0 right-0 z-20 p-4 bg-gradient-to-t from-gray-900/90 to-transparent">
        <div className="flex items-center justify-between text-xs text-gray-400">
          <div>
            Selected: <span className="text-white">{flowState.context.selectedNode || 'None'}</span>
          </div>
          <div className="flex items-center gap-4">
            <span>Speed: {flowState.context.animationSpeed}x</span>
            <span>Status: {flowState.value}</span>
          </div>
        </div>
      </div>
    </div>
  );
};

// Import motion for animations
import { motion } from 'framer-motion';
