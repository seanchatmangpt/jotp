/**
 * Benchmark Data Type Definitions
 *
 * Types for processing and displaying JOTP benchmark results
 */

export interface BenchmarkResult {
  id: string
  name: string
  category: BenchmarkCategory
  timestamp: Date
  config: BenchmarkConfig
  metrics: BenchmarkMetrics
  status: 'pass' | 'fail' | 'warning'
  source: string
}

export type BenchmarkCategory =
  | 'baseline'
  | 'throughput'
  | 'capacity'
  | 'precision'
  | 'stress'
  | 'observability'
  | 'hot-path'
  | 'architecture'
  | 'memory'
  | 'jit'
  | 'profiling'

export interface BenchmarkConfig {
  jvmVersion: string
  jvmVendor: string
  javaVersion: string
  platform: string
  mode: 'Throughput' | 'SampleTime' | 'AverageTime' | 'SingleShot'
  threads: number
  forks: number
  warmupIterations: number
  measurementIterations: number
  [key: string]: string | number | undefined
}

export interface BenchmarkMetrics {
  // Primary score
  score?: number
  scoreUnit?: string
  scoreError?: number
  scoreConfidence?: [number, number]

  // Throughput metrics
  throughput?: number // ops/sec
  throughputMin?: number
  throughputMax?: number

  // Latency metrics (nanoseconds)
  latency?: number
  p50?: number
  p90?: number
  p95?: number
  p99?: number
  p999?: number
  min?: number
  max?: number

  // Resource metrics
  cpu?: number // percent
  cpuOverhead?: number // percent
  memory?: number // bytes
  memoryOverhead?: number // bytes

  // Percentiles object from JMH
  scorePercentiles?: Record<string, number>

  // Raw data
  rawData?: number[][]

  // Custom metrics
  [key: string]: number | string | number[] | number[][] | Record<string, number> | undefined
}

export interface BenchmarkSummary {
  total: number
  passed: number
  failed: number
  warnings: number
  categories: Record<BenchmarkCategory, CategorySummary>
  lastUpdated: Date
}

export interface CategorySummary {
  total: number
  passed: number
  failed: number
  warnings: number
  avgScore?: number
  bestScore?: number
  worstScore?: number
}

export interface TimeSeriesDataPoint {
  timestamp: Date
  value: number
  benchmarkId: string
  benchmarkName: string
}

export interface ComparisonResult {
  benchmark: string
  baseline: number
  current: number
  delta: number
  deltaPercent: number
  improved: boolean
}

export interface SLACompliance {
  metric: string
  target: number
  actual: number
  status: 'pass' | 'fail'
  variance: number
  variancePercent: number
}

export interface CapacityPlanningProfile {
  name: string
  targetThroughput: number
  actualThroughput: number
  cpuOverhead: number
  cpuTarget: number
  p99Latency: number
  p99Target: number
  memoryOverhead: number
  memoryTarget: number
  status: 'pass' | 'fail'
  violations: string[]
}

export interface HotPathValidation {
  operation: string
  baseline: number
  withObservability: number
  overhead: number
  overheadPercent: number
  threshold: number
  status: 'pass' | 'fail'
}

export interface ThroughputScaling {
  subscribers: number
  throughput: number
  stdDev: number
  min: number
  max: number
  efficiency: number // percent of baseline
}

export interface MemoryAllocation {
  component: string
  bytes: number
  kilobytes: number
  megabytes: number
  percentOfTotal: number
}

export interface JITOptimization {
  phase: string
  duration: number
  optimizations: string[]
  bytecodeSize: number
  nativeCodeSize: number
  speedup: number
}

/**
 * Parsed JMH JSON result structure
 */
export interface JMHResult {
  benchmark: string
  mode: string
  threads: number
  forks: number
  warmupIterations: number
  measurementIterations: number
  primaryMetric: {
    score: number
    scoreError: number
    scoreConfidence: [number, number]
    scorePercentiles: Record<string, number>
    scoreUnit: string
    rawData?: number[][]
    min: number
    max: number
  }
  vmVersion: string
  vmVendor: string
  jdkJ9?: string
}

/**
 * Parsed markdown benchmark result
 */
export interface MarkdownBenchmark {
  title: string
  date: Date
  category: BenchmarkCategory
  sections: BenchmarkSection[]
  metadata: Record<string, string | number>
}

export interface BenchmarkSection {
  heading: string
  content: string
  metrics?: BenchmarkMetrics
  tables?: BenchmarkTable[]
}

export interface BenchmarkTable {
  headers: string[]
  rows: string[][]
  caption?: string
}
