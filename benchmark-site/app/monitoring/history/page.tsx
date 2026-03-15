'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { Box, Container, Flex, Grid, Card, Heading, Text, Button, Badge } from "@radix-ui/themes";
import { Benchmark } from '@/lib/types';

export default function HistoryPage() {
  const [benchmarks, setBenchmarks] = useState<Benchmark[]>([]);
  const [filter, setFilter] = useState<'all' | 'completed' | 'failed'>('all');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch('/api/benchmarks')
      .then(res => res.json())
      .then(data => {
        setBenchmarks(data);
        setLoading(false);
      });
  }, []);

  const filteredBenchmarks = benchmarks.filter(b => {
    if (filter === 'all') return true;
    return b.status === filter;
  });

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleString();
  };

  return (
    <Box asChild minH="100vh" p="8">
      <main>
        <Container size="4">
          <Flex justify="between" align="start" mb="8">
            <Box>
              <Heading size="7" mb="2">Historical Benchmarks</Heading>
              <Text color="gray">
                Browse past benchmark results
              </Text>
            </Box>
            <Link href="/monitoring">
              <Button variant="outline">
                Back to Dashboard
              </Button>
            </Link>
          </Flex>

          <Flex gap="2" mb="6">
            <Button
              variant={filter === 'all' ? 'solid' : 'outline'}
              onClick={() => setFilter('all')}
            >
              All
            </Button>
            <Button
              variant={filter === 'completed' ? 'solid' : 'outline'}
              onClick={() => setFilter('completed')}
            >
              Completed
            </Button>
            <Button
              variant={filter === 'failed' ? 'solid' : 'outline'}
              onClick={() => setFilter('failed')}
            >
              Failed
            </Button>
          </Flex>

          {loading ? (
            <Box className="text-center py-12">
              <Text size="6">Loading...</Text>
            </Box>
          ) : (
            <Flex direction="column" gap="4">
              {filteredBenchmarks.map(benchmark => (
                <Card key={benchmark.id}>
                  <Flex direction="column" gap="4" p="6">
                    <Flex justify="between" align="start">
                      <Box>
                        <Heading size="4">{benchmark.name}</Heading>
                        <Text size="2" color="gray">{benchmark.category}</Text>
                      </Box>
                      <Badge
                        color={benchmark.status === 'completed' ? 'green' : 'red'}
                      >
                        {benchmark.status.toUpperCase()}
                      </Badge>
                    </Flex>

                    <Grid columns={{ initial: "2", sm: "4" }} gap="4">
                      {benchmark.startTime && (
                        <Box>
                          <Text size="2" color="gray">Started</Text>
                          <Text size="2">{formatDate(benchmark.startTime)}</Text>
                        </Box>
                      )}
                      {benchmark.endTime && (
                        <Box>
                          <Text size="2" color="gray">Ended</Text>
                          <Text size="2">{formatDate(benchmark.endTime)}</Text>
                        </Box>
                      )}
                      {benchmark.duration && (
                        <Box>
                          <Text size="2" color="gray">Duration</Text>
                          <Text size="2">{benchmark.duration}s</Text>
                        </Box>
                      )}
                      <Box>
                        <Text size="2" color="gray">Progress</Text>
                        <Text size="2">{benchmark.progress}%</Text>
                      </Box>
                    </Grid>

                    {benchmark.metrics && (
                      <Card variant="surface">
                        <Grid columns={{ initial: "2", sm: "4" }} gap="4" p="4">
                          <Box>
                            <Text size="2" color="gray">Throughput</Text>
                            <Text weight="medium">
                              {benchmark.metrics.throughput.toLocaleString()} ops/s
                            </Text>
                          </Box>
                          <Box>
                            <Text size="2" color="gray">Mean Latency</Text>
                            <Text weight="medium">
                              {benchmark.metrics.latency.mean.toFixed(2)} ns
                            </Text>
                          </Box>
                          <Box>
                            <Text size="2" color="gray">P95 Latency</Text>
                            <Text weight="medium">
                              {benchmark.metrics.latency.p95.toFixed(2)} ns
                            </Text>
                          </Box>
                          <Box>
                            <Text size="2" color="gray">Memory</Text>
                            <Text weight="medium">
                              {benchmark.metrics.memory.used} MB
                            </Text>
                          </Box>
                        </Grid>
                      </Card>
                    )}
                  </Flex>
                </Card>
              ))}
            </Flex>
          )}
        </Container>
      </main>
    </Box>
  );
}
