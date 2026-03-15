'use client';

import React, { useCallback, useState, useMemo, useEffect } from 'react';
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
  Node,
  MarkerType
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { ComponentNode } from './nodes/component-node';
import {
  JOTP_COMPONENTS,
  ARCHITECTURE_LAYERS,
  getComponentById,
  getComponentsByCategory,
  getCategoryColor,
  searchComponents,
  type ComponentCategory
} from '@/lib/data/jotp-architecture';

const nodeTypes = {
  component: ComponentNode
};

interface FullArchitectureProps {
  initialCategories?: ComponentCategory[];
  searchable?: boolean;
  exportable?: boolean;
}

export function FullArchitectureFlow({
  initialCategories = ['core', 'lifecycle', 'enterprise', 'observability'],
  searchable = true,
  exportable = true
}: FullArchitectureProps) {
  const [selectedCategories, setSelectedCategories] = useState<ComponentCategory[]>(initialCategories);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedNode, setSelectedNode] = useState<string | null>(null);
  const [showLabels, setShowLabels] = useState(true);

  // Filter components based on selection and search
  const visibleComponents = useMemo(() => {
    let filtered = JOTP_COMPONENTS;

    // Filter by category
    filtered = filtered.filter(c => selectedCategories.includes(c.category));

    // Filter by search
    if (searchQuery) {
      filtered = searchComponents(searchQuery).filter(c => selectedCategories.includes(c.category));
    }

    return filtered;
  }, [selectedCategories, searchQuery]);

  // Create nodes from visible components
  const initialNodes = useMemo(() => {
    const nodes: Node[] = [];
    const layerWidth = 1400;
    const nodeWidth = 220;
    const nodeHeight = 120;
    const horizontalGap = 50;
    const verticalGap = 50;

    ARCHITECTURE_LAYERS.forEach(layer => {
      const layerComponents = visibleComponents.filter(c => c.layer === parseInt(layer.id.replace('layer', '')) || c.layer === ARCHITECTURE_LAYERS.indexOf(layer) + 1);

      if (layerComponents.length === 0) return;

      const layerWidthUsed = layerComponents.length * nodeWidth + (layerComponents.length - 1) * horizontalGap;
      const startX = (layerWidth - layerWidthUsed) / 2;

      layerComponents.forEach((component, index) => {
        nodes.push({
          id: component.id,
          type: 'component',
          position: {
            x: startX + index * (nodeWidth + horizontalGap),
            y: layer.yPosition
          },
          data: {
            label: component.name,
            description: component.description,
            otpEquivalent: component.otpEquivalent,
            features: component.features,
            category: component.category,
            color: component.color,
            status: 'active'
          },
          style: {
            width: nodeWidth,
            height: nodeHeight,
            backgroundColor: `${component.color}10`,
            borderColor: component.color,
            borderWidth: 2
          }
        });
      });
    });

    return nodes;
  }, [visibleComponents]);

  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);

  // Create edges based on dependencies
  const initialEdges = useMemo(() => {
    const edges: Edge[] = [];

    visibleComponents.forEach(component => {
      component.dependencies.forEach(depId => {
        // Only create edge if dependency is visible
        if (visibleComponents.find(c => c.id === depId)) {
          edges.push({
            id: `e${depId}-${component.id}`,
            source: depId,
            target: component.id,
            animated: true,
            style: {
              stroke: '#94a3b8',
              strokeWidth: 2
            },
            markerEnd: {
              type: MarkerType.ArrowClosed,
              color: '#94a3b8'
            },
            type: 'smoothstep'
          });
        }
      });
    });

    return edges;
  }, [visibleComponents]);

  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const onNodeClick = useCallback((_event: React.MouseEvent, node: Node) => {
    setSelectedNode(node.id);
  }, []);

  const toggleCategory = (category: ComponentCategory) => {
    setSelectedCategories(prev =>
      prev.includes(category)
        ? prev.filter(c => c !== category)
        : [...prev, category]
    );
  };

  const resetView = () => {
    setSearchQuery('');
    setSelectedCategories(initialCategories);
    setSelectedNode(null);
  };

  const exportDiagram = async (format: 'png' | 'svg') => {
    // Export functionality would be implemented here
    console.log(`Exporting as ${format}`);
  };

  // Keyboard shortcuts
  useEffect(() => {
    const handleKeyPress = (e: KeyboardEvent) => {
      if (e.key === '/' && searchable) {
        e.preventDefault();
        document.getElementById('architecture-search')?.focus();
      }
      if (e.key === 'r' && !e.ctrlKey && !e.metaKey) {
        resetView();
      }
      if (e.key === 'Escape') {
        setSelectedNode(null);
        setSearchQuery('');
      }
    };

    window.addEventListener('keydown', handleKeyPress);
    return () => window.removeEventListener('keydown', handleKeyPress);
  }, [searchable]);

  const selectedComponent = selectedNode ? getComponentById(selectedNode) : null;

  return (
    <div className="w-full">
      {/* Controls Panel */}
      <div className="mb-4 p-4 bg-white border rounded-lg shadow-sm">
        <div className="flex flex-wrap items-center gap-4">
          {/* Category Filters */}
          <div className="flex flex-wrap gap-2">
            <button
              onClick={() => toggleCategory('core')}
              className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                selectedCategories.includes('core')
                  ? 'bg-blue-500 text-white'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              Core
            </button>
            <button
              onClick={() => toggleCategory('lifecycle')}
              className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                selectedCategories.includes('lifecycle')
                  ? 'bg-purple-500 text-white'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              Lifecycle
            </button>
            <button
              onClick={() => toggleCategory('enterprise')}
              className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                selectedCategories.includes('enterprise')
                  ? 'bg-green-500 text-white'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              Enterprise
            </button>
            <button
              onClick={() => toggleCategory('observability')}
              className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                selectedCategories.includes('observability')
                  ? 'bg-amber-500 text-white'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              Observability
            </button>
            <button
              onClick={() => toggleCategory('messaging')}
              className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                selectedCategories.includes('messaging')
                  ? 'bg-orange-500 text-white'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              Messaging
            </button>
          </div>

          {/* Search */}
          {searchable && (
            <div className="flex-1 min-w-[200px]">
              <input
                id="architecture-search"
                type="text"
                placeholder="Search components... ( / )"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full px-3 py-1.5 border rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          )}

          {/* Actions */}
          <div className="flex gap-2">
            <button
              onClick={resetView}
              className="px-3 py-1.5 bg-gray-100 hover:bg-gray-200 rounded-md text-sm font-medium transition-colors"
              title="Reset view (R)"
            >
              Reset
            </button>
            {exportable && (
              <>
                <button
                  onClick={() => exportDiagram('png')}
                  className="px-3 py-1.5 bg-blue-100 hover:bg-blue-200 text-blue-700 rounded-md text-sm font-medium transition-colors"
                  title="Export as PNG"
                >
                  PNG
                </button>
                <button
                  onClick={() => exportDiagram('svg')}
                  className="px-3 py-1.5 bg-blue-100 hover:bg-blue-200 text-blue-700 rounded-md text-sm font-medium transition-colors"
                  title="Export as SVG"
                >
                  SVG
                </button>
              </>
            )}
          </div>
        </div>

        {/* Legend */}
        <div className="mt-3 pt-3 border-t grid grid-cols-2 md:grid-cols-5 gap-2 text-xs">
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded" style={{ backgroundColor: '#3b82f6' }}></div>
            <span>Core Primitives</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded" style={{ backgroundColor: '#8b5cf6' }}></div>
            <span>Lifecycle</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded" style={{ backgroundColor: '#22c55e' }}></div>
            <span>Enterprise</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded" style={{ backgroundColor: '#f59e0b' }}></div>
            <span>Observability</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded" style={{ backgroundColor: '#f97316' }}></div>
            <span>Messaging</span>
          </div>
        </div>

        {/* Stats */}
        <div className="mt-2 text-xs text-gray-500">
          Showing {visibleComponents.length} of {JOTP_COMPONENTS.length} components | {edges.length} dependencies
        </div>
      </div>

      {/* Flow Diagram */}
      <div className="relative w-full border rounded-lg overflow-hidden" style={{ height: '1200px' }}>
        {/* Component Detail Panel */}
        {selectedComponent && (
          <div className="absolute top-4 left-4 z-10 w-96 bg-white border rounded-lg shadow-lg p-5 max-h-[500px] overflow-y-auto">
            <button
              onClick={() => setSelectedNode(null)}
              className="absolute top-3 right-3 text-gray-400 hover:text-gray-600 text-xl leading-none"
            >
              ✕
            </button>

            <div className="mb-3">
              <span
                className="inline-block px-2 py-1 text-xs font-medium rounded"
                style={{
                  backgroundColor: `${selectedComponent.color}20`,
                  color: selectedComponent.color
                }}
              >
                {selectedComponent.category.toUpperCase()}
              </span>
            </div>

            <h3 className="font-bold text-xl mb-2">{selectedComponent.name}</h3>

            {selectedComponent.otpEquivalent && (
              <p className="text-sm text-purple-600 mb-3">
                OTP: {selectedComponent.otpEquivalent}
              </p>
            )}

            <p className="text-sm text-gray-600 mb-4">{selectedComponent.description}</p>

            <div>
              <h4 className="font-semibold text-sm mb-2">Key Features:</h4>
              <ul className="space-y-1.5">
                {selectedComponent.features.map((feature, i) => (
                  <li key={i} className="text-xs flex items-start gap-2">
                    <span className="text-green-500 mt-0.5 font-bold">✓</span>
                    <span>{feature}</span>
                  </li>
                ))}
              </ul>
            </div>

            {selectedComponent.dependencies.length > 0 && (
              <div className="mt-4 pt-4 border-t">
                <h4 className="font-semibold text-sm mb-2">Dependencies:</h4>
                <div className="flex flex-wrap gap-1">
                  {selectedComponent.dependencies.map(depId => {
                    const dep = getComponentById(depId);
                    return dep ? (
                      <span
                        key={depId}
                        className="px-2 py-1 text-xs rounded"
                        style={{
                          backgroundColor: `${dep.color}20`,
                          color: dep.color,
                          border: `1px solid ${dep.color}40`
                        }}
                      >
                        {dep.name}
                      </span>
                    ) : null;
                  })}
                </div>
              </div>
            )}

            {selectedComponent.documentation && (
              <a
                href={selectedComponent.documentation}
                target="_blank"
                rel="noopener noreferrer"
                className="mt-4 block text-sm text-blue-600 hover:text-blue-800"
              >
                View Documentation →
              </a>
            )}
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
          fitViewOptions={{ padding: 0.2 }}
          minZoom={0.3}
          maxZoom={1.5}
        >
          <Background color="#94a3b8" gap={16} />
          <Controls />
          <MiniMap
            nodeColor={(node) => {
              const component = getComponentById(node.id);
              return component?.color || '#94a3b8';
            }}
            maskColor="rgba(0, 0, 0, 0.1)"
          />
        </ReactFlow>
      </div>

      {/* Architecture Layers Legend */}
      <div className="mt-6 grid grid-cols-1 md:grid-cols-5 gap-4">
        {ARCHITECTURE_LAYERS.map((layer, index) => {
          const layerComponents = visibleComponents.filter(c =>
            c.layer === index + 1
          );
          if (layerComponents.length === 0) return null;

          return (
            <div key={layer.id} className="p-4 bg-gray-50 rounded-lg border">
              <h4 className="font-semibold text-sm mb-2 flex items-center gap-2">
                <span className="w-6 h-6 rounded-full bg-blue-500 text-white flex items-center justify-center text-xs">
                  {index + 1}
                </span>
                {layer.name}
              </h4>
              <p className="text-xs text-gray-600 mb-2">{layer.description}</p>
              <div className="text-xs text-gray-500">
                {layerComponents.length} component{layerComponents.length !== 1 ? 's' : ''}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
