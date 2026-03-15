/**
 * Benchmark Data Normalizer
 *
 * Normalizes benchmark data to consistent formats and units
 */

import { BenchmarkResult, BenchmarkMetrics } from './types/benchmark'

export class BenchmarkDataNormalizer {
  /**
   * Normalize all benchmark results to consistent format
   */
  static normalizeResults(results: BenchmarkResult[]): BenchmarkResult[] {
    return results.map(result => this.normalizeResult(result))
  }

  /**
   * Normalize a single benchmark result
   */
  static normalizeResult(result: BenchmarkResult): BenchmarkResult {
    return {
      ...result,
      metrics: this.normalizeMetrics(result.metrics, result.category),
      config: this.normalizeConfig(result.config),
    }
  }

  /**
   * Normalize metrics to consistent units and structure
   */
  static normalizeMetrics(metrics: BenchmarkMetrics, category: string): BenchmarkMetrics {
    let normalized: BenchmarkMetrics = { ...metrics }

    // Normalize all latencies to nanoseconds
    if (metrics.latency !== undefined) {
      normalized.latency = this.ensureNanoseconds(metrics.latency, metrics.scoreUnit)
    }

    if (metrics.p50 !== undefined) {
      normalized.p50 = this.ensureNanoseconds(metrics.p50, metrics.scoreUnit)
    }

    if (metrics.p90 !== undefined) {
      normalized.p90 = this.ensureNanoseconds(metrics.p90, metrics.scoreUnit)
    }

    if (metrics.p95 !== undefined) {
      normalized.p95 = this.ensureNanoseconds(metrics.p95, metrics.scoreUnit)
    }

    if (metrics.p99 !== undefined) {
      normalized.p99 = this.ensureNanoseconds(metrics.p99, metrics.scoreUnit)
    }

    if (metrics.p999 !== undefined) {
      normalized.p999 = this.ensureNanoseconds(metrics.p999, metrics.scoreUnit)
    }

    // Normalize throughput to ops/sec
    if (metrics.throughput !== undefined) {
      normalized.throughput = this.ensureOpsPerSec(metrics.throughput, metrics.scoreUnit)
    }

    // Normalize memory to bytes
    if (metrics.memory !== undefined) {
      normalized.memory = this.ensureBytes(metrics.memory)
    }

    if (metrics.memoryOverhead !== undefined) {
      normalized.memoryOverhead = this.ensureBytes(metrics.memoryOverhead)
    }

    // Extract percentiles from scorePercentiles if not already set
    if (metrics.scorePercentiles && !metrics.p50) {
      const percentiles = metrics.scorePercentiles
      normalized.p50 = this.ensureNanoseconds(percentiles['50.0'], metrics.scoreUnit)
      normalized.p90 = this.ensureNanoseconds(percentiles['90.0'], metrics.scoreUnit)
      normalized.p95 = this.ensureNanoseconds(percentiles['95.0'], metrics.scoreUnit)
      normalized.p99 = this.ensureNanoseconds(percentiles['99.0'], metrics.scoreUnit)
      normalized.p999 = this.ensureNanoseconds(percentiles['99.9'], metrics.scoreUnit)
    }

    // Calculate derived metrics
    normalized = this.calculateDerivedMetrics(normalized, category)

    return normalized
  }

  /**
   * Normalize config object
   */
  static normalizeConfig(config: any): any {
    return {
      jvmVersion: config.jvmVersion || '26',
      jvmVendor: config.jvmVendor || 'Oracle Corporation',
      javaVersion: config.javaVersion || '26',
      platform: config.platform || process.platform,
      mode: config.mode || 'Throughput',
      threads: config.threads || 1,
      forks: config.forks || 1,
      warmupIterations: config.warmupIterations || 0,
      measurementIterations: config.measurementIterations || 0,
      ...config,
    }
  }

