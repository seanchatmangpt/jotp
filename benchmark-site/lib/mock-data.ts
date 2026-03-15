import { Benchmark, SystemMetrics } from './types';

export const mockBenchmarks: Benchmark[] = [
  {
    id: 'baseline',
    name: 'Baseline Performance',
    category: 'Core Primitives',
    status: 'completed',
    progress: 100,
    startTime: new Date(Date.now() - 3600000).toISOString(),
    endTime: new Date(Date.now() - 3000000).toISOString(),
    duration: 57,
    metrics: {
      throughput: 425000000,
      latency: { p50: 2.35, p95: 3.5, p99: 5.2, mean: 2.5 },
      memory: { allocated: 5, used: 2 },
      cpu: 45
    }
  },
  {
    id: 'observability',
    name: 'Observability Performance',
    category: 'Framework',
    status: 'completed',
    progress: 100,
    startTime: new Date(Date.now() - 7200000).toISOString(),
    endTime: new Date(Date.now() - 6600000).toISOString(),
    duration: 10,
    metrics: {
      throughput: 16800000,
      latency: { p50: 59.34, p95: 75.2, p99: 95.8, mean: 62.1 },
      memory: { allocated: 10, used: 4 },
      cpu: 65
    }
  },
  {
    id: 'throughput',
    name: 'Throughput Analysis',
    category: 'Stress Testing',
    status: 'running',
    progress: 65,
    startTime: new Date(Date.now() - 300000).toISOString(),
    metrics: {
      throughput: 12500000,
      latency: { p50: 72.5, p95: 95.3, p99: 120.8, mean: 78.2 },
      memory: { allocated: 15, used: 8 },
      cpu: 82
    }
  }
];

export const mockSystemMetrics: SystemMetrics = {
  activeBenchmarks: 1,
  totalBenchmarks: 15,
  systemLoad: 2.5,
  memoryUsage: 65,
  cpuUsage: 78
};

export function generateMockBenchmarkId(): string {
  return `benchmark-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}
