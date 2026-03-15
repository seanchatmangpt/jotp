'use client';

import { useState, useEffect, useCallback, useRef } from 'react';
import Link from 'next/link';
import { getComponentById, getDependencies, getDependents, JOTPComponent } from '@/lib/data/jotp-architecture';
import { Badge, Card, Flex, Box, Heading, Text } from '@radix-ui/themes';

interface RelationshipGraphProps {
  componentId: string;
}

export function RelationshipGraph({ componentId }: RelationshipGraphProps) {
  const [component, setComponent] = useState<JOTPComponent | null>(null);
  const [dependencies, setDependencies] = useState<JOTPComponent[]>([]);
  const [dependents, setDependents] = useState<JOTPComponent[]>([]);
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const canvasRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    import('@/lib/data/jotp-architecture').then(() => {
      const comp = getComponentById(componentId);
      const deps = getDependencies(componentId);
      const depsList = getDependents(componentId);

      setComponent(comp || null);
      setDependencies(deps);
      setDependents(depsList);
    });
  }, [componentId]);

  const handleNodeClick = useCallback((nodeId: string) => {
    if (nodeId !== componentId && canvasRef.current) {
      // Smooth scroll to top
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
  }, [componentId]);

  if (!component) {
    return (
      <Flex align="center" justify="center" py="8">
        <Text color="gray">Loading relationship graph...</Text>
      </Flex>
    );
  }

  // Create graph data
  const allRelatedComponents = [
    { type: 'dependency', components: dependencies },
    { type: 'current', components: [component] },
    { type: 'dependent', components: dependents }
  ];

  const hasRelationships = dependencies.length > 0 || dependents.length > 0;

  return (
    <Flex direction="column" gap="6">
      {/* Legend */}
      <Flex gap="4" align="center">
        <Flex gap="2" align="center">
          <Box width="4" height="4" style={{ backgroundColor: 'var(--blue-9)', borderRadius: '0.25rem' }} />
          <Text size="2" color="gray">Current Primitive</Text>
        </Flex>
        <Flex gap="2" align="center">
          <Box width="4" height="4" style={{ backgroundColor: 'var(--purple-9)', borderRadius: '0.25rem' }} />
          <Text size="2" color="gray">Dependencies</Text>
        </Flex>
        <Flex gap="2" align="center">
          <Box width="4" height="4" style={{ backgroundColor: 'var(--green-9)', borderRadius: '0.25rem' }} />
          <Text size="2" color="gray">Dependents</Text>
        </Flex>
      </Flex>

      {/* Graph Visualization */}
      {hasRelationships ? (
        <Box p="8" style={{ backgroundColor: 'var(--gray-3)', borderRadius: '0.5rem', minHeight: '500px', position: 'relative', overflow: 'hidden' }}>
          {/* SVG Connection Lines */}
          <svg style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'none' }}>
            <defs>
              <marker
                id="arrowhead"
                markerWidth="10"
                markerHeight="7"
                refX="9"
                refY="3.5"
                orient="auto"
              >
                <polygon
                  points="0 0, 10 3.5, 0 7"
                  fill="#9ca3af"
                  opacity="0.6"
                />
              </marker>
            </defs>

            {/* Draw connection lines (simplified) */}
            {dependencies.length > 0 && dependents.length > 0 && (
              <>
                {/* Lines from dependencies to current */}
                {dependencies.map((dep, i) => {
                  const startX = 50 + (i * 120);
                  const startY = 100;
                  const endX = 400;
                  const endY = 250;
                  return (
                    <g key={`dep-${dep.id}`}>
                      <line
                        x1={startX + 50}
                        y1={startY + 30}
                        x2={endX - 30}
                        y2={endY - 30}
                        stroke="#a855f7"
                        strokeWidth="2"
                        markerEnd="url(#arrowhead)"
                        opacity="0.6"
                      />
                    </g>
                  );
                })}

                {/* Lines from current to dependents */}
                {dependents.map((dep, i) => {
                  const startX = 400;
                  const startY = 250;
                  const endX = 50 + (i * 120);
                  const endY = 400;
                  return (
                    <g key={`dep-${dep.id}`}>
                      <line
                        x1={startX + 30}
                        y1={startY + 30}
                        x2={endX + 50}
                        y2={endY - 30}
                        stroke="#22c55e"
                        strokeWidth="2"
                        markerEnd="url(#arrowhead)"
                        opacity="0.6"
                      />
                    </g>
                  );
                })}
              </>
            )}
          </svg>

          {/* Dependency Nodes (Top) */}
          {dependencies.length > 0 && (
            <Flex justify="center" gap="4" mb="6">
              {dependencies.map((dep) => (
                <Link
                  key={dep.id}
                  href={`/primitives/${dep.id}`}
                  style={{ position: 'relative', textDecoration: 'none' }}
                  onMouseEnter={() => setHoveredNode(dep.id)}
                  onMouseLeave={() => setHoveredNode(null)}
                  onClick={() => handleNodeClick(dep.id)}
                >
                  <Box
                    width="96px"
                    height="96px"
                    style={{
                      backgroundColor: `${dep.color}20`,
                      borderColor: dep.color,
                      borderRadius: '0.5rem',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      cursor: 'pointer',
                      transition: 'all 0.2s',
                      border: '2px solid',
                    }}
                    className="hover:scale-105 hover:shadow-lg"
                  >
                    <Flex direction="column" align="center" gap="1">
                      <Text style={{ fontSize: '24px' }}>⚡</Text>
                      <Text weight="medium" px="1">
                        {dep.name}
                      </Text>
                    </Flex>
                  </Box>
                  {hoveredNode === dep.id && (
                    <Box
                      style={{
                        position: 'absolute',
                        top: '100%',
                        marginTop: '8px',
                        left: '50%',
                        transform: 'translateX(-50%)',
                        zIndex: 10,
                        width: '192px',
                        backgroundColor: 'var(--color-panel)',
                        borderRadius: '0.5rem',
                        boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
                        border: '1px solid var(--gray-6)',
                        padding: '12px',
                      }}
                    >
                      <Text color="gray">{dep.description}</Text>
                    </Box>
                  )}
                </Link>
              ))}
            </Flex>
          )}

          {/* Current Node (Center) */}
          <Flex justify="center" my="8">
            <Box
              width="128px"
              height="128px"
              style={{
                position: 'relative',
                backgroundColor: `${component.color}30`,
                borderColor: component.color,
                borderRadius: '0.5rem',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                border: '4px solid',
                transition: 'all 0.2s',
              }}
            >
              <Flex direction="column" align="center" gap="2">
                <Text style={{ fontSize: '32px' }}>⚡</Text>
                <Text size="3" weight="bold">
                  {component.name}
                </Text>
              </Flex>
              <Badge
                style={{
                  position: 'absolute',
                  top: '-12px',
                  right: '-12px',
                  backgroundColor: component.color,
                }}
              >
                Current
              </Badge>
            </Box>
          </Flex>

          {/* Dependent Nodes (Bottom) */}
          {dependents.length > 0 && (
            <Flex justify="center" gap="4" mt="6">
              {dependents.map((dep) => (
                <Link
                  key={dep.id}
                  href={`/primitives/${dep.id}`}
                  style={{ position: 'relative', textDecoration: 'none' }}
                  onMouseEnter={() => setHoveredNode(dep.id)}
                  onMouseLeave={() => setHoveredNode(null)}
                  onClick={() => handleNodeClick(dep.id)}
                >
                  <Box
                    width="96px"
                    height="96px"
                    style={{
                      backgroundColor: `${dep.color}20`,
                      borderColor: dep.color,
                      borderRadius: '0.5rem',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      cursor: 'pointer',
                      transition: 'all 0.2s',
                      border: '2px solid',
                    }}
                    className="hover:scale-105 hover:shadow-lg"
                  >
                    <Flex direction="column" align="center" gap="1">
                      <Text style={{ fontSize: '24px' }}>⚡</Text>
                      <Text weight="medium" px="1">
                        {dep.name}
                      </Text>
                    </Flex>
                  </Box>
                  {hoveredNode === dep.id && (
                    <Box
                      style={{
                        position: 'absolute',
                        top: '100%',
                        marginTop: '8px',
                        left: '50%',
                        transform: 'translateX(-50%)',
                        zIndex: 10,
                        width: '192px',
                        backgroundColor: 'var(--color-panel)',
                        borderRadius: '0.5rem',
                        boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
                        border: '1px solid var(--gray-6)',
                        padding: '12px',
                      }}
                    >
                      <Text color="gray">{dep.description}</Text>
                    </Box>
                  )}
                </Link>
              ))}
            </Flex>
          )}

          {/* Empty State Message */}
          {dependencies.length === 0 && (
            <Box
              style={{
                position: 'absolute',
                top: '80px',
                left: '50%',
                transform: 'translateX(-50%)',
              }}
            >
              <Text size="2" color="gray">No dependencies</Text>
            </Box>
          )}

          {dependents.length === 0 && (
            <Box
              style={{
                position: 'absolute',
                bottom: '80px',
                left: '50%',
                transform: 'translateX(-50%)',
              }}
            >
              <Text size="2" color="gray">No dependents</Text>
            </Box>
          )}
        </Box>
      ) : (
        <Box p="12" style={{ backgroundColor: 'var(--gray-3)', borderRadius: '0.5rem', textAlign: 'center' }}>
          <Flex direction="column" align="center" gap="4">
            <Box
              width="64px"
              height="64px"
              style={{
                borderRadius: '50%',
                backgroundColor: 'var(--gray-5)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Text style={{ fontSize: '24px' }}>🔗</Text>
            </Box>
            <Heading size="5">No Relationships Found</Heading>
            <Text color="gray" size="2">
              This primitive has no direct dependencies or dependents. It may be a standalone component
              or relationships haven't been documented yet.
            </Text>
          </Flex>
        </Box>
      )}

      {/* Relationship Summary */}
      <Flex gap="4">
        <Card style={{ flex: 1 }}>
          <Flex direction="column" gap="3" p="4" pb="3">
            <Heading size="3">Dependencies</Heading>
            <Text color="gray">
              Primitives this component depends on
            </Text>
          </Flex>
          <Box p="4" pt="0">
            {dependencies.length > 0 ? (
              <Flex gap="2" wrap="wrap">
                {dependencies.map((dep) => (
                  <Link key={dep.id} href={`/primitives/${dep.id}`} style={{ textDecoration: 'none' }}>
                    <Badge
                      variant="outline"
                      style={{ cursor: 'pointer', borderColor: dep.color }}
                    >
                      {dep.name}
                    </Badge>
                  </Link>
                ))}
              </Flex>
            ) : (
              <Text size="2" color="gray">None</Text>
            )}
          </Box>
        </Card>

        <Card style={{ flex: 1 }}>
          <Flex direction="column" gap="3" p="4" pb="3">
            <Heading size="3">Dependents</Heading>
            <Text color="gray">
              Primitives that depend on this component
            </Text>
          </Flex>
          <Box p="4" pt="0">
            {dependents.length > 0 ? (
              <Flex gap="2" wrap="wrap">
                {dependents.map((dep) => (
                  <Link key={dep.id} href={`/primitives/${dep.id}`} style={{ textDecoration: 'none' }}>
                    <Badge
                      variant="outline"
                      style={{ cursor: 'pointer', borderColor: dep.color }}
                    >
                      {dep.name}
                    </Badge>
                  </Link>
                ))}
              </Flex>
            ) : (
              <Text size="2" color="gray">None</Text>
            )}
          </Box>
        </Card>
      </Flex>

      {/* Instructions */}
      <Box p="4" style={{ backgroundColor: 'var(--blue-3)', borderRadius: '0.5rem', border: '1px solid var(--blue-6)' }}>
        <Text size="2" style={{ color: 'var(--blue-11)' }}>
          <Text weight="bold">Interactive:</Text> Click on any primitive node to navigate to its detail page.
          Hover over nodes to see their descriptions.
        </Text>
      </Box>
    </Flex>
  );
}
