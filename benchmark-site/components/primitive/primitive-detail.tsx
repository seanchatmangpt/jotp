'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { getComponentById, getDependencies, getDependents, JOTPComponent } from '@/lib/data/jotp-architecture';
import { Card, Badge, Tabs, Button, Flex, Box, Heading, Text } from '@radix-ui/themes';
import { ApiReference } from './api-reference';
import { RelationshipGraph } from './relationship-graph';

interface PrimitiveDetailProps {
  componentId: string;
}

export function PrimitiveDetail({ componentId }: PrimitiveDetailProps) {
  const [component, setComponent] = useState<JOTPComponent | null>(null);
  const [dependencies, setDependencies] = useState<JOTPComponent[]>([]);
  const [dependents, setDependents] = useState<JOTPComponent[]>([]);
  const [allComponents, setAllComponents] = useState<JOTPComponent[]>([]);

  useEffect(() => {
    // Load data on client side
    import('@/lib/data/jotp-architecture').then(({ JOTP_COMPONENTS }) => {
      const comp = getComponentById(componentId);
      const deps = getDependencies(componentId);
      const depsList = getDependents(componentId);

      setComponent(comp || null);
      setDependencies(deps);
      setDependents(depsList);
      setAllComponents(JOTP_COMPONENTS);
    });
  }, [componentId]);

  if (!component) {
    return (
      <Flex align="center" justify="center" py="12">
        <Text color="gray">Loading primitive details...</Text>
      </Flex>
    );
  }

  const categoryColors: Record<string, "blue" | "purple" | "green" | "orange" | "amber"> = {
    core: 'blue',
    lifecycle: 'purple',
    enterprise: 'green',
    messaging: 'orange',
    observability: 'amber',
  };

  return (
    <Flex direction="column" gap="6">
      {/* Header Section */}
      <Card>
        <Flex direction="column" gap="4" p="6">
          <Flex justify="between" align="start">
            <Flex direction="column" gap="4" style={{ flex: 1 }}>
              <Flex align="center" gap="3">
                <Heading size="8">{component.name}</Heading>
                <Badge color={categoryColors[component.category]}>{component.category}</Badge>
              </Flex>

              <Text size="4" color="gray">
                {component.description}
              </Text>

              {component.otpEquivalent && (
                <Box px="3" py="1" style={{ backgroundColor: 'var(--gray-4)', borderRadius: '0.5rem', display: 'inline-flex' }}>
                  <Text size="2" color="gray">
                    <Text weight="bold">OTP:</Text> {component.otpEquivalent}
                  </Text>
                </Box>
              )}
            </Flex>

            {/* Quick Actions */}
            <Box>
              <Link href="/primitives">
                <Button variant="outline" size="2">
                  ← Back to Primitives
                </Button>
              </Link>
            </Box>
          </Flex>

          {/* Key Features */}
          <Box>
            <Text size="2" weight="bold" color="gray" mb="3">Key Features</Text>
            <Flex gap="2" wrap="wrap">
              {component.features.map((feature, index) => (
                <Badge key={index} color="gray">
                  {feature}
                </Badge>
              ))}
            </Flex>
          </Box>
        </Flex>
      </Card>

      {/* Main Content with Sidebar */}
      <Flex gap="6">
        {/* Sidebar - Quick Navigation */}
        <Box style={{ flex: '0 0 25%' }}>
          <Card style={{ position: 'sticky', top: '16px' }}>
            <Flex direction="column" gap="4" p="4">
              <Heading size="5">All Primitives</Heading>
              <Text color="gray" size="2">Quick navigation</Text>
              <Box style={{ maxHeight: '600px', overflowY: 'auto' }}>
                <Flex direction="column" gap="1">
                  {allComponents.map((comp) => (
                    <Link
                      key={comp.id}
                      href={`/primitives/${comp.id}`}
                      style={{ textDecoration: 'none' }}
                    >
                      <Flex
                        justify="between"
                        align="center"
                        px="3"
                        py="2"
                        style={{
                          borderRadius: '0.5rem',
                          backgroundColor: comp.id === component.id ? 'var(--blue-4)' : 'transparent',
                          color: comp.id === component.id ? 'var(--blue-11)' : 'inherit',
                          transition: 'background-color 0.2s',
                        }}
                      >
                        <Text truncate>{comp.name}</Text>
                        <Box
                          width="2"
                          height="2"
                          style={{
                            backgroundColor: comp.color,
                            borderRadius: '50%',
                            flexShrink: 0,
                            marginLeft: '8px',
                          }}
                        />
                      </Flex>
                    </Link>
                  ))}
                </Flex>
              </Box>
            </Flex>
          </Card>
        </Box>

        {/* Main Content Area */}
        <Box style={{ flex: 1 }}>
          <Tabs.Root defaultValue="overview">
            <Tabs.List mb="4">
              <Tabs.Trigger value="overview">Overview</Tabs.Trigger>
              <Tabs.Trigger value="api">API</Tabs.Trigger>
              <Tabs.Trigger value="diagram">Diagram</Tabs.Trigger>
              <Tabs.Trigger value="examples">Examples</Tabs.Trigger>
              <Tabs.Trigger value="relationships">Relationships</Tabs.Trigger>
            </Tabs.List>

            {/* Overview Tab */}
            <Tabs.Content value="overview">
              <Flex direction="column" gap="4">
                <Card>
                  <Flex direction="column" gap="4" p="6">
                    <Heading size="5">Overview</Heading>
                    <Text color="gray" size="2">Detailed description and usage</Text>

                    <Box>
                      <Heading size="4" mb="2">Description</Heading>
                      <Text color="gray">{component.description}</Text>
                    </Box>

                    {component.otpEquivalent && (
                      <Box>
                        <Heading size="4" mb="2">OTP Equivalent</Heading>
                        <Text color="gray">
                          This primitive implements the following Erlang/OTP functionality:
                          <Box as="code" ml="2" px="2" py="1" style={{ backgroundColor: 'var(--gray-4)', borderRadius: '0.25rem', fontSize: '14px' }}>
                            {component.otpEquivalent}
                          </Box>
                        </Text>
                      </Box>
                    )}

                    <Box>
                      <Heading size="4" mb="2">Category</Heading>
                      <Badge color={categoryColors[component.category]}>{component.category}</Badge>
                    </Box>

                    <Box>
                      <Heading size="4" mb="2">Key Features</Heading>
                      <Flex direction="column" gap="2">
                        {component.features.map((feature, index) => (
                          <Flex key={index} gap="2" align="start">
                            <Text color="blue">✓</Text>
                            <Text color="gray">{feature}</Text>
                          </Flex>
                        ))}
                      </Flex>
                    </Box>
                  </Flex>
                </Card>

                {/* Dependencies */}
                {dependencies.length > 0 && (
                  <Card>
                    <Flex direction="column" gap="4" p="6">
                      <Heading size="5">Dependencies</Heading>
                      <Text color="gray" size="2">Primitives this component depends on</Text>
                      <Flex gap="2" wrap="wrap">
                        {dependencies.map((dep) => (
                          <Link key={dep.id} href={`/primitives/${dep.id}`} style={{ textDecoration: 'none' }}>
                            <Badge variant="outline" style={{ cursor: 'pointer' }}>
                              {dep.name}
                            </Badge>
                          </Link>
                        ))}
                      </Flex>
                    </Flex>
                  </Card>
                )}

                {/* Dependents */}
                {dependents.length > 0 && (
                  <Card>
                    <Flex direction="column" gap="4" p="6">
                      <Heading size="5">Used By</Heading>
                      <Text color="gray" size="2">Primitives that depend on this component</Text>
                      <Flex gap="2" wrap="wrap">
                        {dependents.map((dep) => (
                          <Link key={dep.id} href={`/primitives/${dep.id}`} style={{ textDecoration: 'none' }}>
                            <Badge variant="outline" style={{ cursor: 'pointer' }}>
                              {dep.name}
                            </Badge>
                          </Link>
                        ))}
                      </Flex>
                    </Flex>
                  </Card>
                )}
              </Flex>
            </Tabs.Content>

            {/* API Tab */}
            <Tabs.Content value="api">
              <ApiReference componentId={component.id} />
            </Tabs.Content>

            {/* Diagram Tab */}
            <Tabs.Content value="diagram">
              <Card>
                <Flex direction="column" gap="4" p="6">
                  <Heading size="5">Interactive Diagram</Heading>
                  <Text color="gray" size="2">Visual representation of the primitive</Text>
                  <Box p="8" style={{ backgroundColor: 'var(--gray-3)', borderRadius: '0.5rem', minHeight: '400px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                    <Flex direction="column" align="center" gap="4">
                      <Box
                        width="96px"
                        height="96px"
                        borderRadius="0.5rem"
                        style={{ backgroundColor: `${component.color}20`, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '48px' }}
                      >
                        ⚡
                      </Box>
                      <Heading size="5">{component.name}</Heading>
                      <Text color="gray" size="2" style={{ maxWidth: '400px', textAlign: 'center' }}>
                        {component.description}
                      </Text>
                      <Flex gap="2" justify="center">
                        {component.features.slice(0, 3).map((feature, index) => (
                          <Badge key={index} color="gray">
                            {feature}
                          </Badge>
                        ))}
                      </Flex>
                    </Flex>
                  </Box>
                  <Text size="2" color="gray" align="center" mt="4">
                    Interactive diagrams coming soon - ReactFlow integration
                  </Text>
                </Flex>
              </Card>
            </Tabs.Content>

            {/* Examples Tab */}
            <Tabs.Content value="examples">
              <Card>
                <Flex direction="column" gap="4" p="6">
                  <Heading size="5">Code Examples</Heading>
                  <Text color="gray" size="2">Java code samples showing how to use this primitive</Text>

                  <Flex direction="column" gap="4">
                    <Box>
                      <Heading size="4" mb="2">Basic Usage</Heading>
                      <Box p="4" style={{ backgroundColor: 'var(--gray-3)', borderRadius: '0.5rem', overflowX: 'auto' }}>
                        <Text size="2" style={{ color: 'var(--green-9)', fontFamily: 'monospace', whiteSpace: 'pre' }}>
                          {`// Example usage of ${component.name}\n// Coming soon...`}
                        </Text>
                      </Box>
                    </Box>

                    <Box>
                      <Heading size="4" mb="2">Advanced Pattern</Heading>
                      <Box p="4" style={{ backgroundColor: 'var(--gray-3)', borderRadius: '0.5rem', overflowX: 'auto' }}>
                        <Text size="2" style={{ color: 'var(--green-9)', fontFamily: 'monospace', whiteSpace: 'pre' }}>
                          {`// Advanced pattern example\n// Coming soon...`}
                        </Text>
                      </Box>
                    </Box>

                    <Box p="4" style={{ backgroundColor: 'var(--blue-3)', borderRadius: '0.5rem', border: '1px solid var(--blue-6)' }}>
                      <Text size="2" style={{ color: 'var(--blue-11)' }}>
                        <Text weight="bold">Note:</Text> More examples will be added as the documentation expands.
                        Check back soon for comprehensive code samples!
                      </Text>
                    </Box>
                  </Flex>
                </Flex>
              </Card>
            </Tabs.Content>

            {/* Relationships Tab */}
            <Tabs.Content value="relationships">
              <Card>
                <Flex direction="column" gap="4" p="6">
                  <Heading size="5">Relationship Graph</Heading>
                  <Text color="gray" size="2">Visual representation of primitive relationships</Text>
                  <RelationshipGraph componentId={component.id} />
                </Flex>
              </Card>
            </Tabs.Content>
          </Tabs.Root>
        </Box>
      </Flex>
    </Flex>
  );
}
