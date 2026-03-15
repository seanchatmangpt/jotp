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

interface WireTapNodeData {
  label: string;
  type: 'producer' | 'channel' | 'consumer' | 'tap' | 'monitor';
  messageCount?: number;
  isTapFlow?: boolean;
  lastMessage?: string;
}

const nodeTypes = {
  producer: ({ data }: { data: WireTapNodeData }) => (
    <Card className={`p-4 min-w-[180px] ${data.isTapFlow ? 'bg-gradient-to-br from-green-50 to-green-100 border-green-300' : 'bg-gradient-to-br from-blue-50 to-blue-100 border-blue-300'}`}>
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📤</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Producer</Badge>
        </div>
      </div>
    </Card>
  ),
  channel: ({ data }: { data: WireTapNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-purple-50 to-purple-100 border-purple-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📨</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Channel</Badge>
        </div>
      </div>
      <div className="text-xs space-y-1">
        <div className="flex justify-between">
          <span className="text-gray-600">Messages:</span>
          <span className="font-mono font-semibold">{data.messageCount || 0}</span>
        </div>
      </div>
    </Card>
  ),
  consumer: ({ data }: { data: WireTapNodeData }) => (
    <Card className={`p-4 min-w-[180px] ${data.isTapFlow ? 'bg-gradient-to-br from-yellow-50 to-yellow-100 border-yellow-300' : 'bg-gradient-to-br from-blue-50 to-blue-100 border-blue-300'}`}>
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📥</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant={data.isTapFlow ? 'default' : 'outline'} className="text-xs">
            {data.isTapFlow ? 'Monitor' : 'Consumer'}
          </Badge>
        </div>
      </div>
      {data.lastMessage && (
        <div className="text-xs bg-white/50 px-2 py-1 rounded mt-2 truncate">
          {data.lastMessage}
        </div>
      )}
    </Card>
  ),
  tap: ({ data }: { data: WireTapNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-orange-50 to-orange-100 border-orange-300 border-2 border-dashed">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">🔌</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Wire Tap</Badge>
        </div>
      </div>
      <div className="text-xs text-gray-600">Non-invasive monitoring</div>
    </Card>
  )
};

const initialNodes: Node<any>[] = [
  // Main flow (top)
  {
    id: 'producer',
    type: 'producer',
    position: { x: 50, y: 50 },
    data: { label: 'Order Producer', type: 'producer', isTapFlow: false }
  },
  {
    id: 'channel',
    type: 'channel',
    position: { x: 300, y: 50 },
    data: { label: 'Order Channel', type: 'channel', messageCount: 0 }
  },
  {
    id: 'consumer',
    type: 'consumer',
    position: { x: 550, y: 50 },
    data: { label: 'Order Consumer', type: 'consumer', isTapFlow: false }
  },
  // Tap flow (bottom)
  {
    id: 'tap',
    type: 'tap',
    position: { x: 300, y: 200 },
    data: { label: 'Wire Tap', type: 'tap' }
  },
  {
    id: 'monitor',
    type: 'consumer',
    position: { x: 550, y: 200 },
    data: { label: 'Monitoring Service', type: 'consumer', isTapFlow: true }
  }
];

const initialEdges: Edge[] = [
  // Main flow
  { id: 'e1', source: 'producer', target: 'channel', animated: false, style: { stroke: '#22c55e', strokeWidth: 3 } },
  { id: 'e2', source: 'channel', target: 'consumer', animated: false, style: { stroke: '#22c55e', strokeWidth: 3 } },
  // Tap flow
  { id: 'e3', source: 'channel', target: 'tap', animated: false, style: { stroke: '#f59e0b', strokeWidth: 2, strokeDasharray: '5,5' } },
  { id: 'e4', source: 'tap', target: 'monitor', animated: false, style: { stroke: '#f59e0b', strokeWidth: 2 } }
];

export function WireTapFlow() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  const [isRunning, setIsRunning] = useState(false);
  const [stats, setStats] = useState({
    mainFlowMessages: 0,
    tapFlowMessages: 0,
    lastMessage: '',
    tapImpact: 0 // ms overhead
  });

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const sendMessage = useCallback(() => {
    const message = `Order #${Math.floor(Math.random() * 10000)}`;
    setStats((prev) => ({ ...prev, lastMessage: message }));

    // Animate main flow
    setEdges((eds) =>
      eds.map((edge) => {
        if (edge.id === 'e1') {
          return { ...edge, animated: true };
        }
        return edge;
      })
    );

    setTimeout(() => {
      setEdges((eds) =>
        eds.map((edge) => {
          if (edge.id === 'e1') {
            return { ...edge, animated: false };
          }
          if (edge.id === 'e2') {
            return { ...edge, animated: true };
          }
          return edge;
        })
      );

      // Update channel message count
      setNodes((nds) =>
        nds.map((node) => {
          if (node.id === 'channel') {
            return {
              ...node,
              data: { ...node.data, messageCount: node.data.messageCount! + 1 }
            };
          }
          return node;
        })
      );
    }, 500);

    setTimeout(() => {
      // Main flow complete
      setEdges((eds) =>
        eds.map((edge) => {
          if (edge.id === 'e2') {
            return { ...edge, animated: false };
          }
          return edge;
        })
      );

      setNodes((nds) =>
        nds.map((node) => {
          if (node.id === 'consumer') {
            return {
              ...node,
              data: { ...node.data, lastMessage: message }
            };
          }
          return node;
        })
      );

      setStats((prev) => ({
        ...prev,
        mainFlowMessages: prev.mainFlowMessages + 1
      }));
    }, 1000);

    // Simultaneously tap the message (non-blocking)
    setTimeout(() => {
      setEdges((eds) =>
        eds.map((edge) => {
          if (edge.id === 'e3') {
            return { ...edge, animated: true };
          }
          return edge;
        })
      );

      setTimeout(() => {
        setEdges((eds) =>
          eds.map((edge) => {
            if (edge.id === 'e3') {
              return { ...edge, animated: false };
            }
            if (edge.id === 'e4') {
              return { ...edge, animated: true };
            }
            return edge;
          })
        );

        setTimeout(() => {
          setEdges((eds) =>
            eds.map((edge) => {
              if (edge.id === 'e4') {
                return { ...edge, animated: false };
              }
              return edge;
            })
          );

          setNodes((nds) =>
            nds.map((node) => {
              if (node.id === 'monitor') {
                return {
                  ...node,
                  data: { ...node.data, lastMessage: message }
                };
              }
              return node;
            })
          );

          setStats((prev) => ({
            ...prev,
            tapFlowMessages: prev.tapFlowMessages + 1,
            tapImpact: Math.random() * 2 // Minimal overhead
          }));
        }, 500);
      }, 500);
    }, 300);
  }, [setNodes, setEdges]);

  const startFlow = useCallback(() => {
    setIsRunning(true);
    const interval = setInterval(sendMessage, 2500);
    return () => clearInterval(interval);
  }, [sendMessage]);

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
          <h2 className="text-2xl font-bold">Wire Tap</h2>
          <p className="text-sm text-gray-600 mt-1">
            Non-invasive monitoring of message flows
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            onClick={() => setIsRunning(!isRunning)}
            variant={isRunning ? "destructive" : "default"}
          >
            {isRunning ? '⏹️ Stop' : '▶️ Start'}
          </Button>
          <Button onClick={sendMessage} disabled={isRunning} variant="outline">
            📨 Send Message
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <Card className="p-4">
          <div className="text-sm text-gray-600">Main Flow</div>
          <div className="text-2xl font-bold font-mono text-green-600">{stats.mainFlowMessages}</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Tap Flow</div>
          <div className="text-2xl font-bold font-mono text-orange-600">{stats.tapFlowMessages}</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Tap Overhead</div>
          <div className="text-2xl font-bold font-mono">{stats.tapImpact.toFixed(2)}ms</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Last Message</div>
          <div className="text-sm font-mono truncate">{stats.lastMessage || '-'}</div>
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

      <Card className="p-4 bg-gradient-to-r from-blue-50 to-orange-50">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <div className="w-4 h-4 bg-green-500 rounded-full"></div>
            <span className="text-sm">Main Flow (Production)</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-4 h-4 bg-orange-500 rounded-full border-2 border-dashed border-orange-700"></div>
            <span className="text-sm">Tap Flow (Monitoring)</span>
          </div>
          <div className="ml-auto text-sm text-gray-600">
            Wire tap copies messages without affecting main flow
          </div>
        </div>
      </Card>

      <Card className="p-4 bg-gray-50">
        <h3 className="font-bold text-sm mb-2">Java Implementation</h3>
        <pre className="text-xs bg-gray-900 text-green-400 p-3 rounded overflow-x-auto">
{`// Wire tap for non-invasive monitoring
var mainChannel = new PointToPointChannel<Order>();

// Attach wire tap (copies messages, doesn't consume)
var tappedChannel = WireTap.tap(mainChannel)
  .to(monitoringChannel);

// Main flow: processes orders normally
mainChannel.send(order);  // → Order Consumer

// Tap flow: receives copy for monitoring
monitoringChannel.subscribe(msg -> {
  logger.info("Order received: " + msg);
  metrics.increment("orders.received");
  // Original message still reaches Order Consumer
});`}
        </pre>
      </Card>

      <Card className="p-4">
        <h3 className="font-bold text-sm mb-2">Pattern Description</h3>
        <div className="text-sm space-y-2 text-gray-700">
          <p><strong>Wire Tap</strong> inspects messages on a channel without affecting the main flow.</p>
          <ul className="list-disc list-inside space-y-1">
            <li><strong>Non-invasive:</strong> Copies messages without consuming them</li>
            <li><strong>Parallel Processing:</strong> Main flow and tap flow execute independently</li>
            <li><strong>Zero Impact:</strong> Tap failures don't affect main flow</li>
            <li><strong>Minimal Overhead:</strong> Async copying adds ~1-2ms latency</li>
            <li><strong>Use Cases:</strong> Monitoring, logging, analytics, debugging, audit trails</li>
          </ul>
        </div>
      </Card>
    </div>
  );
}
