'use client';

import { useEffect, useState } from 'react';
import { Box, Flex, Card, Heading, Text, Grid, Badge, Button, Select, Separator, Progress } from "@radix-ui/themes";
import {
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { BenchmarkCard } from '@/components/ui/benchmark-card';
import {
  AlertTriangle,
  TrendingUp,
  TrendingDown,
  Minus,
  Play,
  Upload,
  FileText,
  Loader2,
} from 'lucide-react';

interface MetricCard {
  title: string;
  value: string;
  change: string;
  changeType: 'increase' | 'decrease' | 'neutral';
  icon: string;
}

interface RecentBenchmark {
  id: string;
  name: string;
  type: string;
  status: 'success' | 'failed' | 'running';
  timestamp: string;
  duration: string;
}

export default function DashboardPage() {
  const [metrics, setMetrics] = useState<MetricCard[]>([]);
  const [recentBenchmarks, setRecentBenchmarks] = useState<RecentBenchmark[]>([]);
  const [alerts, setAlerts] = useState<string[]>([]);

  useEffect(() => {
    // Simulate fetching dashboard data
    setMetrics([
      {
        title: 'Avg Throughput',
        value: '2.4M ops/s',
        change: '+12.5%',
        changeType: 'increase',
        icon: 'M13 7h8m0 0v8m0-8l-8 8-4-4-6 6',
      },
      {
        title: 'Avg Latency',
        value: '1.2ms',
        change: '-8.3%',
        changeType: 'decrease',
        icon: 'M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z',
      },
      {
        title: 'Active Processes',
        value: '1.2M',
        change: 'No change',
        changeType: 'neutral',
        icon: 'M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7',
      },
      {
        title: 'Success Rate',
        value: '99.97%',
        change: '+0.02%',
        changeType: 'increase',
        icon: 'M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z',
      },
    ]);

    setRecentBenchmarks([
      {
        id: '1',
        name: 'Process Spawn Benchmark',
        type: 'Throughput',
        status: 'success',
        timestamp: '2 hours ago',
        duration: '5m 32s',
      },
      {
        id: '2',
        name: 'Message Passing Latency',
        type: 'Latency',
        status: 'success',
        timestamp: '4 hours ago',
        duration: '3m 15s',
      },
      {
        id: '3',
        name: 'Supervisor Tree Stress Test',
        type: 'Capacity',
        status: 'running',
        timestamp: 'Started 30m ago',
        duration: 'Running...',
      },
      {
        id: '4',
        name: 'Memory Leak Detection',
        type: 'Analysis',
        status: 'failed',
        timestamp: '6 hours ago',
        duration: 'Failed after 12m',
      },
      {
        id: '5',
        name: 'Concurrent Process Pool',
        type: 'Throughput',
        status: 'success',
        timestamp: '8 hours ago',
        duration: '7m 45s',
      },
    ]);

    setAlerts([
      'Memory usage spike detected in Process Spawn Benchmark',
      'Latency regression in Message Passing test (vs. baseline)',
      'New baseline available: v1.0.0 -> v1.1.0',
    ]);
  }, []);

  return (
    <Flex direction="column" gap="6">
      {/* Header */}
      <Box>
        <Heading size="7">Dashboard Overview</Heading>
        <Text color="gray" mt="2">
          Real-time performance metrics and benchmark results
        </Text>
      </Box>

      {/* Metrics Grid */}
      <Grid columns={{ initial: "1", sm: "2", lg: "4" }} gap="6">
        {metrics.map((metric, index) => (
          <MetricCardComponent key={index} metric={metric} />
        ))}
      </Grid>

      {/* Alerts Section */}
      {alerts.length > 0 && (
        <Card className="bg-yellow-50 dark:bg-yellow-950/20 border-yellow-200 dark:border-yellow-800">
          <Flex gap="3" align="start" p="4">
            <AlertTriangle className="w-6 h-6 text-yellow-600 dark:text-yellow-500 flex-shrink-0" />
            <Flex direction="column" gap="2">
              <Heading size="4" className="text-yellow-700 dark:text-yellow-400">
                Active Alerts ({alerts.length})
              </Heading>
              {alerts.map((alert, index) => (
                <Text key={index} size="2" className="text-yellow-700 dark:text-yellow-300">
                  - {alert}
                </Text>
              ))}
            </Flex>
          </Flex>
        </Card>
      )}

      {/* Charts Section */}
      <Grid columns={{ initial: "1", lg: "2" }} gap="6">
        {/* Throughput Chart Placeholder */}
        <Card>
          <Flex direction="column" gap="4" p="4">
            <Flex justify="between" align="center">
              <Heading size="5">Throughput Trend</Heading>
              <Select defaultValue="24h">
                <SelectTrigger className="w-[140px]">
                  <SelectValue placeholder="Select period" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="24h">Last 24 hours</SelectItem>
                  <SelectItem value="7d">Last 7 days</SelectItem>
                  <SelectItem value="30d">Last 30 days</SelectItem>
                </SelectContent>
              </Select>
            </Flex>
            <Box className="h-64 flex items-center justify-center bg-muted rounded-lg">
              <Text color="gray" size="2">
                Chart visualization will be rendered here
              </Text>
            </Box>
          </Flex>
        </Card>

        {/* Latency Chart Placeholder */}
        <Card>
          <Flex direction="column" gap="4" p="4">
            <Flex justify="between" align="center">
              <Heading size="5">Latency Distribution</Heading>
              <Select defaultValue="p50">
                <SelectTrigger className="w-[140px]">
                  <SelectValue placeholder="Select percentile" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="p50">P50</SelectItem>
                  <SelectItem value="p95">P95</SelectItem>
                  <SelectItem value="p99">P99</SelectItem>
                </SelectContent>
              </Select>
            </Flex>
            <Box className="h-64 flex items-center justify-center bg-muted rounded-lg">
              <Text color="gray" size="2">
                Histogram visualization will be rendered here
              </Text>
            </Box>
          </Flex>
        </Card>
      </Grid>

      {/* Recent Benchmarks Table */}
      <Card>
        <Flex justify="between" align="center" py="4" px="6" className="border-b">
          <Heading size="5">Recent Benchmarks</Heading>
          <Button variant="ghost" className="text-primary">
            View All -&gt;
          </Button>
        </Flex>
        <Box className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b bg-muted/50">
                <th className="px-6 py-3 text-left text-sm font-medium text-muted-foreground">
                  Benchmark Name
                </th>
                <th className="px-6 py-3 text-left text-sm font-medium text-muted-foreground">
                  Type
                </th>
                <th className="px-6 py-3 text-left text-sm font-medium text-muted-foreground">
                  Status
                </th>
                <th className="px-6 py-3 text-left text-sm font-medium text-muted-foreground">
                  Timestamp
                </th>
                <th className="px-6 py-3 text-left text-sm font-medium text-muted-foreground">
                  Duration
                </th>
                <th className="px-6 py-3 text-left text-sm font-medium text-muted-foreground">
                  Actions
                </th>
              </tr>
            </thead>
            <tbody>
              {recentBenchmarks.map((benchmark) => (
                <tr key={benchmark.id} className="border-b last:border-0">
                  <td className="px-6 py-4">
                    <Text weight="medium">{benchmark.name}</Text>
                  </td>
                  <td className="px-6 py-4">
                    <Badge variant="secondary">{benchmark.type}</Badge>
                  </td>
                  <td className="px-6 py-4">
                    <StatusBadge status={benchmark.status} />
                  </td>
                  <td className="px-6 py-4">
                    <Text color="gray">{benchmark.timestamp}</Text>
                  </td>
                  <td className="px-6 py-4">
                    <Text color="gray">{benchmark.duration}</Text>
                  </td>
                  <td className="px-6 py-4">
                    <Button variant="ghost" size="1" className="text-primary mr-2">
                      View
                    </Button>
                    <Button variant="ghost" size="1">
                      Compare
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Box>
      </Card>

      {/* Quick Actions */}
      <Grid columns={{ initial: "1", sm: "3" }} gap="6">
        <Button size="3" className="h-12">
          <Flex gap="2" align="center" justify="center">
            <Play className="w-5 h-5" />
            <Text weight="medium">Run New Benchmark</Text>
          </Flex>
        </Button>

        <Button size="3" variant="outline" className="h-12">
          <Flex gap="2" align="center" justify="center">
            <Upload className="w-5 h-5" />
            <Text weight="medium">Import Results</Text>
          </Flex>
        </Button>

        <Button size="3" variant="outline" className="h-12">
          <Flex gap="2" align="center" justify="center">
            <FileText className="w-5 h-5" />
            <Text weight="medium">Generate Report</Text>
          </Flex>
        </Button>
      </Grid>
    </Flex>
  );
}

function MetricCardComponent({ metric }: { metric: MetricCard }) {
  const changeColors: Record<string, string> = {
    increase: 'text-green-600 dark:text-green-400',
    decrease: 'text-red-600 dark:text-red-400',
    neutral: 'text-muted-foreground',
  };

  const iconColors: Record<string, string> = {
    increase: 'text-green-600 dark:text-green-400',
    decrease: 'text-red-600 dark:text-red-400',
    neutral: 'text-muted-foreground',
  };

  const ChangeIcon = {
    increase: TrendingUp,
    decrease: TrendingDown,
    neutral: Minus,
  }[metric.changeType];

  return (
    <Card>
      <Flex direction="column" gap="4" p="4">
        <Flex justify="between" align="start">
          <Flex gap="3" align="start">
            <Box className="p-2 bg-blue-100 dark:bg-blue-900/30 rounded-lg">
              <svg
                width={24}
                height={24}
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
                className="text-blue-600 dark:text-blue-400"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d={metric.icon}
                />
              </svg>
            </Box>
            <Box>
              <Text size="2" color="gray">{metric.title}</Text>
              <Text size="6" weight="bold" mt="1">{metric.value}</Text>
            </Box>
          </Flex>
        </Flex>
        <Flex gap="2" align="center" mt="2">
          <ChangeIcon className={`w-4 h-4 ${iconColors[metric.changeType]}`} />
          <Text size="2" weight="medium" className={changeColors[metric.changeType]}>
            {metric.change}
          </Text>
          <Text size="2" color="gray">vs last week</Text>
        </Flex>
      </Flex>
    </Card>
  );
}

function StatusBadge({ status }: { status: 'success' | 'failed' | 'running' }) {
  const variantMap = {
    success: 'default' as const,
    failed: 'destructive' as const,
    running: 'secondary' as const,
  };

  const colorClasses = {
    success: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    failed: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
    running: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400',
  };

  const labels = {
    success: 'Success',
    failed: 'Failed',
    running: 'Running',
  };

  return (
    <Badge variant={variantMap[status]} className={colorClasses[status]}>
      {status === 'running' && (
        <Loader2 className="w-3 h-3 mr-1 animate-spin" />
      )}
      {labels[status]}
    </Badge>
  );
}