  /**
   * Calculate derived metrics
   */
  private static calculateDerivedMetrics(metrics: BenchmarkMetrics, category: string): BenchmarkMetrics {
    const derived = { ...metrics }

    // Calculate throughput range if min/max available
    if (metrics.throughputMin !== undefined && metrics.throughputMax !== undefined) {
      derived.throughput = metrics.throughput || (metrics.throughputMin + metrics.throughputMax) / 2
    }

    // Calculate CPU overhead if not present
    if (metrics.cpu !== undefined && metrics.cpuOverhead === undefined) {
      derived.cpuOverhead = metrics.cpu
    }

    // Calculate latency percentiles for display
    if (metrics.p99 !== undefined && metrics.p50 !== undefined) {
      // Tail latency ratio (p99/p50)
      const tailRatio = metrics.p99 / metrics.p50
      ;(derived as any).tailLatencyRatio = tailRatio
    }

    // Calculate confidence interval width
    if (metrics.scoreConfidence) {
      const [lower, upper] = metrics.scoreConfidence
      ;(derived as any).confidenceIntervalWidth = upper - lower
      ;(derived as any).confidenceIntervalPercent = ((upper - lower) / metrics.score!) * 100
    }

    return derived
  }

  /**
   * Ensure value is in nanoseconds
   */
  private static ensureNanoseconds(value: number, unit?: string): number {
    if (!unit) return value

    const unitLower = unit.toLowerCase()

    if (unitLower.includes('ms') || unitLower.includes('milli')) {
      return value * 1_000_000
    }

    if (unitLower.includes('µs') || unitLower.includes('micro') || unitLower.includes('us')) {
      return value * 1_000
    }

    if (unitLower.includes('s') && !unitLower.includes('ns')) {
      return value * 1_000_000_000
    }

    return value
  }

  /**
   * Ensure value is in ops/sec
   */
  private static ensureOpsPerSec(value: number, unit?: string): number {
    if (!unit) return value

    const unitLower = unit.toLowerCase()

    if (unitLower.includes('/ms') || unitLower.includes('permillisecond')) {
      return value * 1_000
    }

    if (unitLower.includes('/min') || unitLower.includes('perminute')) {
      return value / 60
    }

    if (unitLower.includes('/hr') || unitLower.includes('perhour')) {
      return value / 3600
    }

    return value
  }

  /**
   * Ensure value is in bytes
   */
  private static ensureBytes(value: number): number {
    // If value is already large, assume it's in bytes
    if (value > 1_000_000_000) return value

    // If value is small and looks like MB, convert
    if (value < 10_000) {
      return value * 1024 * 1024
    }

    return value
  }

  /**
   * Normalize timestamps to ISO format
   */
  static normalizeTimestamp(date: Date | string | number): string {
    const d = typeof date === 'string' || typeof date === 'number' ? new Date(date) : date
    return d.toISOString()
  }

  /**
   * Round values to appropriate precision
   */
  static roundValue(value: number, precision: number = 2): number {
    return Math.round(value * Math.pow(10, precision)) / Math.pow(10, precision)
  }

  /**
   * Format latency for display
   */
  static formatLatency(nanoseconds: number): string {
    if (nanoseconds >= 1_000_000) {
      return `${this.roundValue(nanoseconds / 1_000_000)}ms`
    }

    if (nanoseconds >= 1_000) {
      return `${this.roundValue(nanoseconds / 1_000)}µs`
    }

    return `${this.roundValue(nanoseconds)}ns`
  }

  /**
   * Format throughput for display
   */
  static formatThroughput(opsPerSec: number): string {
    if (opsPerSec >= 1_000_000_000) {
      return `${this.roundValue(opsPerSec / 1_000_000_000)}B ops/sec`
    }

    if (opsPerSec >= 1_000_000) {
      return `${this.roundValue(opsPerSec / 1_000_000)}M ops/sec`
    }

    if (opsPerSec >= 1_000) {
      return `${this.roundValue(opsPerSec / 1_000)}K ops/sec`
    }

    return `${this.roundValue(opsPerSec)} ops/sec`
  }

  /**
   * Format memory for display
   */
  static formatMemory(bytes: number): string {
    if (bytes >= 1_073_741_824) {
      return `${this.roundValue(bytes / 1_073_741_824)}GB`
    }

    if (bytes >= 1_048_576) {
      return `${this.roundValue(bytes / 1_048_576)}MB`
    }

    if (bytes >= 1024) {
      return `${this.roundValue(bytes / 1024)}KB`
    }

    return `${bytes}B`
  }
}
