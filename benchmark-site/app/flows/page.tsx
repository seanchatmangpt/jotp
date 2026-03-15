'use client';

import React from 'react';
import { Box, Container, Flex, Grid, Card, Heading, Text, Section } from "@radix-ui/themes";
import { BenchmarkPipelineFlow, ArchitectureDiagramFlow, PerformanceFlow } from '@/components/flows';

export default function FlowsPage() {
  return (
    <Container size="4" px="4" py="8">
      <Box mb="12">
        <Heading size="8" mb="4">Interactive Flow Visualizations</Heading>
        <Text size="5" color="gray">
          Explore the JOTP benchmark system through interactive diagrams powered by @xyflow/react
        </Text>
      </Box>

      {/* Benchmark Pipeline Flow */}
      <Section mb="16">
        <Box mb="6">
          <Heading size="7" mb="2">Benchmark Pipeline</Heading>
          <Text color="gray">
            Visualize the complete benchmark execution process from configuration to report generation.
            Click "Run Pipeline" to see the animated execution flow.
          </Text>
        </Box>
        <BenchmarkPipelineFlow />

        <Card mt="4" className="bg-blue-50 border-blue-200">
          <Flex direction="column" gap="2" p="4">
            <Heading size="4">Pipeline Stages:</Heading>
            <Flex direction="column" gap="1" asChild>
              <ol className="list-decimal list-inside text-sm">
                <li><strong>Configuration:</strong> Load and validate benchmark settings</li>
                <li><strong>Warm-up:</strong> JVM warm-up and JIT compilation</li>
                <li><strong>Execution:</strong> Run benchmark iterations</li>
                <li><strong>Data Collection:</strong> Gather performance metrics</li>
                <li><strong>Analysis:</strong> Statistical analysis of results</li>
                <li><strong>Report Generation:</strong> Create visualizations and reports</li>
              </ol>
            </Flex>
          </Flex>
        </Card>
      </Section>

      {/* Architecture Diagram */}
      <Section mb="16">
        <Box mb="6">
          <Heading size="7" mb="2">JOTP Framework Architecture</Heading>
          <Text color="gray">
            Interactive diagram showing the core JOTP components and their relationships.
            Click on any component to see detailed information.
          </Text>
        </Box>
        <ArchitectureDiagramFlow />

        <Grid columns={{ initial: "1", sm: "2", lg: "3" }} gap="4" mt="4">
          <Card className="bg-blue-50 border-blue-200">
            <Flex direction="column" gap="2" p="4">
              <Heading size="4">
                <Flex align="center" gap="2">
                  <Text size="6">⚡</Text> Proc
                </Flex>
              </Heading>
              <Text size="2" color="gray">
                Lightweight process with virtual threads and mailbox-based message passing
              </Text>
            </Flex>
          </Card>
          <Card className="bg-purple-50 border-purple-200">
            <Flex direction="column" gap="2" p="4">
              <Heading size="4">
                <Flex align="center" gap="2">
                  <Text size="6">🛡️</Text> Supervisor
                </Flex>
              </Heading>
              <Text size="2" color="gray">
                Fault tolerance tree with multiple restart strategies
              </Text>
            </Flex>
          </Card>
          <Card className="bg-green-50 border-green-200">
            <Flex direction="column" gap="2" p="4">
              <Heading size="4">
                <Flex align="center" gap="2">
                  <Text size="6">🔄</Text> StateMachine
                </Flex>
              </Heading>
              <Text size="2" color="gray">
                Full gen_statem implementation with state transitions and timeouts
              </Text>
            </Flex>
          </Card>
          <Card className="bg-orange-50 border-orange-200">
            <Flex direction="column" gap="2" p="4">
              <Heading size="4">
                <Flex align="center" gap="2">
                  <Text size="6">📡</Text> FrameworkEventBus
                </Flex>
              </Heading>
              <Text size="2" color="gray">
                Event-driven observability and distributed communication
              </Text>
            </Flex>
          </Card>
          <Card className="bg-yellow-50 border-yellow-200">
            <Flex direction="column" gap="2" p="4">
              <Heading size="4">
                <Flex align="center" gap="2">
                  <Text size="6">📋</Text> ProcRegistry
                </Flex>
              </Heading>
              <Text size="2" color="gray">
                Global process name registry with auto-cleanup
              </Text>
            </Flex>
          </Card>
          <Card className="bg-pink-50 border-pink-200">
            <Flex direction="column" gap="2" p="4">
              <Heading size="4">
                <Flex align="center" gap="2">
                  <Text size="6">👁️</Text> ProcMonitor
                </Flex>
              </Heading>
              <Text size="2" color="gray">
                Unilateral process monitoring with DOWN notifications
              </Text>
            </Flex>
          </Card>
        </Grid>
      </Section>

      {/* Performance Flow */}
      <Section mb="16">
        <Box mb="6">
          <Heading size="7" mb="2">Performance Hot Path Analysis</Heading>
          <Text color="gray">
            Detailed timing breakdown showing the critical execution path (green) vs. non-critical operations (gray).
            Understanding the hot path is essential for optimization efforts.
          </Text>
        </Box>
        <PerformanceFlow />

        <Grid columns={{ initial: "1", sm: "2" }} gap="4" mt="4">
          <Card className="bg-green-50 border-green-200">
            <Flex direction="column" gap="3" p="4">
              <Heading size="4">
                <Flex align="center" gap="2">
                  <Box className="w-4 h-4 rounded bg-green-500" />
                  Hot Path (Critical)
                </Flex>
              </Heading>
              <Text size="2" color="gray" mb="2">
                The critical execution path that directly affects system latency. This is where optimization efforts should focus.
              </Text>
              <Flex direction="column" gap="1" asChild>
                <ul className="text-xs">
                  <li>• Message Receive: <strong>0.5ms</strong></li>
                  <li>• State Transition: <strong>1.5ms</strong></li>
                  <li>• Message Send: <strong>0.3ms</strong></li>
                  <li className="font-semibold border-t pt-1 mt-1">
                    Total: <strong>2.3ms</strong>
                  </li>
                </ul>
              </Flex>
            </Flex>
          </Card>
          <Card className="bg-gray-50 border-gray-200">
            <Flex direction="column" gap="3" p="4">
              <Heading size="4">
                <Flex align="center" gap="2">
                  <Box className="w-4 h-4 rounded bg-gray-400" />
                  Cold Path (Non-critical)
                </Flex>
              </Heading>
              <Text size="2" color="gray" mb="2">
                Background operations that don&apos;t affect the critical path. These run asynchronously and can be deferred.
              </Text>
              <Flex direction="column" gap="1" asChild>
                <ul className="text-xs">
                  <li>• Monitoring: <strong>0.7ms</strong></li>
                  <li className="font-semibold border-t pt-1 mt-1">
                    Overhead: <strong>0.7ms</strong>
                  </li>
                </ul>
              </Flex>
            </Flex>
          </Card>
        </Grid>

        <Card mt="4" className="bg-yellow-50 border-yellow-200">
          <Flex direction="column" gap="2" p="4">
            <Heading size="4">💡 Optimization Insights</Heading>
            <Flex direction="column" gap="1" asChild>
              <ul className="text-sm">
                <li>• State Transition is the bottleneck (<strong>65%</strong> of hot path)</li>
                <li>• Pattern matching and state update are the most expensive operations</li>
                <li>• Monitoring overhead is minimal (<strong>23%</strong> of total latency)</li>
                <li>• Focus optimization efforts on the state machine logic</li>
              </ul>
            </Flex>
          </Flex>
        </Card>
      </Section>

      {/* Technical Details */}
      <Card mt="16" p="6">
        <Heading size="6" mb="4">Technical Implementation</Heading>
        <Grid columns={{ initial: "1", sm: "2" }} gap="6">
          <Box>
            <Text size="2" weight="medium" mb="2">Technologies</Text>
            <Flex direction="column" gap="1" asChild>
              <ul className="text-sm">
                <li>• <strong>@xyflow/react 12.10.1</strong> - Interactive flow diagrams</li>
                <li>• <strong>React 19</strong> - UI components</li>
                <li>• <strong>Next.js 16</strong> - App router and server components</li>
                <li>• <strong>Tailwind CSS</strong> - Styling</li>
                <li>• <strong>TypeScript</strong> - Type safety</li>
              </ul>
            </Flex>
          </Box>
          <Box>
            <Text size="2" weight="medium" mb="2">Features</Text>
            <Flex direction="column" gap="1" asChild>
              <ul className="text-sm">
                <li>• Custom node types with rich data visualization</li>
                <li>• Animated edges showing data flow</li>
                <li>• Interactive click handlers for details</li>
                <li>• Responsive design for all screen sizes</li>
                <li>• Real-time updates and status monitoring</li>
              </ul>
            </Flex>
          </Box>
        </Grid>
        <Box mt="4">
          <a
            href="/benchmark-site/FLOW-VISUALIZATION.md"
            className="text-blue-600 hover:text-blue-800 text-sm font-semibold"
          >
            📄 View Full Documentation →
          </a>
        </Box>
      </Card>
    </Container>
  );
}
