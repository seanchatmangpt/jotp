'use client';

import React, { useMemo } from 'react';
import { Box, Container, Flex, Grid, Card, Heading, Text, Section } from "@radix-ui/themes";
import {
  ThroughputChart,
  LatencyChart,
  CapacityChart,
  TimeseriesChart,
  ComparisonChart,
  SparklineCard,
  generateSystemComparison,
} from '@/components/charts';
import { generateTimeSeriesData } from '@/lib/chart-utils';

export default function ChartExamplesPage() {
  // Example data
  const throughputData = useMemo(() => [
    {
      name: 'Config A',
      disabled: 85000,
      enabled: 92000,
      withSubscribers: 88000
    },
    {
      name: 'Config B',
      disabled: 95000,
      enabled: 98000,
      withSubscribers: 96000
    },
    {
      name: 'Config C',
      disabled: 75000,
      enabled: 85000,
      withSubscribers: 80000
    }
  ], []);

  const latencyData = useMemo(() => [
    {
      name: 'System A',
      p50: 2.1,
      p95: 5.8,
      p99: 8.2,
      p999: 12.1
    },
    {
      name: 'System B',
      p50: 1.8,
      p95: 4.2,
      p99: 6.1,
      p999: 9.5
    },
    {
      name: 'System C',
      p50: 3.2,
      p95: 7.5,
      p99: 10.8,
      p999: 15.2
    }
  ], []);

  const capacityDatasets = useMemo(() => [
    {
      name: 'Small Instance',
      data: [
        { load: 100, throughput: 50000, cpu: 45, memory: 1024 },
        { load: 500, throughput: 80000, cpu: 78, memory: 2048 },
        { load: 1000, throughput: 95000, cpu: 95, memory: 3072 },
        { load: 2000, throughput: 98000, cpu: 99, memory: 4096 }
      ]
    },
    {
      name: 'Large Instance',
      data: [
        { load: 100, throughput: 60000, cpu: 25, memory: 1024 },
        { load: 500, throughput: 120000, cpu: 42, memory: 2048 },
        { load: 1000, throughput: 180000, cpu: 65, memory: 3072 },
        { load: 2000, throughput: 240000, cpu: 78, memory: 4096 }
      ]
    }
  ], []);

  const timeseriesData = useMemo(() => [
    { timestamp: '2024-01-01T00:00:00Z', throughput: 85000, latency: 2.5, cpu: 45 },
    { timestamp: '2024-01-01T01:00:00Z', throughput: 92000, latency: 2.1, cpu: 52 },
    { timestamp: '2024-01-01T02:00:00Z', throughput: 88000, latency: 2.8, cpu: 48 },
    { timestamp: '2024-01-01T03:00:00Z', throughput: 95000, latency: 1.9, cpu: 58 },
    { timestamp: '2024-01-01T04:00:00Z', throughput: 87000, latency: 2.3, cpu: 50 },
    { timestamp: '2024-01-01T05:00:00Z', throughput: 91000, latency: 2.0, cpu: 54 }
  ], []);

  const comparisonData = useMemo(() =>
    generateSystemComparison(
      ['Throughput', 'Latency', 'CPU Efficiency', 'Memory', 'Fault Tolerance'],
      [
        { name: 'JOTP', values: [95, 88, 92, 85, 98] },
        { name: 'Akka', values: [82, 90, 78, 88, 85] },
        { name: 'Erlang/OTP', values: [88, 95, 80, 90, 95] }
      ]
    ),
  []);

  const sparklineData = useMemo(() =>
    generateTimeSeriesData(95000, 5000, 20, 'up').map((point) => ({
      timestamp: point.timestamp,
      value: typeof point.throughput === 'number' ? point.throughput :
             typeof point.value === 'number' ? point.value :
             0
    })),
  []);

  const latencySparklineData = useMemo(() =>
    generateTimeSeriesData(2.1, 0.5, 20, 'down').map((point) => ({
      timestamp: point.timestamp,
      value: typeof point.latency === 'number' ? point.latency :
             typeof point.value === 'number' ? point.value :
             0
    })),
  []);

  const cpuSparklineData = useMemo(() =>
    generateTimeSeriesData(45, 10, 20, 'stable').map((point) => ({
      timestamp: point.timestamp,
      value: typeof point.cpu === 'number' ? point.cpu :
             typeof point.value === 'number' ? point.value :
             0
    })),
  []);

  return (
    <Box minH="100vh" className="bg-gray-50" py="12" px={{ initial: "4", sm: "6", lg: "8" }}>
      <Container size="4">
        {/* Header */}
        <Box mb="12">
          <Heading size="8" className="text-gray-900" mb="4">
            Chart Component Examples
          </Heading>
          <Text size="5" color="gray">
            Complete examples of all available chart components for the JOTP benchmark site.
          </Text>
        </Box>

        {/* Sparkline Cards */}
        <Section mb="12">
          <Heading size="6" className="text-gray-800" mb="6">Sparkline Cards</Heading>
          <Grid columns={{ initial: "1", sm: "3" }} gap="6">
            <SparklineCard
              title="Current Throughput"
              value="95,234 ops/s"
              change={5.2}
              data={sparklineData}
              type="area"
              color="#3b82f6"
            />
            <SparklineCard
              title="Average Latency"
              value="2.1ms"
              change={-3.1}
              data={latencySparklineData}
              type="area"
              color="#10b981"
            />
            <SparklineCard
              title="CPU Usage"
              value="45%"
              change={0.8}
              data={cpuSparklineData}
              type="line"
              color="#f59e0b"
            />
          </Grid>
        </Section>

        {/* Throughput Chart */}
        <Section mb="12">
          <Heading size="6" className="text-gray-800" mb="6">Throughput Chart</Heading>
          <Card>
            <Flex direction="column" gap="4" p="6">
              <ThroughputChart
                data={throughputData}
                title="Throughput Comparison (ops/sec)"
                showTarget={true}
                targetValue={100000}
                height={300}
              />
            </Flex>
          </Card>
        </Section>

        {/* Latency Chart */}
        <Section mb="12">
          <Heading size="6" className="text-gray-800" mb="6">Latency Chart</Heading>
          <Card>
            <Flex direction="column" gap="4" p="6">
              <LatencyChart
                data={latencyData}
                title="Latency Distribution (ms)"
                showThreshold={true}
                thresholdValue={10}
                height={300}
              />
            </Flex>
          </Card>
        </Section>

        {/* Timeseries Chart */}
        <Section mb="12">
          <Heading size="6" className="text-gray-800" mb="6">Time Series Chart</Heading>
          <Card>
            <Flex direction="column" gap="4" p="6">
              <TimeseriesChart
                data={timeseriesData}
                metrics={['throughput', 'latency', 'cpu']}
                title="Performance Metrics Over Time"
                chartType="line"
                height={300}
              />
            </Flex>
          </Card>
        </Section>

        {/* Comparison Chart */}
        <Section mb="12">
          <Heading size="6" className="text-gray-800" mb="6">System Comparison (Radar)</Heading>
          <Card>
            <Flex direction="column" gap="4" p="6">
              <ComparisonChart
                data={comparisonData}
                systems={['JOTP', 'Akka', 'Erlang/OTP']}
                title="Multi-System Comparison"
                height={400}
              />
            </Flex>
          </Card>
        </Section>

        {/* Capacity Chart */}
        <Section mb="12">
          <Heading size="6" className="text-gray-800" mb="6">Capacity Planning</Heading>
          <Card>
            <Flex direction="column" gap="4" p="6">
              <CapacityChart
                datasets={capacityDatasets}
                title="Instance Capacity Comparison"
                height={400}
              />
            </Flex>
          </Card>
        </Section>

        {/* Code Examples */}
        <Section mb="12">
          <Heading size="6" className="text-gray-800" mb="6">Code Examples</Heading>
          <Flex direction="column" gap="6">
            <Box className="bg-gray-900 rounded-lg p-6 overflow-x-auto">
              <pre className="text-sm text-gray-100">
{`import { ThroughputChart } from '@/components/charts';

<ThroughputChart
  data={[
    { name: 'Config A', disabled: 85000, enabled: 92000 },
    { name: 'Config B', disabled: 95000, enabled: 98000 }
  ]}
  title="Throughput Comparison"
  showTarget={true}
  targetValue={100000}
  height={300}
/>`}
              </pre>
            </Box>

            <Box className="bg-gray-900 rounded-lg p-6 overflow-x-auto">
              <pre className="text-sm text-gray-100">
{`import { ComparisonChart, generateSystemComparison } from '@/components/charts';

<ComparisonChart
  data={generateSystemComparison(
    ['Throughput', 'Latency', 'CPU', 'Memory', 'Fault Tolerance'],
    [
      { name: 'JOTP', values: [95, 88, 92, 85, 98] },
      { name: 'Akka', values: [82, 90, 78, 88, 85] }
    ]
  )}
  systems={['JOTP', 'Akka']}
  title="System Comparison"
  height={400}
/>`}
              </pre>
            </Box>
          </Flex>
        </Section>

        {/* Documentation Link */}
        <Section className="text-center">
          <a
            href="/benchmark-site/CHART-LIBRARY.md"
            className="inline-flex items-center px-6 py-3 border border-transparent text-base font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700"
          >
            View Full Documentation
          </a>
        </Section>
      </Container>
    </Box>
  );
}
