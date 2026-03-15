'use client';

import React, { useCallback, useState, useEffect } from 'react';
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
import { Card } from '@radix-ui/themes';
import { Button } from '@radix-ui/themes';
import { Badge } from '@radix-ui/themes';

interface ScatterGatherNodeData {
  label: string;
  type: 'requester' | 'scatter' | 'recipient' | 'aggregator';
  status?: 'idle' | 'scattering' | 'gathering' | 'complete';
  responseCount?: number;
  totalRecipients?: number;
}

const nodeTypes = {
  requester: ({ data }: { data: ScatterGatherNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-green-50 to-green-100 border-green-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📤</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Requester</Badge>
        </div>
      </div>
      {data.status && (
        <Badge variant={data.status === 'idle' ? 'secondary' : 'default'} className="text-xs">
          {data.status}
        </Badge>
      )}
    </Card>
  ),
  scatter: ({ data }: { data: ScatterGatherNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-purple-50 to-purple-100 border-purple-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📡</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Scatter</Badge>
        </div>
      </div>
      <div className="text-xs text-gray-600">Broadcast to all recipients</div>
    </Card>
  ),
  recipient: ({ data }: { data: ScatterGatherNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-blue-50 to-blue-100 border-blue-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">🔧</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Recipient</Badge>
        </div>
      </div>
    </Card>
  ),
  aggregator: ({ data }: { data: ScatterGatherNodeData }) => (
    <Card className="p-4 min-w-[200px] bg-gradient-to-br from-orange-50 to-orange-100 border-orange-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📊</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Aggregator</Badge>
        </div>
      </div>
      {data.responseCount !== undefined && data.totalRecipients !== undefined && (
        <div className="text-xs space-y-1">
          <div className="flex justify-between">
            <span className="text-gray-600">Responses:</span>
            <span className="font-mono font-semibold">{data.responseCount}/{data.totalRecipients}</span>
          </div>
          <div className="w-full bg-gray-200 rounded-full h-2">
            <div
              className="bg-orange-500 h-2 rounded-full transition-all"
              style={{ width: `${(data.responseCount / data.totalRecipients) * 100}%` }}
            />
          </div>
        </div>
      )}
    </Card>
  )
};

const initialNodes: Node<any>[] = [
  {
    id: 'requester',
    type: 'requester',
    position: { x: 50, y: 150 },
    data: { label: 'Price Service', type: 'requester', status: 'idle' }
  },
  {
    id: 'scatter',
    type: 'scatter',
    position: { x: 300, y: 150 },
    data: { label: 'Scatter-Gather', type: 'scatter' }
  },
  {
    id: 'recipient1',
    type: 'recipient',
    position: { x: 600, y: 50 },
    data: { label: 'Vendor A', type: 'recipient' }
  },
  {
    id: 'recipient2',
    type: 'recipient',
    position: { x: 600, y: 150 },
    data: { label: 'Vendor B', type: 'recipient' }
  },
  {
    id: 'recipient3',
    type: 'recipient',
    position: { x: 600, y: 250 },
    data: { label: 'Vendor C', type: 'recipient' }
  },
  {
    id: 'aggregator',
    type: 'aggregator',
    position: { x: 900, y: 150 },
    data: {
      label: 'Price Aggregator',
      type: 'aggregator',
      responseCount: 0,
      totalRecipients: 3
    }
  }
];

const initialEdges: Edge[] = [
  { id: 'e1', source: 'requester', target: 'scatter', animated: false, style: { stroke: '#22c55e' } },
  { id: 'e2', source: 'scatter', target: 'recipient1', animated: false, style: { stroke: '#3b82f6' } },
  { id: 'e3', source: 'scatter', target: 'recipient2', animated: false, style: { stroke: '#3b82f6' } },
  { id: 'e4', source: 'scatter', target: 'recipient3', animated: false, style: { stroke: '#3b82f6' } },
  { id: 'e5', source: 'recipient1', target: 'aggregator', animated: false, style: { stroke: '#f59e0b' } },
  { id: 'e6', source: 'recipient2', target: 'aggregator', animated: false, style: { stroke: '#f59e0b' } },
  { id: 'e7', source: 'recipient3', target: 'aggregator', animated: false, style: { stroke: '#f59e0b' } }
];

export function ScatterGatherFlow() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  const [isRunning, setIsRunning] = useState(false);
  const [stats, setStats] = useState({
    totalRequests: 0,
    avgResponseTime: 0,
    timeoutCount: 0
  });

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const executeScatterGather = useCallback(() => {
    const startTime = Date.now();

    // Phase 1: Scatter
    setNodes((nds) =>
      nds.map((node) => {
        if (node.id === 'requester') {
          return { ...node, data: { ...node.data, status: 'scattering' as const } };
        }
        return node;
      })
    );

    setEdges((eds) =>
      eds.map((edge) => {
        if (edge.source === 'requester' && edge.target === 'scatter') {
          return { ...edge, animated: true };
        }
        return edge;
      })
    );

    setTimeout(() => {
      // Broadcast to all recipients
      setEdges((eds) =>
        eds.map((edge) => {
          if (edge.source === 'scatter') {
            return { ...edge, animated: true };
          }
          if (edge.source === 'requester') {
            return { ...edge, animated: false };
          }
          return edge;
        })
      );
    }, 500);

    setTimeout(() => {
      // Phase 2: Gather
      setNodes((nds) =>
        nds.map((node) => {
          if (node.id === 'requester') {
            return { ...node, data: { ...node.data, status: 'gathering' as const } };
          }
          return node;
        })
      );

      setEdges((eds) =>
        eds.map((edge) => {
          if (edge.target === 'aggregator') {
            return { ...edge, animated: true };
          }
          if (edge.source === 'scatter') {
            return { ...edge, animated: false };
          }
          return edge;
        })
      );

      // Simulate responses arriving
      let responseCount = 0;
      const responseInterval = setInterval(() => {
        responseCount++;
        setNodes((nds) =>
          nds.map((node) => {
            if (node.id === 'aggregator') {
              return { ...node, data: { ...node.data, responseCount } };
            }
            return node;
          })
        );

        if (responseCount >= 3) {
          clearInterval(responseInterval);

          // Complete
          setTimeout(() => {
            setNodes((nds) =>
              nds.map((node) => {
                if (node.id === 'requester') {
                  return { ...node, data: { ...node.data, status: 'complete' as const } };
                }
                if (node.id === 'aggregator') {
                  return { ...node, data: { ...node.data, responseCount: 0 } };
                }
                return node;
              })
            );

            setEdges((eds) =>
              eds.map((edge) => ({ ...edge, animated: false }))
            );

            const responseTime = Date.now() - startTime;
            setStats((prev) => ({
              totalRequests: prev.totalRequests + 1,
              avgResponseTime: (prev.avgResponseTime * prev.totalRequests + responseTime) / (prev.totalRequests + 1),
              timeoutCount: prev.timeoutCount
            }));

            setTimeout(() => {
              setNodes((nds) =>
                nds.map((node) => {
                  if (node.id === 'requester') {
                    return { ...node, data: { ...node.data, status: 'idle' as const } };
                  }
                  return node;
                })
              );
            }, 1000);
          }, 500);
        }
      }, 300);
    }, 1500);
  }, [setNodes, setEdges]);

  const startFlow = useCallback(() => {
    setIsRunning(true);
    const interval = setInterval(executeScatterGather, 5000);
    return () => clearInterval(interval);
  }, [executeScatterGather]);

  const stopFlow = useCallback(() => {
    setIsRunning(false);
  }, []);

  useEffect(() => {
    let cleanup: (() => void) | undefined;

    if (isRunning) {
      cleanup = startFlow();
    }

    return () => {
      if (cleanup) cleanup();
    };
  }, [isRunning, startFlow]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold">Scatter-Gather</h2>
          <p className="text-sm text-gray-600 mt-1">
            Broadcast to multiple recipients and aggregate responses
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            onClick={() => setIsRunning(!isRunning)}
            variant={isRunning ? "destructive" : "default"}
          >
            {isRunning ? '⏹️ Stop' : '▶️ Start'}
          </Button>
          <Button onClick={executeScatterGather} disabled={isRunning} variant="outline">
            📡 Execute Once
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <Card className="p-4">
          <div className="text-sm text-gray-600">Total Requests</div>
          <div className="text-2xl font-bold font-mono">{stats.totalRequests}</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Avg Response Time</div>
          <div className="text-2xl font-bold font-mono">{stats.avgResponseTime.toFixed(0)}ms</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Timeouts</div>
          <div className="text-2xl font-bold font-mono text-red-600">{stats.timeoutCount}</div>
        </Card>
      </div>

      <div className="w-full h-[500px] border rounded-lg overflow-hidden">
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

      <Card className="p-4 bg-gray-50">
        <h3 className="font-bold text-sm mb-2">Java Implementation</h3>
        <pre className="text-xs bg-gray-900 text-green-400 p-3 rounded overflow-x-auto">
{`// Scatter-gather with parallel execution
var scatterGather = new ScatterGather<PriceQuote>();

// Add recipients (parallel processing)
scatterGather.addRecipient(vendorA::getQuote);  // Non-blocking
scatterGather.addRecipient(vendorB::getQuote);
scatterGather.addRecipient(vendorC::getQuote);

// Execute with timeout
var result = scatterGather
  .withTimeout(Duration.ofSeconds(5))
  .aggregate((quotes) -> quotes.stream()
    .min(Comparator.comparing(PriceQuote::getPrice))
    .orElseThrow());

// Returns best price or throws TimeoutException`}
        </pre>
      </Card>

      <Card className="p-4">
        <h3 className="font-bold text-sm mb-2">Pattern Description</h3>
        <div className="text-sm space-y-2 text-gray-700">
          <p><strong>Scatter-Gather</strong> broadcasts a request to multiple recipients and aggregates their responses.</p>
          <ul className="list-disc list-inside space-y-1">
            <li><strong>Scatter Phase:</strong> Send request to all recipients in parallel</li>
            <li><strong>Gather Phase:</strong> Collect responses from all recipients</li>
            <li><strong>Aggregation:</strong> Combine responses (min, max, avg, merge, etc.)</li>
            <li><strong>Timeout Handling:</strong> Continue with partial results or fail on timeout</li>
            <li><strong>Use Cases:</strong> Best-price queries, parallel API calls, data enrichment</li>
          </ul>
        </div>
      </Card>
    </div>
  );
}
