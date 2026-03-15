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
  type: string;
  payload: any;
  timestamp: number;
}

interface RouterNodeData {
  label: string;
  type: 'input' | 'router' | 'channel';
  messageCount?: number;
  condition?: string;
  route?: string;
}

const nodeTypes = {
  input: ({ data }: { data: RouterNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-green-50 to-green-100 border-green-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📥</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Input</Badge>
        </div>
      </div>
    </Card>
  ),
  router: ({ data }: { data: RouterNodeData }) => (
    <Card className="p-4 min-w-[200px] bg-gradient-to-br from-purple-50 to-purple-100 border-purple-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">🔀</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Router</Badge>
        </div>
      </div>
      <div className="text-xs space-y-1">
        <div className="text-gray-600">Route based on message content</div>
      </div>
    </Card>
  ),
  channel: ({ data }: { data: RouterNodeData }) => (
    <Card className="p-4 min-w-[180px] bg-gradient-to-br from-blue-50 to-blue-100 border-blue-300">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">📨</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <Badge variant="outline" className="text-xs">Channel</Badge>
        </div>
      </div>
      {data.condition && (
        <div className="text-xs bg-white/50 px-2 py-1 rounded">
          <span className="text-gray-600">When:</span> {data.condition}
        </div>
      )}
      <div className="flex justify-between text-xs mt-2">
        <span className="text-gray-600">Messages:</span>
        <span className="font-mono font-semibold">{data.messageCount || 0}</span>
      </div>
    </Card>
  )
};

const initialNodes: Node<any>[] = [
  {
    id: 'input',
    type: 'input',
    position: { x: 50, y: 150 },
    data: { label: 'Message Input', type: 'input' }
  },
  {
    id: 'router',
    type: 'router',
    position: { x: 300, y: 150 },
    data: { label: 'Content Router', type: 'router' }
  },
  {
    id: 'gold',
    type: 'channel',
    position: { x: 600, y: 50 },
    data: {
      label: 'Gold Queue',
      type: 'channel',
      condition: 'priority == "GOLD"',
      messageCount: 0
    }
  },
  {
    id: 'silver',
    type: 'channel',
    position: { x: 600, y: 150 },
    data: {
      label: 'Silver Queue',
      type: 'channel',
      condition: 'priority == "SILVER"',
      messageCount: 0
    }
  },
  {
    id: 'bronze',
    type: 'channel',
    position: { x: 600, y: 250 },
    data: {
      label: 'Bronze Queue',
      type: 'channel',
      condition: 'priority == "BRONZE"',
      messageCount: 0
    }
  }
];

const initialEdges: Edge[] = [
  { id: 'e1', source: 'input', target: 'router', animated: true, style: { stroke: '#22c55e' } },
  { id: 'e2', source: 'router', target: 'gold', animated: false, label: 'GOLD', style: { stroke: '#f59e0b' } },
  { id: 'e3', source: 'router', target: 'silver', animated: false, label: 'SILVER', style: { stroke: '#94a3b8' } },
  { id: 'e4', source: 'router', target: 'bronze', animated: false, label: 'BRONZE', style: { stroke: '#cd7f32' } }
];

export function ContentBasedRouterFlow() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  const [isRunning, setIsRunning] = useState(false);
  const [currentRoute, setCurrentRoute] = useState<string>('');
  const [routingTable, setRoutingTable] = useState<Record<string, number>>({
    gold: 0,
    silver: 0,
    bronze: 0
  });

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const routeMessage = useCallback((priority: 'GOLD' | 'SILVER' | 'BRONZE') => {
    setCurrentRoute(priority);

    // Animate input to router
    setEdges((eds) =>
      eds.map((edge) => {
        if (edge.source === 'input' && edge.target === 'router') {
          return { ...edge, animated: true };
        }
        return edge;
      })
    );

    setTimeout(() => {
      // Animate router to appropriate channel
      setEdges((eds) =>
        eds.map((edge) => {
          if (edge.source === 'router') {
            const isTargetRoute = edge.label === priority;
            return { ...edge, animated: isTargetRoute, style: { ...edge.style, strokeWidth: isTargetRoute ? 3 : 1 } };
          }
          return edge;
        })
      );
    }, 500);

    setTimeout(() => {
      // Update channel message count
      const targetChannel = priority.toLowerCase();
      setNodes((nds) =>
        nds.map((node) => {
          if (node.id === targetChannel) {
            return {
              ...node,
              data: { ...node.data, messageCount: node.data.messageCount! + 1 }
            };
          }
          return node;
        })
      );

      // Reset animations
      setEdges((eds) =>
        eds.map((edge) => {
          return { ...edge, animated: false, style: { ...edge.style, strokeWidth: 1 } };
        })
      );

      setRoutingTable((prev) => ({
        ...prev,
        [targetChannel]: prev[targetChannel] + 1
      }));
    }, 1500);
  }, [setNodes, setEdges]);

  const startFlow = useCallback(() => {
    setIsRunning(true);
    const priorities: Array<'GOLD' | 'SILVER' | 'BRONZE'> = ['GOLD', 'SILVER', 'BRONZE'];
    const interval = setInterval(() => {
      const randomPriority = priorities[Math.floor(Math.random() * priorities.length)];
      routeMessage(randomPriority);
    }, 2000);
    return () => clearInterval(interval);
  }, [routeMessage]);

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
          <h2 className="text-2xl font-bold">Content-Based Router</h2>
          <p className="text-sm text-gray-600 mt-1">
            Route messages based on content and conditions
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
            <Button onClick={() => routeMessage('GOLD')} disabled={isRunning} variant="outline">
              🥇 Gold
            </Button>
            <Button onClick={() => routeMessage('SILVER')} disabled={isRunning} variant="outline">
              🥈 Silver
            </Button>
            <Button onClick={() => routeMessage('BRONZE')} disabled={isRunning} variant="outline">
              🥉 Bronze
            </Button>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <Card className="p-4">
          <div className="text-sm text-gray-600">Total Messages</div>
          <div className="text-2xl font-bold font-mono">
            {Object.values(routingTable).reduce((a, b) => a + b, 0)}
          </div>
        </Card>
        <Card className="p-4 border-yellow-400">
          <div className="text-sm text-gray-600">Gold Route</div>
          <div className="text-2xl font-bold font-mono text-yellow-600">{routingTable.gold}</div>
        </Card>
        <Card className="p-4 border-gray-400">
          <div className="text-sm text-gray-600">Silver Route</div>
          <div className="text-2xl font-bold font-mono text-gray-600">{routingTable.silver}</div>
        </Card>
        <Card className="p-4 border-orange-400">
          <div className="text-sm text-gray-600">Bronze Route</div>
          <div className="text-2xl font-bold font-mono text-orange-600">{routingTable.bronze}</div>
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

      {currentRoute && (
        <Card className="p-4 bg-gradient-to-r from-blue-50 to-purple-50">
          <div className="flex items-center gap-2">
            <Badge variant="outline">Current Route</Badge>
            <span className="font-mono font-bold">{currentRoute}</span>
            <span className="text-sm text-gray-600">→ Message routed based on priority field</span>
          </div>
        </Card>
      )}

      <Card className="p-4 bg-gray-50">
        <h3 className="font-bold text-sm mb-2">Java Implementation</h3>
        <pre className="text-xs bg-gray-900 text-green-400 p-3 rounded overflow-x-auto">
{`// Content-based routing with pattern matching
var router = new ContentBasedRouter<Order>();

router.when(order -> order.getPriority() == Priority.GOLD)
      .to(goldQueue);

router.when(order -> order.getPriority() == Priority.SILVER)
      .to(silverQueue);

router.when(order -> order.getPriority() == Priority.BRONZE)
      .to(bronzeQueue);

// Route messages automatically
router.route(incomingOrder);

// Or use switch expression (Java 26)
String queueId = switch (order.getPriority()) {
  case GOLD -> "gold-queue";
  case SILVER -> "silver-queue";
  case BRONZE -> "bronze-queue";
};`}
        </pre>
      </Card>

      <Card className="p-4">
        <h3 className="font-bold text-sm mb-2">Pattern Description</h3>
        <div className="text-sm space-y-2 text-gray-700">
          <p><strong>Content-Based Router</strong> examines message content and routes to appropriate channel.</p>
          <ul className="list-disc list-inside space-y-1">
            <li><strong>Conditional Routing:</strong> Routes based on message fields or content</li>
            <li><strong>Dynamic Dispatch:</strong> No hard-coded destinations</li>
            <li><strong>Extensibility:</strong> Add new routes without changing existing logic</li>
            <li><strong>Performance:</strong> O(n) routing complexity where n = number of conditions</li>
            <li><strong>Use Cases:</strong> Priority routing, geographic routing, order type routing</li>
          </ul>
        </div>
      </Card>
    </div>
  );
}
