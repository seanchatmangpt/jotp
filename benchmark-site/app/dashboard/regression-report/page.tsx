'use client';

import { Card, Heading, Text, Flex, Grid, Box } from '@radix-ui/themes';

export default function RegressionReportPage() {
  return (
    <Flex direction="column" gap="6">
      <Box>
        <Heading size="8">
          Regression Report
        </Heading>
        <Text color="gray" mt="2">
          Track performance changes across versions and detect regressions
        </Text>
      </Box>

      <Grid columns={{ initial: '1', md: '2' }} gap="6">
        <Card>
          <Flex direction="column" gap="4" p="6">
            <Heading size="5">Regression Detection</Heading>
            <Flex align="center" justify="center" py="8" direction="column" gap="4">
              <svg width={48} height={48} fill="none" stroke="currentColor" viewBox="0 0 24 24" style={{ color: 'var(--gray-8)' }}>
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
              </svg>
              <Text color="gray">Automatically detect performance regressions</Text>
              <Text size="2" color="gray">Compare benchmark results across commits</Text>
            </Flex>
          </Flex>
        </Card>

        <Card>
          <Flex direction="column" gap="4" p="6">
            <Heading size="5">Trend Analysis</Heading>
            <Flex align="center" justify="center" py="8" direction="column" gap="4">
              <svg width={48} height={48} fill="none" stroke="currentColor" viewBox="0 0 24 24" style={{ color: 'var(--gray-8)' }}>
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
              </svg>
              <Text color="gray">Track performance trends over time</Text>
              <Text size="2" color="gray">Visualize improvements and degradations</Text>
            </Flex>
          </Flex>
        </Card>
      </Grid>

      <Card>
        <Flex direction="column" gap="4" p="6">
          <Heading size="5">Recent Regressions</Heading>
          <Flex align="center" justify="center" py="12">
            <Text color="gray">No regressions detected in the current period</Text>
            <Text size="2" color="gray" mt="2">All benchmarks passing within acceptable thresholds</Text>
          </Flex>
        </Flex>
      </Card>
    </Flex>
  );
}
