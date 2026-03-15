'use client';

import React, { useCallback, useMemo, useState } from 'react';
import {
  ReactFlow as ReactFlowComponent,
  Background,
  Controls,
  MiniMap,
  Node,
  Edge,
  useNodesState,
  useEdgesState,
  BackgroundVariant,
  NodeTypes,
  Position,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { SupervisorNode } from '../nodes/supervisor-node';
import { ChildNode } from '../nodes/child-node';
import { useTheme } from 'next-themes';

// Types
export type RestartStrategy = 'ONE_FOR_ONE' | 'ONE_FOR_ALL' | 'REST_FOR_ONE' | 'SIMPLE_ONE_FOR_ONE';
export type RestartType = 'PERMANENT' | 'TRANSIENT' | 'TEMPORARY';
export type ChildState = 'running' | 'restarting' | 'stopped' | 'crashed';

export interface ChildSpec {
  id: string;
  name: string;
  restartType: RestartType;
  state: ChildState;
  childType: 'WORKER' | 'SUPERVISOR';
  shutdown?: number;
}

export interface SupervisorNodeData {
  id: string;
  name: string;
  strategy: RestartStrategy;
  intensity: number;
  period: number;
  children: ChildSpec[];
  isExpanded: boolean;
  restartCount: number;
  lastRestart?: Date;
  onToggle?: () => void;
}

interface SupervisionTreeProps {
  initialData?: SupervisorNodeData[];
  onCrash?: (supervisorId: string, childId: string) => void;
  onNodeClick?: (nodeId: string, data: any) => void;
  className?: string;
}

const nodeTypes: any = {
  supervisor: SupervisorNode,
  child: ChildNode,
};

// Convert tree data to ReactFlow nodes and edges
const buildFlowElements = (
  data: SupervisorNodeData[],
  theme: string | undefined
): { nodes: Node[]; edges: Edge[] } => {
  const nodes: Node[] = [];
  const edges: Edge[] = [];
  const isDark = theme === 'dark';

  let nodeId = 0;
  const addNode = (
    id: string,
    type: string,
    position: { x: number; y: number },
    data: any
  ) => {
    nodes.push({
      id,
      type,
      position,
      data,
      sourcePosition: Position.Bottom,
      targetPosition: Position.Top,
      style: {
        background: isDark ? '#1a1a2e' : '#ffffff',
        border: isDark ? '#4a5568' : '#e2e8f0',
      },
    });
  };

  const processSupervisor = (
    supervisor: SupervisorNodeData,
    x: number,
    y: number,
    parentId?: string
  ) => {
    const supervisorId = supervisor.id;
    const supervisorY = y;

    // Add supervisor node
    addNode(supervisorId, 'supervisor', { x, y: supervisorY }, {
      ...supervisor,
      onToggle: () => {}, // Will be set by parent
    });

    // Edge from parent
    if (parentId) {
      edges.push({
        id: `${parentId}-${supervisorId}`,
        source: parentId,
        target: supervisorId,
        type: 'smoothstep',
        animated: false,
        style: {
          stroke: isDark ? '#4a5568' : '#cbd5e0',
          strokeWidth: 2,
        },
      });
    }

    // Add children if expanded
    if (supervisor.isExpanded && supervisor.children.length > 0) {
      const childCount = supervisor.children.length;
      const spacing = 200;
      const totalWidth = (childCount - 1) * spacing;
      const startX = x - totalWidth / 2;
      const childY = y + 180;

      supervisor.children.forEach((child, index) => {
        const childX = startX + index * spacing;
        const childId = `${supervisorId}-${child.id}`;

        // Check if child is a supervisor (nested)
        if (child.childType === 'SUPERVISOR') {
          // Create nested supervisor
          const nestedSupervisor: SupervisorNodeData = {
            id: childId,
            name: child.name,
            strategy: 'ONE_FOR_ONE', // Default for nested
            intensity: 3,
            period: 5000,
            children: [],
            isExpanded: false,
            restartCount: 0,
          };

          // Find child supervisor data if available
          const childData = data.find(d => d.id === child.id);
          if (childData) {
            Object.assign(nestedSupervisor, childData);
          }

          processSupervisor(nestedSupervisor, childX, childY, supervisorId);
        } else {
          // Regular child node
          addNode(childId, 'child', { x: childX, y: childY }, {
            ...child,
            supervisorId: supervisorId,
            supervisorStrategy: supervisor.strategy,
          });

          // Edge from supervisor to child
          edges.push({
            id: `${supervisorId}-${childId}`,
            source: supervisorId,
            target: childId,
            type: 'smoothstep',
            animated: child.state === 'restarting',
            style: {
              stroke: getStrokeColor(child.state),
              strokeWidth: child.state === 'restarting' ? 3 : 2,
            },
          });
        }
      });
    }
  };

  // Process all top-level supervisors
  const rootCount = data.length;
  const rootSpacing = 400;
  const totalRootWidth = (rootCount - 1) * rootSpacing;
  const rootStartX = -totalRootWidth / 2;

  data.forEach((supervisor, index) => {
    const x = rootStartX + index * rootSpacing;
    processSupervisor(supervisor, x, 0);
  });

  return { nodes, edges };
};

const getStrokeColor = (state: ChildState): string => {
  switch (state) {
    case 'running':
      return '#10b981';
    case 'restarting':
      return '#f59e0b';
    case 'crashed':
      return '#ef4444';
    case 'stopped':
      return '#6b7280';
    default:
      return '#cbd5e0';
  }
};

export const SupervisionTree: React.FC<SupervisionTreeProps> = ({
  initialData,
  onCrash,
  onNodeClick,
  className = '',
}) => {
  const { theme } = useTheme();
  const [treeData, setTreeData] = useState<SupervisorNodeData[]>(
    initialData || getDefaultTreeData()
  );

  const toggleNodeExpansion = useCallback((supervisorId: string) => {
    setTreeData(prev =>
      prev.map(supervisor => {
        if (supervisor.id === supervisorId) {
          return { ...supervisor, isExpanded: !supervisor.isExpanded };
        }
        return supervisor;
      })
    );
  }, []);

  const simulateCrash = useCallback(
    (supervisorId: string, childId: string) => {
      // Update child state to crashed
      setTreeData(prev =>
        prev.map(supervisor => {
          if (supervisor.id === supervisorId) {
            const updatedChildren = supervisor.children.map(child => {
              if (child.id === childId.replace(`${supervisorId}-`, '')) {
                return { ...child, state: 'crashed' as ChildState };
              }
              return child;
            });
            return {
              ...supervisor,
              children: updatedChildren,
              restartCount: supervisor.restartCount + 1,
              lastRestart: new Date(),
            };
          }
          return supervisor;
        })
      );

      // Animate restart
      setTimeout(() => {
        setTreeData(prev =>
          prev.map(supervisor => {
            if (supervisor.id === supervisorId) {
              const updatedChildren = supervisor.children.map(child => {
                if (child.id === childId.replace(`${supervisorId}-`, '')) {
                  return { ...child, state: 'restarting' as ChildState };
                }
                return child;
              });
              return { ...supervisor, children: updatedChildren };
            }
            return supervisor;
          })
        );
      }, 500);

      // Return to running
      setTimeout(() => {
        setTreeData(prev =>
          prev.map(supervisor => {
            if (supervisor.id === supervisorId) {
              const updatedChildren = supervisor.children.map(child => {
                if (child.id === childId.replace(`${supervisorId}-`, '')) {
                  return { ...child, state: 'running' as ChildState };
                }
                return child;
              });
              return { ...supervisor, children: updatedChildren };
            }
            return supervisor;
          })
        );
      }, 2000);

      onCrash?.(supervisorId, childId);
    },
    [onCrash]
  );

  const handleNodeClick = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      if (node.type === 'supervisor') {
        toggleNodeExpansion(node.id);
      }
      onNodeClick?.(node.id, node.data);
    },
    [toggleNodeExpansion, onNodeClick]
  );

  // Build flow elements
  const { nodes: initialNodes, edges: initialEdges } = useMemo(
    () => buildFlowElements(treeData, theme),
    [treeData, theme]
  );

  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

  // Update nodes/edges when tree data or theme changes
  React.useEffect(() => {
    const { nodes: newNodes, edges: newEdges } = buildFlowElements(treeData, theme);
    setNodes(newNodes);
    setEdges(newEdges);
  }, [treeData, theme, setNodes, setEdges]);

  return (
    <div className={`supervision-tree-wrapper ${className}`}>
      <style jsx global>{`
        .supervision-tree-wrapper {
          width: 100%;
          height: 600px;
          border-radius: 8px;
          overflow: hidden;
        }

        .supervision-tree-wrapper .react-flow {
          background: ${theme === 'dark' ? '#0f172a' : '#f8fafc'};
        }

        .supervision-tree-wrapper .react-flow__node {
          cursor: pointer;
          transition: all 0.2s ease;
        }

        .supervision-tree-wrapper .react-flow__node:hover {
          filter: brightness(1.1);
        }

        .supervision-tree-wrapper .react-flow__edge-path {
          stroke-width: 2;
        }

        .supervision-tree-wrapper .react-flow__controls {
          background: ${theme === 'dark' ? '#1e293b' : '#ffffff'};
          border: 1px solid ${theme === 'dark' ? '#334155' : '#e2e8f0'};
          border-radius: 8px;
        }

        .supervision-tree-wrapper .react-flow__controls-button {
          background: ${theme === 'dark' ? '#334155' : '#f1f5f9'};
          border-color: ${theme === 'dark' ? '#475569' : '#cbd5e0'};
          fill: ${theme === 'dark' ? '#e2e8f0' : '#475569'};
        }

        .supervision-tree-wrapper .react-flow__minimap {
          background: ${theme === 'dark' ? '#1e293b' : '#ffffff'};
          border: 1px solid ${theme === 'dark' ? '#334155' : '#e2e8f0'};
          border-radius: 8px;
        }
      `}</style>
      <ReactFlowComponent
        nodes={nodes.map(node => ({
          ...node,
          data: {
            ...node.data,
            onToggle: () => toggleNodeExpansion(node.id),
            onCrash: (childId: string) => simulateCrash(node.id, childId),
          },
        }))}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={handleNodeClick}
        nodeTypes={nodeTypes}
        fitView
        attributionPosition="bottom-left"
      >
        <Background
          variant={theme === 'dark' ? BackgroundVariant.Dots : BackgroundVariant.Dots}
          gap={16}
          size={1}
          color={theme === 'dark' ? '#334155' : '#cbd5e0'}
        />
        <Controls />
        <MiniMap
          nodeColor={(node) => {
            if (node.type === 'supervisor') return '#3b82f6';
            if (node.type === 'child') return '#10b981';
            return '#6b7280';
          }}
          maskColor="rgba(0, 0, 0, 0.1)"
        />
      </ReactFlowComponent>
    </div>
  );
};

// Default tree data for demonstration
function getDefaultTreeData(): SupervisorNodeData[] {
  return [
    {
      id: 'root-supervisor',
      name: 'RootSupervisor',
      strategy: 'ONE_FOR_ONE',
      intensity: 5,
      period: 5000,
      isExpanded: true,
      restartCount: 0,
      children: [
        {
          id: 'tenant-a-supervisor',
          name: 'TenantA_Supervisor',
          restartType: 'PERMANENT',
          state: 'running',
          childType: 'SUPERVISOR',
        },
        {
          id: 'tenant-b-supervisor',
          name: 'TenantB_Supervisor',
          restartType: 'PERMANENT',
          state: 'running',
          childType: 'SUPERVISOR',
        },
        {
          id: 'metrics-service',
          name: 'MetricsService',
          restartType: 'TEMPORARY',
          state: 'running',
          childType: 'WORKER',
        },
      ],
    },
  ];
}
