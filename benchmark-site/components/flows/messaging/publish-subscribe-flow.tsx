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
  topic: string;
  payload: string;
  timestamp: number;
}

interface PubSubNodeData {
  label: string;
  type: 'publisher' | 'topic' | 'subscriber';
  messageCount?: number;
  topics?: string[];
}

const nodeTypes = {
  publisher: ({ data }: { data: PubSubNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-green-50 to-green-100 border-green-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📢</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Publisher</Badge>
        </div>
      </div>
      {data.topics && data.topics.length > 0 && (
        <div className="text-xs space-y-1">
          <div className="text-gray-600">Topics:</div>
          <div className="flex flex-wrap gap-1">
            {data.topics.map((topic, i) => (
              <Badge key={i} variant="secondary" className="text-xs">{topic}</Badge>
            ))}
          </div>
        </div>
      )}
    </Card>
  ),
  topic: ({ data }: { data: PubSubNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-blue-50 to-blue-100 border-blue-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📡</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Topic</Badge>
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
  subscriber: ({ data }: { data: PubSubNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-purple-50 to-purple-100 border-purple-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">👂</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Subscriber</Badge>
        </div>
      </div>
      {data.topics && data.topics.length > 0 && (
        <div className="text-xs space-y-1">
          <div className="text-gray-600">Subscribed to:</div>
          <div className="flex flex-wrap gap-1">
            {data.topics.map((topic, i) => (
              <Badge key={i} variant="secondary" className="text-xs">{topic}</Badge>
            ))}
          </div>
        </div>
      )}
    </Card>
  )
};

const initialNodes: Node<any>[] = [
  {
    id: 'publisher1',
    type: 'publisher',
    position: { x: 50, y: 50 },
    data: { label: 'Order Service', type: 'publisher', topics: ['orders.created', 'orders.updated'] }
  },
  {
    id: 'publisher2',
    type: 'publisher',
    position: { x: 50, y: 250 },
    data: { label: 'Payment Service', type: 'publisher', topics: ['payments.completed'] }
  },
  {
    id: 'topic1',
    type: 'topic',
    position: { x: 350, y: 50 },
    data: { label: 'orders.created', type: 'topic', messageCount: 0 }
  },
  {
    id: 'topic2',
    type: 'topic',
    position: { x: 350, y: 150 },
    data: { label: 'orders.updated', type: 'topic', messageCount: 0 }
  },
  {
    id: 'topic3',
    type: 'topic',
    position: { x: 350, y: 250 },
    data: { label: 'payments.completed', type: 'topic', messageCount: 0 }
  },
  {
    id: 'subscriber1',
    type: 'subscriber',
    position: { x: 650, y: 50 },
    data: { label: 'Email Service', type: 'subscriber', topics: ['orders.created'] }
  },
  {
    id: 'subscriber2',
    type: 'subscriber',
    position: { x: 650, y: 150 },
    data: { label: 'Inventory Service', type: 'subscriber', topics: ['orders.created', 'orders.updated'] }
  },
  {
    id: 'subscriber3',
    type: 'subscriber',
    position: { x: 650, y: 250 },
    data: { label: 'Analytics Service', type: 'subscriber', topics: ['orders.created', 'payments.completed'] }
  }
];

const initialEdges: Edge[] = [
  { id: 'e1', source: 'publisher1', target: 'topic1', animated: true, style: { stroke: '#22c55e' } },
  { id: 'e2', source: 'publisher1', target: 'topic2', animated: false, style: { stroke: '#22c55e' } },
  { id: 'e3', source: 'publisher2', target: 'topic3', animated: false, style: { stroke: '#22c55e' } },
  { id: 'e4', source: 'topic1', target: 'subscriber1', animated: true, style: { stroke: '#3b82f6' } },
  { id: 'e5', source: 'topic1', target: 'subscriber2', animated: true, style: { stroke: '#3b82f6' } },
  { id: 'e6', source: 'topic2', target: 'subscriber2', animated: false, style: { stroke: '#3b82f6' } },
  { id: 'e7', source: 'topic1', target: 'subscriber3', animated: true, style: { stroke: '#3b82f6' } },
  { id: 'e8', source: 'topic3', target: 'subscriber3', animated: false, style: { stroke: '#3b82f6' } }
];

export function PublishSubscribeFlow() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  const [isRunning, setIsRunning] = useState(false);
  const [stats, setStats] = useState({
    totalMessages: 0,
    topicMessages: { 'orders.created': 0, 'orders.updated': 0, 'payments.completed': 0 },
    subscriberDeliveries: { 'Email Service': 0, 'Inventory Service': 0, 'Analytics Service': 0 }
  });

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const publishMessage = useCallback((topic: string) => {
    setNodes((nds) =>
      nds.map((node) => {
        if (node.data.label === topic) {
          return {
            ...node,
            data: { ...node.data, messageCount: node.data.messageCount! + 1 }
          };
        }
        return node;
      })
    );

    // Animate edges to subscribers
    setEdges((eds) =>
      eds.map((edge) => {
        if (edge.source === nodes.find(n => n.data.label === topic)?.id) {
          return { ...edge, animated: true };
        }
        return edge;
      })
    );

    setTimeout(() => {
      setEdges((eds) =>
        eds.map((edge) => {
          return { ...edge, animated: false };
        })
      );

      // Update delivery counts
      const topicSubscribers: Record<string, string[]> = {
        'orders.created': ['Email Service', 'Inventory Service', 'Analytics Service'],
        'orders.updated': ['Inventory Service'],
        'payments.completed': ['Analytics Service']
      };

      setStats((prev) => ({
        totalMessages: prev.totalMessages + 1,
        topicMessages: {
          ...prev.topicMessages,
          [topic]: prev.topicMessages[topic as keyof typeof prev.topicMessages] + 1
        },
        subscriberDeliveries: {
          ...prev.subscriberDeliveries,
          ...(topicSubscribers[topic]?.reduce((acc, sub) => ({
            ...acc,
            [sub]: prev.subscriberDeliveries[sub as keyof typeof prev.subscriberDeliveries] + 1
          }), {}))
        }
      }));
    }, 1000);
  }, [nodes, setNodes, setEdges]);

  const startFlow = useCallback(() => {
    setIsRunning(true);
    const topics = ['orders.created', 'orders.updated', 'payments.completed'];
    const interval = setInterval(() => {
      const randomTopic = topics[Math.floor(Math.random() * topics.length)];
      publishMessage(randomTopic);
    }, 2000);
    return () => clearInterval(interval);
  }, [publishMessage]);

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
          <h2 className="text-2xl font-bold">Publish-Subscribe Channel</h2>
          <p className="text-sm text-gray-600 mt-1">
            Topic-based broadcast to multiple subscribers with isolation
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
            <Button onClick={() => publishMessage('orders.created')} disabled={isRunning} variant="outline">
              📦 Order
            </Button>
            <Button onClick={() => publishMessage('orders.updated')} disabled={isRunning} variant="outline">
              🔄 Update
            </Button>
            <Button onClick={() => publishMessage('payments.completed')} disabled={isRunning} variant="outline">
              💳 Payment
            </Button>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <Card className="p-4">
          <div className="text-sm text-gray-600">Total Messages</div>
          <div className="text-2xl font-bold font-mono">{stats.totalMessages}</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Orders Created</div>
          <div className="text-2xl font-bold font-mono">{stats.topicMessages['orders.created']}</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Payments Completed</div>
          <div className="text-2xl font-bold font-mono">{stats.topicMessages['payments.completed']}</div>
        </Card>
        <Card className="p-4">
          <div className="text-sm text-gray-600">Total Deliveries</div>
          <div className="text-2xl font-bold font-mono">
            {Object.values(stats.subscriberDeliveries).reduce((a, b) => a + b, 0)}
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
{`// Publish-subscribe with topic hierarchy
var topic = new PublishSubscribeChannel<OrderCreated>("orders.created");

// Multiple subscribers receive same message
topic.subscribe(event -> emailService.sendConfirmation(event));
topic.subscribe(event -> inventoryService.reserveItems(event));
topic.subscribe(event -> analyticsService.trackEvent(event));

// Publisher broadcasts to all subscribers
Proc.spawn(() -> {
  while (true) {
    var order = createOrder();
    topic.publish(new OrderCreated(order));
    Thread.sleep(100);
  }
});`}
        </pre>
      </Card>

      <Card className="p-4">
        <h3 className="font-bold text-sm mb-2">Pattern Description</h3>
        <div className="text-sm space-y-2 text-gray-700">
          <p><strong>Publish-Subscribe Channel</strong> broadcasts each message to all subscribed consumers.</p>
          <ul className="list-disc list-inside space-y-1">
            <li><strong>Topic-based:</strong> Messages organized by topic hierarchy</li>
            <li><strong>Subscriber Isolation:</strong> Each subscriber processes independently</li>
            <li><strong>Fan-out:</strong> One message reaches multiple consumers simultaneously</li>
            <li><strong>Decoupling:</strong> Publishers don't know who is subscribing</li>
            <li><strong>Use Cases:</strong> Event notifications, data synchronization, analytics pipelines</li>
          </ul>
        </div>
      </Card>
    </div>
  );
}
