'use client';

import React from 'react';
import { Box, Container, Grid, Card, Heading, Text, Flex, Section } from "@radix-ui/themes";
import { FullArchitectureFlow } from '@/components/flows/full-architecture';
import {
  JOTP_COMPONENTS,
  ARCHITECTURE_LAYERS,
  getComponentsByCategory
} from '@/lib/data/jotp-architecture';

export default function ArchitecturePage() {
  const coreCount = getComponentsByCategory('core').length;
  const lifecycleCount = getComponentsByCategory('lifecycle').length;
  const enterpriseCount = getComponentsByCategory('enterprise').length;
  const observabilityCount = getComponentsByCategory('observability').length;
  const messagingCount = getComponentsByCategory('messaging').length;

  return (
    <Container size="4" px="4" py="8">
      {/* Header */}
      <Box mb="9">
        <Heading size="8" mb="4" className="bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-transparent">
          JOTP Architecture
        </Heading>
        <Text size="5" color="gray" mb="6">
          Complete visualization of all JOTP primitives and enterprise patterns
        </Text>

        {/* Quick Stats */}
        <Grid columns={{ initial: "2", sm: "5" }} gap="4" mb="8">
          <Card variant="surface" style={{ background: "var(--blue-3)", borderColor: "var(--blue-6)" }}>
            <Flex direction="column" gap="1" p="4">
              <Heading size="8" style={{ color: "var(--blue-9)" }}>{coreCount}</Heading>
              <Text size="2" color="gray">Core Primitives</Text>
            </Flex>
          </Card>
          <Card variant="surface" style={{ background: "var(--purple-3)", borderColor: "var(--purple-6)" }}>
            <Flex direction="column" gap="1" p="4">
              <Heading size="8" style={{ color: "var(--purple-9)" }}>{lifecycleCount}</Heading>
              <Text size="2" color="gray">Lifecycle</Text>
            </Flex>
          </Card>
          <Card variant="surface" style={{ background: "var(--green-3)", borderColor: "var(--green-6)" }}>
            <Flex direction="column" gap="1" p="4">
              <Heading size="8" style={{ color: "var(--green-9)" }}>{enterpriseCount}</Heading>
              <Text size="2" color="gray">Enterprise</Text>
            </Flex>
          </Card>
          <Card variant="surface" style={{ background: "var(--amber-3)", borderColor: "var(--amber-6)" }}>
            <Flex direction="column" gap="1" p="4">
              <Heading size="8" style={{ color: "var(--amber-9)" }}>{observabilityCount}</Heading>
              <Text size="2" color="gray">Observability</Text>
            </Flex>
          </Card>
          <Card variant="surface" style={{ background: "var(--orange-3)", borderColor: "var(--orange-6)" }}>
            <Flex direction="column" gap="1" p="4">
              <Heading size="8" style={{ color: "var(--orange-9)" }}>{messagingCount}</Heading>
              <Text size="2" color="gray">Messaging</Text>
            </Flex>
          </Card>
        </Grid>
      </Box>

      {/* Architecture Diagram */}
      <Section mb="9">
        <Box mb="6">
          <Heading size="7" mb="2">Complete Architecture</Heading>
          <Text color="gray">
            Interactive diagram showing all {JOTP_COMPONENTS.length} JOTP components and their dependencies.
            Click on any component to see detailed information, features, and OTP equivalents.
          </Text>
        </Box>

        <FullArchitectureFlow
          initialCategories={['core', 'lifecycle', 'enterprise', 'observability', 'messaging']}
          searchable={true}
          exportable={true}
        />

        {/* Controls Guide */}
        <Card mt="4" variant="surface">
          <Flex direction="column" gap="4" p="4">
            <Heading size="4">Controls & Shortcuts</Heading>
            <Grid columns={{ initial: "1", sm: "3" }} gap="4">
              <Box>
                <Text size="2" weight="medium" mb="1">Mouse Controls</Text>
                <Flex direction="column" gap="1" asChild>
                  <ul className="text-gray-600 text-sm">
                    <li>• <strong>Pan:</strong> Click and drag background</li>
                    <li>• <strong>Zoom:</strong> Mouse wheel or pinch</li>
                    <li>• <strong>Select:</strong> Click component</li>
                  </ul>
                </Flex>
              </Box>
              <Box>
                <Text size="2" weight="medium" mb="1">Keyboard Shortcuts</Text>
                <Flex direction="column" gap="1" asChild>
                  <ul className="text-gray-600 text-sm">
                    <li>• <strong>/</strong> - Focus search</li>
                    <li>• <strong>R</strong> - Reset view</li>
                    <li>• <strong>Esc</strong> - Clear selection</li>
                  </ul>
                </Flex>
              </Box>
              <Box>
                <Text size="2" weight="medium" mb="1">Features</Text>
                <Flex direction="column" gap="1" asChild>
                  <ul className="text-gray-600 text-sm">
                    <li>• Filter by category</li>
                    <li>• Search components</li>
                    <li>• Export as PNG/SVG</li>
                  </ul>
                </Flex>
              </Box>
            </Grid>
          </Flex>
        </Card>
      </Section>

      {/* Architecture Layers */}
      <Section mb="9">
        <Box mb="6">
          <Heading size="7" mb="2">Architecture Layers</Heading>
          <Text color="gray">
            JOTP is organized in 5 architectural layers, from core primitives to enterprise patterns.
          </Text>
        </Box>

        <Flex direction="column" gap="4">
          {ARCHITECTURE_LAYERS.map((layer, index) => {
            const layerComponents = JOTP_COMPONENTS.filter(c => c.layer === index + 1);

            return (
              <Card key={layer.id} variant="surface">
                <Flex direction="column" gap="4" p="6">
                  <Flex align="start" gap="4">
                    <Box className="flex-shrink-0 w-10 h-10 rounded-full bg-gradient-to-br from-blue-500 to-purple-500 text-white flex items-center justify-center font-bold">
                      {index + 1}
                    </Box>
                    <Box className="flex-1">
                      <Heading size="5" mb="1">{layer.name}</Heading>
                      <Text color="gray">{layer.description}</Text>
                    </Box>
                    <Text size="2" color="gray">
                      {layerComponents.length} components
                    </Text>
                  </Flex>

                  <Grid columns={{ initial: "1", sm: "2", lg: "3" }} gap="3">
                    {layerComponents.map(component => (
                      <Card
                        key={component.id}
                        variant="surface"
                        style={{
                          backgroundColor: `${component.color}08`,
                          borderColor: `${component.color}30`
                        }}
                      >
                        <Flex direction="column" gap="1" p="3">
                          <Flex align="center" gap="2">
                            <Box
                              className="w-2 h-2 rounded-full"
                              style={{ backgroundColor: component.color }}
                            />
                            <Text size="2" weight="medium">{component.name}</Text>
                          </Flex>
                          <Text size="1" color="gray" className="line-clamp-2">
                            {component.description}
                          </Text>
                          {component.otpEquivalent && (
                            <Text size="1" style={{ color: "var(--purple-9)" }}>
                              OTP: {component.otpEquivalent}
                            </Text>
                          )}
                        </Flex>
                      </Card>
                    ))}
                  </Grid>
                </Flex>
              </Card>
            );
          })}
        </Flex>
      </Section>

      {/* Component Categories */}
      <Section mb="9">
        <Box mb="6">
          <Heading size="7" mb="2">Component Categories</Heading>
          <Text color="gray">
            JOTP components are organized by category for better navigation and understanding.
          </Text>
        </Box>

        <Grid columns={{ initial: "1", sm: "2", lg: "3" }} gap="6">
          {/* Core Primitives */}
          <Card variant="surface" style={{ borderLeft: '4px solid #3b82f6' }}>
            <Flex direction="column" gap="3" p="6">
              <Heading size="5">
                <Flex align="center" gap="2">
                  <Box className="w-3 h-3 rounded-full bg-blue-500" />
                  Core Primitives
                </Flex>
              </Heading>
              <Text size="2" color="gray">
                Fundamental OTP primitives implementing Erlang/OTP core functionality
              </Text>
              <Flex direction="column" gap="2" asChild>
                <ul>
                  {getComponentsByCategory('core').map(c => (
                    <li key={c.id}>
                      <Text size="2" weight="medium">{c.name}</Text>
                      <Text size="1" color="gray" ml="2">{c.otpEquivalent}</Text>
                    </li>
                  ))}
                </ul>
              </Flex>
            </Flex>
          </Card>

          {/* Lifecycle Management */}
          <Card variant="surface" style={{ borderLeft: '4px solid #8b5cf6' }}>
            <Flex direction="column" gap="3" p="6">
              <Heading size="5">
                <Flex align="center" gap="2">
                  <Box className="w-3 h-3 rounded-full bg-purple-500" />
                  Lifecycle Management
                </Flex>
              </Heading>
              <Text size="2" color="gray">
                Process supervision, monitoring, and registry services
              </Text>
              <Flex direction="column" gap="2" asChild>
                <ul>
                  {getComponentsByCategory('lifecycle').slice(0, 5).map(c => (
                    <li key={c.id}>
                      <Text size="2" weight="medium">{c.name}</Text>
                      <Text size="1" color="gray" ml="2">{c.otpEquivalent}</Text>
                    </li>
                  ))}
                  {getComponentsByCategory('lifecycle').length > 5 && (
                    <li>
                      <Text size="1" color="gray">
                        +{getComponentsByCategory('lifecycle').length - 5} more
                      </Text>
                    </li>
                  )}
                </ul>
              </Flex>
            </Flex>
          </Card>

          {/* Enterprise Patterns */}
          <Card variant="surface" style={{ borderLeft: '4px solid #22c55e' }}>
            <Flex direction="column" gap="3" p="6">
              <Heading size="5">
                <Flex align="center" gap="2">
                  <Box className="w-3 h-3 rounded-full bg-green-500" />
                  Enterprise Patterns
                </Flex>
              </Heading>
              <Text size="2" color="gray">
                Enterprise integration patterns for production systems
              </Text>
              <Flex direction="column" gap="2" asChild>
                <ul>
                  {getComponentsByCategory('enterprise').slice(0, 5).map(c => (
                    <li key={c.id}>
                      <Text size="2" weight="medium">{c.name}</Text>
                    </li>
                  ))}
                  {getComponentsByCategory('enterprise').length > 5 && (
                    <li>
                      <Text size="1" color="gray">
                        +{getComponentsByCategory('enterprise').length - 5} more
                      </Text>
                    </li>
                  )}
                </ul>
              </Flex>
            </Flex>
          </Card>

          {/* Observability */}
          <Card variant="surface" style={{ borderLeft: '4px solid #f59e0b' }}>
            <Flex direction="column" gap="3" p="6">
              <Heading size="5">
                <Flex align="center" gap="2">
                  <Box className="w-3 h-3 rounded-full bg-amber-500" />
                  Observability
                </Flex>
              </Heading>
              <Text size="2" color="gray">
                Monitoring, metrics, and distributed tracing
              </Text>
              <Flex direction="column" gap="2" asChild>
                <ul>
                  {getComponentsByCategory('observability').map(c => (
                    <li key={c.id}>
                      <Text size="2" weight="medium">{c.name}</Text>
                    </li>
                  ))}
                </ul>
              </Flex>
            </Flex>
          </Card>

          {/* Messaging Patterns */}
          <Card variant="surface" style={{ borderLeft: '4px solid #f97316' }}>
            <Flex direction="column" gap="3" p="6">
              <Heading size="5">
                <Flex align="center" gap="2">
                  <Box className="w-3 h-3 rounded-full bg-orange-500" />
                  Messaging Patterns
                </Flex>
              </Heading>
              <Text size="2" color="gray">
                Enterprise Integration Patterns (deprecated in favor of reactive)
              </Text>
              <Flex direction="column" gap="2" asChild>
                <ul>
                  {getComponentsByCategory('messaging').map(c => (
                    <li key={c.id}>
                      <Text size="2" weight="medium">{c.name}</Text>
                    </li>
                  ))}
                </ul>
              </Flex>
            </Flex>
          </Card>

          {/* Usage Stats */}
          <Card variant="surface" className="bg-gradient-to-br from-blue-50 to-purple-50" style={{ borderLeft: '4px solid #8b5cf6' }}>
            <Flex direction="column" gap="4" p="6">
              <Heading size="5">Usage Statistics</Heading>
              <Flex direction="column" gap="3">
                <Box>
                  <Flex justify="between" mb="1">
                    <Text size="2">Total Components</Text>
                    <Text size="2" weight="bold">{JOTP_COMPONENTS.length}</Text>
                  </Flex>
                  <Box className="w-full bg-gray-200 rounded-full h-2">
                    <Box className="bg-gradient-to-r from-blue-500 to-purple-500 h-2 rounded-full" style={{ width: '100%' }} />
                  </Box>
                </Box>
                <Box>
                  <Flex justify="between" mb="1">
                    <Text size="2">Core Coverage</Text>
                    <Text size="2" weight="bold">{Math.round((coreCount / 4) * 100)}%</Text>
                  </Flex>
                  <Box className="w-full bg-gray-200 rounded-full h-2">
                    <Box className="bg-blue-500 h-2 rounded-full" style={{ width: `${(coreCount / 4) * 100}%` }} />
                  </Box>
                </Box>
                <Box>
                  <Flex justify="between" mb="1">
                    <Text size="2">Enterprise Coverage</Text>
                    <Text size="2" weight="bold">{Math.round((enterpriseCount / 8) * 100)}%</Text>
                  </Flex>
                  <Box className="w-full bg-gray-200 rounded-full h-2">
                    <Box className="bg-green-500 h-2 rounded-full" style={{ width: `${(enterpriseCount / 8) * 100}%` }} />
                  </Box>
                </Box>
              </Flex>
            </Flex>
          </Card>
        </Grid>
      </Section>

      {/* Technical Details */}
      <Card variant="surface" p="6">
        <Heading size="6" mb="4">Technical Implementation</Heading>
        <Grid columns={{ initial: "1", sm: "2" }} gap="6">
          <Box>
            <Text size="2" weight="medium" mb="2">Technologies</Text>
            <Flex direction="column" gap="1" asChild>
              <ul className="text-sm">
                <li>• <strong>@xyflow/react 12.10.1</strong> - Interactive flow diagrams</li>
                <li>• <strong>React 19</strong> - UI components</li>
                <li>• <strong>Next.js 16</strong> - App router and server components</li>
                <li>• <strong>TypeScript</strong> - Type safety</li>
                <li>• <strong>Tailwind CSS</strong> - Styling</li>
              </ul>
            </Flex>
          </Box>
          <Box>
            <Text size="2" weight="medium" mb="2">Features</Text>
            <Flex direction="column" gap="1" asChild>
              <ul className="text-sm">
                <li>• <strong>Interactive Navigation</strong> - Pan and zoom controls</li>
                <li>• <strong>Smart Filtering</strong> - Filter by category and search</li>
                <li>• <strong>Dependency Tracking</strong> - Visual component relationships</li>
                <li>• <strong>Detail Panels</strong> - Click for component details</li>
                <li>• <strong>Export Support</strong> - PNG and SVG export</li>
                <li>• <strong>Keyboard Shortcuts</strong> - Power user controls</li>
              </ul>
            </Flex>
          </Box>
        </Grid>
      </Card>
    </Container>
  );
}
