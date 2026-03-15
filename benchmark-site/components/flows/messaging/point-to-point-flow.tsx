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

interface Message {
  id: string;
  payload: string;
  timestamp: number;
}

interface PointToPointNodeData {
  label: string;
  type: 'producer' | 'channel' | 'consumer';
  queueDepth?: number;
  messageRate?: number;
  processingTime?: number;
}

const nodeTypes = {
  producer: ({ data }: { data: PointToPointNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-green-50 to-green-100 border-green-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📤</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Producer</Badge>
        </div>
      </div>
      <div className="text-xs space-y-1">
        <div className="flex justify-between">
          <span className="text-gray-600">Rate:</span>
          <span className="font-mono font-semibold">{data.messageRate || 0} msg/s</span>
        </div>
      </div>
    </Card>
  ),
  channel: ({ data }: { data: PointToPointNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-blue-50 to-blue-100 border-blue-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📨</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Channel</Badge>
        </div>
      </div>
      <div className="text-xs space-y-1">
        <div className="flex justify-between">
          <span className="text-gray-600">Queue:</span>
          <span className="font-mono font-semibold">{data.queueDepth || 0}</span>
        </div>
      </div>
    </Card>
  ),
  consumer: ({ data }: { data: PointToPointNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-purple-50 to-purple-100 border-purple-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📥</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Consumer</Badge>
        </div>
      </div>
      <div className="text-xs space-y-1">
        <div className="flex justify-between">
          <span className="text-gray-600">Processing:</span>
          <span className="font-mono font-semibold">{data.processingTime || 0}ms</span>
        </div>
      </div>
    </Card>
  )
};

const initialNodes: Node<any>[] = [
  {
    id: 'producer',
    type: 'producer',
    position: { x: 50, y: 150 },
    data: { label: 'Order Producer', type: 'producer', messageRate: 0 }
  },
  {
    id: 'channel',
    type: 'channel',
    position: { x: 350, y: 150 },
    data: { label: 'Order Queue', type: 'channel', queueDepth: 0 }
  },
  {
    id: 'consumer1',
    type: 'consumer',
    position: { x: 650, y: 80 },
    data: { label: 'Order Handler 1', type: 'consumer', processingTime: 0 }
  },
  {
    id: 'consumer2',
    type: 'consumer',
    position: { x: 650, y: 220 },
    data: { label: 'Order Handler 2', type: 'consumer', processingTime: 0 }
  }
];

const initialEdges: Edge[] = [
  { id: 'e1', source: 'producer', target: 'channel', animated: true, style: { stroke: '#22c55e' } },
  { id: 'e2', source: 'channel', target: 'consumer1', animated: false, style: { stroke: '#3b82f6' } },
  { id: 'e3', source: 'channel', target: 'consumer2', animated: false, style: { stroke: '#3b82f6' } }
];

export function PointToPointFlow() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  const [messages, setMessages] = useState<Message[]>([]);
  const [isRunning, setIsRunning] = useState(false);
  const [stats, setStats] = useState({
    totalMessages: 0,
    avgQueueDepth: 0,
    avgProcessingTime: 0,
    loadBalance: { consumer1: 0, consumer2: 0 }
  });

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const sendMessage = useCallback(() => {
    const messageId = `msg-${Date.now()}-${Math.random()}`;
    const newMessage: Message = {
      id: messageId,
      payload: `Order #${Math.floor(Math.random() * 10000)}`,
      timestamp: Date.now()
    };

    setMessages((prev) => [...prev, newMessage]);

    // Animate message through channel
    setNodes((nds) =>
      nds.map((node) => {
        if (node.id === 'channel') {
          return {
            ...node,
            data: { ...node.data, queueDepth: Math.random() * 10 + 1 }
          };
        }
        if (node.id === 'producer') {
          return {
            ...node,
            data: { ...node.data, messageRate: Math.random() * 100 + 50 }
          };
        }
        return node;
      })
    );

    // Randomly select consumer (load balancing)
    const targetConsumer = Math.random() > 0.5 ? 'consumer1' : 'consumer2';
    const processingTime = Math.random() * 50 + 10;

    setEdges((eds) =>
      eds.map((edge) => {
        if (edge.target === targetConsumer) {
          return { ...edge, animated: true };
        }
        if (edge.source === 'channel') {
          return { ...edge, animated: false };
        }
        return edge;
      })
    );

    setTimeout(() => {
      setNodes((nds) =>
        nds.map((node) => {
          if (node.id === targetConsumer) {
            return {
              ...node,
              data: { ...node.data, processingTime }
            };
          }
          if (node.id === 'channel') {
            return {
              ...node,
              data: { ...node.data, queueDepth: Math.max(0, node.data.queueDepth! - 1) }
            };
          }
          return node;
        })
      );

      setEdges((eds) =>
        eds.map((edge) => {
          if (edge.target === targetConsumer) {
            return { ...edge, animated: false };
          }
          return edge;
        })
      );

      setStats((prev) => ({
        totalMessages: prev.totalMessages + 1,
        avgQueueDepth: Math.random() * 5 + 2,
        avgProcessingTime: (prev.avgProcessingTime * prev.totalMessages + processingTime) / (prev.totalMessages + 1),
        loadBalance: {
          ...prev.loadBalance,
          [targetConsumer]: prev.loadBalance[targetConsumer as keyof typeof prev.loadBalance] + 1
        }
      }));
    }, 1500);
  }, [setNodes, setEdges]);

  const startFlow = useCallback(() => {
    setIsRunning(true);
    const interval = setInterval(sendMessage, 2000);
    return () => {
      clearInterval(interval);
      setIsRunning(false);
    };
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
          <h2 className="text-2xl font-bold">Point-to-Point Channel</h2>
          <p className="text-sm text-gray-600 mt-1">
            One-to-one message delivery with competing consumers pattern
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
            📨 Send Single Message
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <Card className="p-4">
          <div className="text-sm text-gray-600">Total Messages</div>
          <div className="text-2xl font-bold font-mono">{stats.totalMessages}</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Avg Queue Depth</div>
          <div className="text-2xl font-bold font-mono">{stats.avgQueueDepth.toFixed(1)}</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Avg Processing</div>
          <div className="text-2xl font-bold font-mono">{stats.avgProcessingTime.toFixed(1)}ms</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Load Balance</div>
          <div className="text-sm font-mono">
            C1: {stats.loadBalance.consumer1} / C2: {stats.loadBalance.consumer2}
          </div>
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
{`// Point-to-point channel with competing consumers
var channel = new PointToPointChannel<Order>();

// Start competing consumers
Proc.spawn(() -> new OrderHandler("handler-1", channel));
Proc.spawn(() -> new OrderHandler("handler-2", channel));

// Producer sends messages
Proc.spawn(() -> {
  while (true) {
    var order = generateOrder();
    channel.send(order);  // Load balanced to available consumer
    Thread.sleep(100);
  }
});`}
        </pre>
      </Card>

      <Card className="p-4">
        <h3 className="font-bold text-sm mb-2">Pattern Description</h3>
        <div className="text-sm space-y-2 text-gray-700">
          <p><strong>Point-to-Point Channel</strong> ensures each message is consumed by exactly one consumer.</p>
          <ul className="list-disc list-inside space-y-1">
            <li><strong>Competing Consumers:</strong> Multiple consumers compete for messages from the same channel</li>
            <li><strong>Load Balancing:</strong> Messages are distributed across available consumers</li>
            <li><strong>Scalability:</strong> Add more consumers to increase throughput</li>
            <li><strong>Use Cases:</strong> Order processing, task queues, job distribution</li>
          </ul>
        </div>
      </Card>
    </div>
  );
}
