'use client';

import { Card, Heading, Text, Flex, Grid, Box } from '@radix-ui/themes';

export default function AnalysisPage() {
  return (
    <Flex direction="column" gap="6">
      <Box>
        <Heading size="8">
          Performance Analysis
        </Heading>
        <Text color="gray" mt="2">
          Deep dive into performance characteristics and bottlenecks
        </Text>
      </Box>

      <Grid columns={{ initial: '1', md: '2' }} gap="6">
        <Card style={{ cursor: 'pointer' }} className="hover:shadow-md transition-shadow">
          <Flex direction="column" gap="4" p="4">
            <Flex gap="3" align="center">
              <Box
                p="2"
                style={{
                  backgroundColor: 'var(--red-3)',
                  borderRadius: '0.5rem',
                }}
              >
                <svg width={24} height={24} fill="none" stroke="currentColor" viewBox="0 0 24 24" style={{ color: 'var(--red-9)' }}>
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
              </Box>
              <Heading size="5">Root Cause Analysis</Heading>
            </Flex>
            <Text color="gray" size="2">
              Investigate performance regressions and identify bottlenecks
            </Text>
          </Flex>
        </Card>

        <Card style={{ cursor: 'pointer' }} className="hover:shadow-md transition-shadow">
          <Flex direction="column" gap="4" p="4">
            <Flex gap="3" align="center">
              <Box
                p="2"
                style={{
                  backgroundColor: 'var(--orange-3)',
                  borderRadius: '0.5rem',
                }}
              >
                <svg width={24} height={24} fill="none" stroke="currentColor" viewBox="0 0 24 24" style={{ color: 'var(--orange-9)' }}>
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                </svg>
              </Box>
              <Heading size="5">Profiling</Heading>
            </Flex>
            <Text color="gray" size="2">
              CPU and memory profiling to optimize hot paths
            </Text>
          </Flex>
        </Card>

        <Card style={{ cursor: 'pointer' }} className="hover:shadow-md transition-shadow">
          <Flex direction="column" gap="4" p="4">
            <Flex gap="3" align="center">
              <Box
                p="2"
                style={{
                  backgroundColor: 'var(--yellow-3)',
                  borderRadius: '0.5rem',
                }}
              >
                <svg width={24} height={24} fill="none" stroke="currentColor" viewBox="0 0 24 24" style={{ color: 'var(--yellow-9)' }}>
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17.657 18.657A8 8 0 016.343 7.343S7 9 9 10c0-2 .5-5 2.986-7C14 5 16.09 5.777 17.656 7.343A7.975 7.975 0 0120 13a7.975 7.975 0 01-2.343 5.657z" />
                </svg>
              </Box>
              <Heading size="5">Flame Graphs</Heading>
            </Flex>
            <Text color="gray" size="2">
              Visualize call stacks and identify CPU time distribution
            </Text>
          </Flex>
        </Card>

        <Card style={{ cursor: 'pointer' }} className="hover:shadow-md transition-shadow">
          <Flex direction="column" gap="4" p="4">
            <Flex gap="3" align="center">
              <Box
                p="2"
                style={{
                  backgroundColor: 'var(--indigo-3)',
                  borderRadius: '0.5rem',
                }}
              >
                <svg width={24} height={24} fill="none" stroke="currentColor" viewBox="0 0 24 24" style={{ color: 'var(--indigo-9)' }}>
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7" />
                </svg>
              </Box>
              <Heading size="5">Memory Analysis</Heading>
            </Flex>
            <Text color="gray" size="2">
              Track heap usage, garbage collection, and allocations
            </Text>
          </Flex>
        </Card>
      </Grid>

      <Card>
        <Flex direction="column" gap="4" p="6">
          <Heading size="5">Analysis Tools</Heading>
          <Flex align="center" justify="center" py="12">
            <Text color="gray">Advanced profiling and analysis tools will be available here</Text>
          </Flex>
        </Flex>
      </Card>
    </Flex>
  );
}
