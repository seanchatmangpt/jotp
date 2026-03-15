export interface Benchmark {
  id: string;
  name: string;
  category: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  progress: number;
  startTime?: string;
  endTime?: string;
  duration?: number;
  metrics?: BenchmarkMetrics;
  logs?: LogEntry[];
}

export interface BenchmarkMetrics {
  throughput: number;
  latency: {
    p50: number;
    p95: number;
    p99: number;
    mean: number;
  };
  memory: {
    allocated: number;
    used: number;
  };
  cpu: number;
}

export interface LogEntry {
  timestamp: string;
  level: 'info' | 'warn' | 'error' | 'debug';
  message: string;
  data?: any;
}

export interface BenchmarkUpdate {
  benchmarkId: string;
  type: 'progress' | 'log' | 'complete' | 'error';
  data: any;
}

export interface SystemMetrics {
  activeBenchmarks: number;
  totalBenchmarks: number;
  systemLoad: number;
  memoryUsage: number;
  cpuUsage: number;
}
