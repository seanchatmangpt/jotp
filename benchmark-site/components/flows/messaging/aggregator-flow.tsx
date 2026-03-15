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

interface AggregatorNodeData {
  label: string;
  type: 'input' | 'aggregator' | 'output';
  messageCount?: number;
  correlationId?: string;
  completionStrategy?: string;
  messages?: Array<{ id: string; content: string; timestamp: number }>;
}

const nodeTypes = {
  input: ({ data }: { data: AggregatorNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-green-50 to-green-100 border-green-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📥</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Input</Badge>
        </div>
      </div>
      {data.correlationId && (
        <div className="text-xs bg-white/50 px-2 py-1 rounded mt-2">
          <span className="text-gray-600">Correlation ID:</span>
          <span className="font-mono font-semibold ml-1">{data.correlationId}</span>
        </div>
      )}
    </Card>
  ),
  aggregator: ({ data }: { data: AggregatorNodeData }) => (
    <Card className="p-4 min-w-[220px] bg-gradient-to-br from-purple-50 to-purple-100 border-purple-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📊</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Aggregator</Badge>
        </div>
      </div>
      <div className="text-xs space-y-1 mb-2">
        <div className="flex justify-between">
          <span className="text-gray-600">Buffered:</span>
          <span className="font-mono font-semibold">{data.messageCount || 0}</span>
        </div>
      </div>
      {data.completionStrategy && (
        <div className="text-xs bg-white/50 px-2 py-1 rounded">
          <span className="text-gray-600">Strategy:</span> {data.completionStrategy}
        </div>
      )}
      {data.messages && data.messages.length > 0 && (
        <div className="mt-2 pt-2 border-t border-current/20">
          <div className="text-xs text-gray-600 mb-1">Messages:</div>
          <div className="space-y-1 max-h-20 overflow-y-auto">
            {data.messages.slice(0, 3).map((msg, i) => (
              <div key={i} className="text-xs bg-white/50 px-2 py-0.5 rounded truncate">
                {msg.content}
              </div>
            ))}
            {data.messages.length > 3 && (
              <div className="text-xs text-gray-500">+{data.messages.length - 3} more</div>
            )}
          </div>
        </div>
      )}
    </Card>
  ),
  output: ({ data }: { data: AggregatorNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-blue-50 to-blue-100 border-blue-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📤</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Output</Badge>
        </div>
      </div>
      {data.messageCount !== undefined && data.messageCount > 0 && (
        <div className="text-xs bg-white/50 px-2 py-1 rounded">
          <span className="text-gray-600">Aggregated:</span>
          <span className="font-mono font-semibold ml-1">{data.messageCount}</span>
        </div>
      )}
    </Card>
  )
};

const initialNodes: Node<any>[] = [
  {
    id: 'input1',
    type: 'input',
    position: { x: 50, y: 50 },
    data: { label: 'Order Line 1', type: 'input', correlationId: 'ORD-001' }
  },
  {
    id: 'input2',
    type: 'input',
    position: { x: 50, y: 150 },
    data: { label: 'Order Line 2', type: 'input', correlationId: 'ORD-001' }
  },
  {
    id: 'input3',
    type: 'input',
    position: { x: 50, y: 250 },
    data: { label: 'Order Line 3', type: 'input', correlationId: 'ORD-001' }
  },
  {
    id: 'aggregator',
    type: 'aggregator',
    position: { x: 350, y: 150 },
    data: {
      label: 'Order Aggregator',
      type: 'aggregator',
      messageCount: 0,
      completionStrategy: 'Count (3)',
      messages: []
    }
  },
  {
    id: 'output',
    type: 'output',
    position: { x: 650, y: 150 },
    data: { label: 'Complete Order', type: 'output', messageCount: 0 }
  }
];

const initialEdges: Edge[] = [
  { id: 'e1', source: 'input1', target: 'aggregator', animated: false, style: { stroke: '#22c55e' } },
  { id: 'e2', source: 'input2', target: 'aggregator', animated: false, style: { stroke: '#22c55e' } },
  { id: 'e3', source: 'input3', target: 'aggregator', animated: false, style: { stroke: '#22c55e' } },
  { id: 'e4', source: 'aggregator', target: 'output', animated: false, style: { stroke: '#3b82f6' } }
];

export function AggregatorFlow() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  const [isRunning, setIsRunning] = useState(false);
  const [stats, setStats] = useState({
    totalAggregations: 0,
    avgBufferSize: 0,
    completionStrategies: { count: 0, timeout: 0, sequence: 0 }
  });

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const addMessage = useCallback((inputId: string, content: string) => {
    const edge = edges.find(e => e.source === inputId);
    if (!edge) return;

    // Animate message to aggregator
    setEdges((eds) =>
      eds.map((e) => {
        if (e.id === edge.id) {
          return { ...e, animated: true };
        }
        return e;
      })
    );

    setTimeout(() => {
      setEdges((eds) =>
        eds.map((e) => {
          if (e.id === edge.id) {
            return { ...e, animated: false };
          }
          return e;
        })
      );

      // Add to aggregator buffer
      setNodes((nds) =>
        nds.map((node) => {
          if (node.id === 'aggregator') {
            const newMessages = [
              ...(node.data.messages || []),
              { id: `msg-${Date.now()}`, content, timestamp: Date.now() }
            ];
            return {
              ...node,
              data: {
                ...node.data,
                messageCount: newMessages.length,
                messages: newMessages
              }
            };
          }
          return node;
        })
      );

      // Check completion strategy (count-based: 3 messages)
      setTimeout(() => {
        const aggregatorNode = nodes.find(n => n.id === 'aggregator');
        const currentCount = aggregatorNode?.data.messageCount || 0;

        if (currentCount >= 3) {
          // Aggregation complete - flush to output
          setEdges((eds) =>
            eds.map((e) => {
              if (e.target === 'output') {
                return { ...e, animated: true };
              }
              return e;
            })
          );

          setTimeout(() => {
            setNodes((nds) =>
              nds.map((node) => {
                if (node.id === 'output') {
                  return {
                    ...node,
                    data: { ...node.data, messageCount: 3 }
                  };
                }
                if (node.id === 'aggregator') {
                  return {
                    ...node,
                    data: { ...node.data, messageCount: 0, messages: [] }
                  };
                }
                return node;
              })
            );

            setEdges((eds) =>
              eds.map((e) => {
                if (e.target === 'output') {
                  return { ...e, animated: false };
                }
                return e;
              })
            );

            setStats((prev) => ({
              totalAggregations: prev.totalAggregations + 1,
              avgBufferSize: (prev.avgBufferSize * prev.totalAggregations + 3) / (prev.totalAggregations + 1),
              completionStrategies: {
                ...prev.completionStrategies,
                count: prev.completionStrategies.count + 1
              }
            }));

            setTimeout(() => {
              setNodes((nds) =>
                nds.map((node) => {
                  if (node.id === 'output') {
                    return { ...node, data: { ...node.data, messageCount: 0 } };
                  }
                  return node;
                })
              );
            }, 1500);
          }, 1000);
        }
      }, 500);
    }, 500);
  }, [nodes, edges, setNodes, setEdges]);

  const startFlow = useCallback(() => {
    setIsRunning(true);
    const items = [
      { id: 'input1', content: 'Widget A x5' },
      { id: 'input2', content: 'Widget B x2' },
      { id: 'input3', content: 'Widget C x1' }
    ];

    const interval = setInterval(() => {
      const randomItem = items[Math.floor(Math.random() * items.length)];
      addMessage(randomItem.id, randomItem.content);
    }, 1500);

    return () => clearInterval(interval);
  }, [addMessage]);

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
          <h2 className="text-2xl font-bold">Aggregator</h2>
          <p className="text-sm text-gray-600 mt-1">
            Aggregate related messages by correlation ID
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            onClick={() => setIsRunning(!isRunning)}
            variant={isRunning ? "destructive" : "default"}
          >
            {isRunning ? '⏹️ Stop' : '▶️ Start'}
          </Button>
          <div className="flex gap-1">
            <Button onClick={() => addMessage('input1', 'Widget A x5')} disabled={isRunning} variant="outline">
              Line 1
            </Button>
            <Button onClick={() => addMessage('input2', 'Widget B x2')} disabled={isRunning} variant="outline">
              Line 2
            </Button>
            <Button onClick={() => addMessage('input3', 'Widget C x1')} disabled={isRunning} variant="outline">
              Line 3
            </Button>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <Card className="p-4">
          <div className="text-sm text-gray-600">Total Aggregations</div>
          <div className="text-2xl font-bold font-mono">{stats.totalAggregations}</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Avg Buffer Size</div>
          <div className="text-2xl font-bold font-mono">{stats.avgBufferSize.toFixed(1)}</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Count Strategy</div>
          <div className="text-2xl font-bold font-mono">{stats.completionStrategies.count}</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Correlation ID</div>
          <div className="text-lg font-mono font-bold text-purple-600">ORD-001</div>
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
{`// Aggregate related messages by correlation ID
var aggregator = new Aggregator<OrderLine>()
  .withCorrelationId(OrderLine::getOrderId)
  .completionStrategy(CompletionStrategy.count(3))
  .aggregate(lines -> new Order(
    lines.get(0).getOrderId(),
    lines.stream().mapToInt(OrderLine::getQuantity).sum(),
    lines.stream().mapToDouble(OrderLine::getPrice).sum()
  ));

// Messages arrive out of order
aggregator.process(new OrderLine("ORD-001", "Widget A", 5, 10.0));
aggregator.process(new OrderLine("ORD-001", "Widget B", 2, 15.0));
aggregator.process(new OrderLine("ORD-001", "Widget C", 1, 20.0));
// → Emits: Order("ORD-001", 8, 100.0)`}
        </pre>
      </Card>

      <Card className="p-4">
        <h3 className="font-bold text-sm mb-2">Pattern Description</h3>
        <div className="text-sm space-y-2 text-gray-700">
          <p><strong>Aggregator</strong> combines multiple related messages into a single message.</p>
          <ul className="list-disc list-inside space-y-1">
            <li><strong>Correlation ID:</strong> Groups related messages (e.g., order ID, session ID)</li>
            <li><strong>Completion Strategies:</strong> Count-based, timeout-based, sequence-based</li>
            <li><strong>Buffering:</strong> Stores messages until completion condition is met</li>
            <li><strong>Aggregation:</strong> Combines, sums, averages, or merges message data</li>
            <li><strong>Use Cases:</strong> Order assembly, batch processing, report generation</li>
          </ul>
        </div>
      </Card>
    </div>
  );
}
