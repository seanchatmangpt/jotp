'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { Box, Container, Flex, Grid, Card, Heading, Text, Button } from "@radix-ui/themes";
import { Benchmark, SystemMetrics } from '@/lib/types';
import { StatusDashboard } from '@/components/monitoring/status-dashboard';
import { LiveCard } from '@/components/monitoring/live-card';

export default function MonitoringDashboard() {
  const [benchmarks, setBenchmarks] = useState<Benchmark[]>([]);
  const [metrics, setMetrics] = useState<SystemMetrics | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 5000);
    return () => clearInterval(interval);
  }, []);

  async function fetchData() {
    try {
      const [benchmarksRes, metricsRes] = await Promise.all([
        fetch('/api/benchmarks'),
        fetch('/api/metrics')
      ]);

      const benchmarksData = await benchmarksRes.json();
      const metricsData = await metricsRes.json();

      setBenchmarks(benchmarksData);
      setMetrics(metricsData);
      setLoading(false);
    } catch (error) {
      console.error('Failed to fetch data:', error);
      setLoading(false);
    }
  }

  if (loading) {
    return (
      <Box minH="100vh" className="flex items-center justify-center">
        <Text size="6">Loading...</Text>
      </Box>
    );
  }

  const activeBenchmarks = benchmarks.filter(b => b.status === 'running');
  const completedBenchmarks = benchmarks.filter(b => b.status === 'completed');

  return (
    <Box asChild minH="100vh" p="8">
      <main>
        <Container size="4">
          <Flex justify="between" align="start" mb="8">
            <Box>
              <Heading size="7" mb="2">Monitoring Dashboard</Heading>
              <Text color="gray">
                Real-time benchmark execution and metrics
              </Text>
            </Box>
            <Flex gap="4">
              <Link href="/monitoring/live">
                <Button className="bg-blue-600 text-white hover:bg-blue-700">
                  Live View
                </Button>
              </Link>
              <Link href="/monitoring/history">
                <Button variant="outline">
                  History
                </Button>
              </Link>
            </Flex>
          </Flex>

          {metrics && <StatusDashboard initialMetrics={metrics} activeBenchmarks={activeBenchmarks} />}

          <Box mb="6">
            <Heading size="5" mb="4">Active Benchmarks</Heading>
            {activeBenchmarks.length === 0 ? (
              <Text color="gray" className="text-center py-8">
                No active benchmarks running
              </Text>
            ) : (
              <Grid columns={{ initial: "1", sm: "2" }} gap="4">
                {activeBenchmarks.map(benchmark => (
                  <LiveCard key={benchmark.id} benchmark={benchmark} />
                ))}
              </Grid>
            )}
          </Box>

          <Box>
            <Heading size="5" mb="4">Recent Completions</Heading>
            <Grid columns={{ initial: "1", sm: "2" }} gap="4">
              {completedBenchmarks.slice(0, 4).map(benchmark => (
                <LiveCard key={benchmark.id} benchmark={benchmark} />
              ))}
            </Grid>
          </Box>
        </Container>
      </main>
    </Box>
  );
}
