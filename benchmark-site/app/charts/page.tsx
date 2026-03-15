'use client';

import React from 'react';
import Link from 'next/link';
import { Box, Container, Flex, Grid, Card, Heading, Text, Button, Section } from "@radix-ui/themes";
import {
  ThroughputChart,
  LatencyChart,
  TimeseriesChart,
  ComparisonChart,
  SparklineCard,
  generateSystemComparison,
} from '@/components/charts';
import { generateTimeSeriesData } from '@/lib/chart-utils';

export default function ChartsPage() {
  // Demo data
  const throughputDemo = [
    { name: 'Baseline', disabled: 85000, enabled: 92000 },
    { name: 'Optimized', disabled: 95000, enabled: 98000 }
  ];

  const latencyDemo = [
    { name: 'JOTP', p50: 2.1, p95: 5.8, p99: 8.2, p999: 12.1 },
    { name: 'Akka', p50: 2.5, p95: 6.2, p99: 9.1, p999: 13.5 }
  ];

  const comparisonDemo = generateSystemComparison(
    ['Throughput', 'Latency', 'CPU Efficiency', 'Memory', 'Fault Tolerance'],
    [
      { name: 'JOTP', values: [95, 88, 92, 85, 98] },
      { name: 'Akka', values: [82, 90, 78, 88, 85] }
    ]
  );

  const timeseriesDemo = [
    { timestamp: '2024-01-01T00:00:00Z', throughput: 85000, latency: 2.5 },
    { timestamp: '2024-01-01T01:00:00Z', throughput: 92000, latency: 2.1 },
    { timestamp: '2024-01-01T02:00:00Z', throughput: 88000, latency: 2.8 },
    { timestamp: '2024-01-01T03:00:00Z', throughput: 95000, latency: 1.9 },
    { timestamp: '2024-01-01T04:00:00Z', throughput: 87000, latency: 2.3 }
  ];

  const sparklineDemo = generateTimeSeriesData(95000, 5000, 20, 'up').map((point) => ({
    timestamp: point.timestamp,
    value: typeof point.throughput === 'number' ? point.throughput :
           typeof point.value === 'number' ? point.value :
           0
  }));

  const latencySparkline = generateTimeSeriesData(2.1, 0.5, 20, 'down').map((point) => ({
    timestamp: point.timestamp,
    value: typeof point.latency === 'number' ? point.latency :
           typeof point.value === 'number' ? point.value :
           0
  }));

  const cpuSparkline = generateTimeSeriesData(45, 10, 20, 'stable').map((point) => ({
    timestamp: point.timestamp,
    value: typeof point.cpu === 'number' ? point.cpu :
           typeof point.value === 'number' ? point.value :
           0
  }));

  return (
    <Box minH="100vh" className="bg-gradient-to-br from-blue-50 to-indigo-100">
      {/* Header */}
      <Box className="bg-white shadow-sm">
        <Container size="4">
          <Flex justify="between" align="center" py="6">
            <Box>
              <Heading size="7" className="text-gray-900">
                JOTP Chart Library
              </Heading>
              <Text color="gray" mt="2">
                Interactive performance visualization components
              </Text>
            </Box>
            <Link href="/charts/examples">
              <Button className="bg-blue-600 text-white hover:bg-blue-700">
                View Examples
              </Button>
            </Link>
          </Flex>
        </Container>
      </Box>

      {/* Main Content */}
      <Container size="4" py="12">
        {/* Overview Section */}
        <Section mb="9" className="text-center">
          <Heading size="7" className="text-gray-900" mb="4">
            Visualize Performance Metrics
          </Heading>
          <Text size="5" color="gray" className="max-w-2xl mx-auto">
            A comprehensive chart library built on Recharts for displaying JOTP benchmark results,
            system comparisons, and real-time performance data.
          </Text>
        </Section>

        {/* Features Grid */}
        <Section mb="9">
          <Heading size="6" className="text-gray-800" mb="8" style={{ textAlign: 'center' }}>
            Chart Components
          </Heading>
          <Grid columns={{ initial: "1", sm: "2", lg: "3" }} gap="6">
            <Card>
              <Flex direction="column" gap="4" p="6">
                <Box className="w-12 h-12 bg-blue-100 rounded-lg flex items-center justify-center">
                  <svg className="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                  </svg>
                </Box>
                <Heading size="4" className="text-gray-900">Throughput Chart</Heading>
                <Text color="gray" size="2">
                  Bar chart comparing operations per second across configurations with target lines.
                </Text>
              </Flex>
            </Card>

            <Card>
              <Flex direction="column" gap="4" p="6">
                <Box className="w-12 h-12 bg-green-100 rounded-lg flex items-center justify-center">
                  <svg className="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                  </svg>
                </Box>
                <Heading size="4" className="text-gray-900">Latency Chart</Heading>
                <Text color="gray" size="2">
                  Visualize P50, P95, P99, and P999 percentiles with SLA threshold indicators.
                </Text>
              </Flex>
            </Card>

            <Card>
              <Flex direction="column" gap="4" p="6">
                <Box className="w-12 h-12 bg-purple-100 rounded-lg flex items-center justify-center">
                  <svg className="w-6 h-6 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                  </svg>
                </Box>
                <Heading size="4" className="text-gray-900">Capacity Chart</Heading>
                <Text color="gray" size="2">
                  Multi-line chart comparing CPU, memory, and throughput across instance profiles.
                </Text>
              </Flex>
            </Card>

            <Card>
              <Flex direction="column" gap="4" p="6">
                <Box className="w-12 h-12 bg-yellow-100 rounded-lg flex items-center justify-center">
                  <svg className="w-6 h-6 text-yellow-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                </Box>
                <Heading size="4" className="text-gray-900">Timeseries Chart</Heading>
                <Text color="gray" size="2">
                  Line or area charts for performance trends with metric toggling.
                </Text>
              </Flex>
            </Card>

            <Card>
              <Flex direction="column" gap="4" p="6">
                <Box className="w-12 h-12 bg-red-100 rounded-lg flex items-center justify-center">
                  <svg className="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 3.055A9.001 9.001 0 1020.945 13H11V3.055z" />
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20.488 9H15V3.512A9.025 9.025 0 0120.488 9z" />
                  </svg>
                </Box>
                <Heading size="4" className="text-gray-900">Comparison Chart</Heading>
                <Text color="gray" size="2">
                  Radar charts for multi-dimensional system comparisons on normalized scales.
                </Text>
              </Flex>
            </Card>

            <Card>
              <Flex direction="column" gap="4" p="6">
                <Box className="w-12 h-12 bg-indigo-100 rounded-lg flex items-center justify-center">
                  <svg className="w-6 h-6 text-indigo-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 12l3-3 3 3 4-4M8 21l4-4 4 4M3 4h18M4 4h16v12a1 1 0 01-1 1H5a1 1 0 01-1-1V4z" />
                  </svg>
                </Box>
                <Heading size="4" className="text-gray-900">Sparkline Cards</Heading>
                <Text color="gray" size="2">
                  Mini trend charts for dashboard cards with performance indicators.
                </Text>
              </Flex>
            </Card>
          </Grid>
        </Section>

        {/* Live Demos */}
        <Section mb="9">
          <Heading size="6" className="text-gray-800" mb="8" style={{ textAlign: 'center' }}>
            Interactive Demos
          </Heading>

          {/* Sparkline Demo */}
          <Grid columns={{ initial: "1", sm: "3" }} gap="6" mb="8">
            <SparklineCard
              title="Throughput"
              value="95,234 ops/s"
              change={5.2}
              data={sparklineDemo}
              type="area"
              color="#3b82f6"
            />
            <SparklineCard
              title="Latency"
              value="2.1ms"
              change={-3.1}
              data={latencySparkline}
              type="area"
              color="#10b981"
            />
            <SparklineCard
              title="CPU Usage"
              value="45%"
              change={0.8}
              data={cpuSparkline}
              type="line"
              color="#f59e0b"
            />
          </Grid>

          {/* Chart Demos */}
          <Grid columns={{ initial: "1", lg: "2" }} gap="8">
            <Card>
              <Flex direction="column" gap="4" p="6">
                <ThroughputChart
                  data={throughputDemo}
                  title="Throughput Comparison"
                  height={250}
                />
              </Flex>
            </Card>

            <Card>
              <Flex direction="column" gap="4" p="6">
                <LatencyChart
                  data={latencyDemo}
                  title="Latency Distribution"
                  height={250}
                />
              </Flex>
            </Card>

            <Card>
              <Flex direction="column" gap="4" p="6">
                <TimeseriesChart
                  data={timeseriesDemo}
                  metrics={['throughput', 'latency']}
                  title="Performance Over Time"
                  height={250}
                />
              </Flex>
            </Card>

            <Card>
              <Flex direction="column" gap="4" p="6">
                <ComparisonChart
                  data={comparisonDemo}
                  systems={['JOTP', 'Akka']}
                  title="System Comparison"
                  height={250}
                />
              </Flex>
            </Card>
          </Grid>
        </Section>

        {/* Quick Start */}
        <Card className="shadow-lg" mb="9">
          <Flex direction="column" gap="6" p="8">
            <Heading size="6" className="text-gray-800">Quick Start</Heading>
            <Box className="bg-gray-900 rounded-lg p-6 overflow-x-auto">
              <pre className="text-sm text-gray-100">
{`// 1. Install dependencies
npm install recharts

// 2. Import components
import { ThroughputChart, SparklineCard } from '@/components/charts';

// 3. Use in your app
<ThroughputChart
  data={[
    { name: 'Config A', disabled: 85000, enabled: 92000 },
    { name: 'Config B', disabled: 95000, enabled: 98000 }
  ]}
  title="Throughput Comparison"
  height={300}
/>`}
              </pre>
            </Box>
          </Flex>
        </Card>

        {/* Documentation Links */}
        <Section className="text-center">
          <Flex direction={{ initial: "column", sm: "row" }} gap="4" justify="center">
            <a
              href="/benchmark-site/CHART-LIBRARY.md"
              className="inline-flex items-center justify-center px-6 py-3 border border-transparent text-base font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700"
            >
              Full Documentation
            </a>
            <Link
              href="/charts/examples"
              className="inline-flex items-center justify-center px-6 py-3 border border-gray-300 text-base font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50"
            >
              View All Examples
            </Link>
          </Flex>
        </Section>
      </Container>

      {/* Footer */}
      <Box className="bg-white border-t border-gray-200 mt-16">
        <Container size="4">
          <Box py="8" className="text-center">
            <Text color="gray">JOTP Chart Library - Built with Recharts and Next.js 15</Text>
          </Box>
        </Container>
      </Box>
    </Box>
  );
}
