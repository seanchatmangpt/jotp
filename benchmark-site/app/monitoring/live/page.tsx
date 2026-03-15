'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { Box, Container, Flex, Grid, Card, Heading, Text, Button } from "@radix-ui/themes";
import { Benchmark, LogEntry } from '@/lib/types';
import { BenchmarkLog } from '@/components/monitoring/benchmark-log';

export default function LiveMonitoringPage() {
  const [benchmarks, setBenchmarks] = useState<Benchmark[]>([]);
  const [selectedBenchmark, setSelectedBenchmark] = useState<Benchmark | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [liveUpdates, setLiveUpdates] = useState<any>(null);

  useEffect(() => {
    fetch('/api/benchmarks')
      .then(res => res.json())
      .then(data => {
        setBenchmarks(data);
        const running = data.find((b: Benchmark) => b.status === 'running');
        if (running) {
          setSelectedBenchmark(running);
          setLogs(running.logs || []);
        }
      });

    // Subscribe to SSE stream
    const eventSource = new EventSource('/api/benchmarks/stream');

    eventSource.onmessage = (event) => {
      const update = JSON.parse(event.data);
      setLiveUpdates(update);

      if (update.type === 'progress' || update.type === 'complete') {
        setBenchmarks(prev => prev.map(b =>
          b.id === update.benchmarkId
            ? { ...b, progress: update.data.progress, metrics: update.data.metrics }
            : b
        ));
      }
    };

    return () => eventSource.close();
  }, []);

  return (
    <Box asChild minH="100vh" p="8">
      <main>
        <Container size="4">
          <Flex justify="between" align="start" mb="8">
            <Box>
              <Heading size="7" mb="2">Live Benchmark Execution</Heading>
              <Text color="gray">
                Real-time benchmark execution with live logs
              </Text>
            </Box>
            <Link href="/monitoring">
              <Button variant="outline">
                Back to Dashboard
              </Button>
            </Link>
          </Flex>

          <Grid columns={{ initial: "1", lg: "3" }} gap="6">
            <Box className="lg:col-span-1">
              <Card>
                <Flex direction="column" gap="4" p="6">
                  <Heading size="5">Active Benchmarks</Heading>
                  <Flex direction="column" gap="2">
                    {benchmarks.filter(b => b.status === 'running').map(benchmark => (
                      <button
                        key={benchmark.id}
                        onClick={() => {
                          setSelectedBenchmark(benchmark);
                          setLogs(benchmark.logs || []);
                        }}
                        className={`w-full text-left p-3 rounded-lg transition ${
                          selectedBenchmark?.id === benchmark.id
                            ? 'bg-blue-100 dark:bg-blue-900'
                            : 'bg-gray-100 dark:bg-gray-700 hover:bg-gray-200 dark:hover:bg-gray-600'
                        }`}
                      >
                        <Text weight="medium">{benchmark.name}</Text>
                        <Text size="2" color="gray">
                          Progress: {benchmark.progress}%
                        </Text>
                      </button>
                    ))}
                  </Flex>

                  {liveUpdates && (
                    <Card className="bg-green-100 dark:bg-green-900">
                      <Text size="2" weight="medium">Live Update Received</Text>
                      <Text size="1" color="gray" mt="1">
                        Type: {liveUpdates.type}
                      </Text>
                    </Card>
                  )}
                </Flex>
              </Card>
            </Box>

            <Box className="lg:col-span-2">
              {selectedBenchmark ? (
                <Flex direction="column" gap="6">
                  <Card>
                    <Flex direction="column" gap="4" p="6">
                      <Heading size="5" mb="2">{selectedBenchmark.name}</Heading>
                      <Text color="gray" mb="4">
                        {selectedBenchmark.category}
                      </Text>

                      <Box mb="4">
                        <Flex justify="between" mb="2">
                          <Text size="2">Progress</Text>
                          <Text size="2">{selectedBenchmark.progress}%</Text>
                        </Flex>
                        <Box className="w-full bg-gray-200 rounded-full h-3">
                          <Box
                            className="bg-blue-600 h-3 rounded-full transition-all duration-300"
                            style={{ width: `${selectedBenchmark.progress}%` }}
                          />
                        </Box>
                      </Box>

                      {selectedBenchmark.metrics && (
                        <Grid columns="2" gap="4">
                          <Box>
                            <Text size="2" color="gray">Throughput</Text>
                            <Text weight="medium">
                              {selectedBenchmark.metrics.throughput.toLocaleString()} ops/s
                            </Text>
                          </Box>
                          <Box>
                            <Text size="2" color="gray">Avg Latency</Text>
                            <Text weight="medium">
                              {selectedBenchmark.metrics.latency.mean.toFixed(2)} ns
                            </Text>
                          </Box>
                        </Grid>
                      )}
                    </Flex>
                  </Card>

                  <Box>
                    <Heading size="4" mb="2">Live Logs</Heading>
                    <BenchmarkLog logs={logs} benchmarkId={selectedBenchmark.id} />
                  </Box>
                </Flex>
              ) : (
                <Card>
                  <Flex align="center" justify="center" p="12">
                    <Text color="gray">Select a benchmark to view details</Text>
                  </Flex>
                </Card>
              )}
            </Box>
          </Grid>
        </Container>
      </main>
    </Box>
  );
}
