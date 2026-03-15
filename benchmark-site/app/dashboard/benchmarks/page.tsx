'use client';

import { Card, Heading, Text, Flex, Grid, Button, Box } from '@radix-ui/themes';
import Link from 'next/link';

export default function BenchmarksPage() {
  return (
    <Flex direction="column" gap="6">
      <Box>
        <Heading size="8">Benchmarks</Heading>
        <Text color="gray" mt="2">
          Comprehensive performance benchmarks and comparisons
        </Text>
      </Box>

      <Grid columns={{ initial: '1', md: '3' }} gap="6">
        <Card style={{ cursor: 'pointer', transition: 'box-shadow 0.2s' }}>
          <Flex direction="column" gap="4" p="4">
            <Flex gap="3" align="center">
              <Box
                p="2"
                style={{
                  backgroundColor: 'var(--blue-3)',
                  borderRadius: '0.5rem',
                }}
              >
                <svg width={24} height={24} fill="none" stroke="currentColor" viewBox="0 0 24 24" style={{ color: 'var(--blue-9)' }}>
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                </svg>
              </Box>
              <Heading size="5">Throughput Tests</Heading>
            </Flex>
            <Text color="gray" size="2">
              Measure operations per second under various load conditions
            </Text>
          </Flex>
        </Card>

        <Card style={{ cursor: 'pointer', transition: 'box-shadow 0.2s' }}>
          <Flex direction="column" gap="4" p="4">
            <Flex gap="3" align="center">
              <Box
                p="2"
                style={{
                  backgroundColor: 'var(--purple-3)',
                  borderRadius: '0.5rem',
                }}
              >
                <svg width={24} height={24} fill="none" stroke="currentColor" viewBox="0 0 24 24" style={{ color: 'var(--purple-9)' }}>
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </Box>
              <Heading size="5">Latency Tests</Heading>
            </Flex>
            <Text color="gray" size="2">
              Analyze response times and percentiles across scenarios
            </Text>
          </Flex>
        </Card>

        <Card style={{ cursor: 'pointer', transition: 'box-shadow 0.2s' }}>
          <Flex direction="column" gap="4" p="4">
            <Flex gap="3" align="center">
              <Box
                p="2"
                style={{
                  backgroundColor: 'var(--green-3)',
                  borderRadius: '0.5rem',
                }}
              >
                <svg width={24} height={24} fill="none" stroke="currentColor" viewBox="0 0 24 24" style={{ color: 'var(--green-9)' }}>
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7" />
                </svg>
              </Box>
              <Heading size="5">Capacity Tests</Heading>
            </Flex>
            <Text color="gray" size="2">
              Determine maximum sustainable load and resource limits
            </Text>
          </Flex>
        </Card>
      </Grid>

      <Card>
        <Flex direction="column" gap="4" p="6">
          <Heading size="5">Recent Benchmark Runs</Heading>
          <Flex direction="column" align="center" py="12" gap="4">
            <Text color="gray">Benchmark results will be displayed here</Text>
            <Button color="blue" mt="2">Run New Benchmark</Button>
          </Flex>
        </Flex>
      </Card>
    </Flex>
  );
}
