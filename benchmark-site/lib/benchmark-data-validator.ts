/**
 * Benchmark Data Validator
 *
 * Validates benchmark data for integrity and consistency
 */

import { BenchmarkResult, BenchmarkMetrics } from './types/benchmark'

export interface ValidationError {
  id: string
  field: string
  message: string
  severity: 'error' | 'warning'
}

export interface ValidationResult {
  valid: boolean
  errors: ValidationError[]
  warnings: ValidationError[]
}

export class BenchmarkDataValidator {
  /**
   * Validate a single benchmark result
   */
  static validateResult(result: BenchmarkResult): ValidationResult {
    const errors: ValidationError[] = []
    const warnings: ValidationError[] = []

    // Validate required fields
    if (!result.id) {
      errors.push({ id: result.id, field: 'id', message: 'Missing ID', severity: 'error' })
    }

    if (!result.name) {
      errors.push({ id: result.id, field: 'name', message: 'Missing name', severity: 'error' })
    }

    if (!result.category) {
      errors.push({ id: result.id, field: 'category', message: 'Missing category', severity: 'error' })
    }

    if (!result.timestamp) {
      errors.push({ id: result.id, field: 'timestamp', message: 'Missing timestamp', severity: 'error' })
    }

    // Validate config
    if (!result.config) {
      errors.push({ id: result.id, field: 'config', message: 'Missing config', severity: 'error' })
    } else {
      if (!result.config.jvmVersion) {
        warnings.push({ id: result.id, field: 'config.jvmVersion', message: 'Missing JVM version', severity: 'warning' })
      }

      if (!result.config.javaVersion) {
        warnings.push({ id: result.id, field: 'config.javaVersion', message: 'Missing Java version', severity: 'warning' })
      }
    }

    // Validate metrics
    if (!result.metrics) {
      errors.push({ id: result.id, field: 'metrics', message: 'Missing metrics', severity: 'error' })
    } else {
      const metricValidation = this.validateMetrics(result.metrics, result.id)
      errors.push(...metricValidation.errors)
      warnings.push(...metricValidation.warnings)
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
    }
  }

  /**
   * Validate metrics object
   */
  static validateMetrics(metrics: BenchmarkMetrics, id: string): ValidationResult {
    const errors: ValidationError[] = []
    const warnings: ValidationError[] = []

    // Check if at least one metric is present
    const hasAnyMetric =
      metrics.score !== undefined ||
      metrics.throughput !== undefined ||
      metrics.latency !== undefined ||
      metrics.cpu !== undefined ||
      metrics.memory !== undefined

    if (!hasAnyMetric) {
      errors.push({ id, field: 'metrics', message: 'No metrics present', severity: 'error' })
    }

    // Validate score
    if (metrics.score !== undefined) {
      if (isNaN(metrics.score)) {
        errors.push({ id, field: 'metrics.score', message: 'Score is NaN', severity: 'error' })
      }

      if (metrics.score < 0) {
        errors.push({ id, field: 'metrics.score', message: 'Score is negative', severity: 'error' })
      }

      if (!metrics.scoreUnit) {
        warnings.push({ id, field: 'metrics.scoreUnit', message: 'Score unit missing', severity: 'warning' })
      }
    }

    // Validate throughput
    if (metrics.throughput !== undefined) {
      if (isNaN(metrics.throughput)) {
        errors.push({ id, field: 'metrics.throughput', message: 'Throughput is NaN', severity: 'error' })
      }

      if (metrics.throughput < 0) {
        errors.push({ id, field: 'metrics.throughput', message: 'Throughput is negative', severity: 'error' })
      }

      // Sanity check: throughput should be reasonable
      if (metrics.throughput > 1e12) {
        warnings.push({ id, field: 'metrics.throughput', message: 'Throughput suspiciously high (>1T ops/sec)', severity: 'warning' })
      }
    }

    // Validate latency
    if (metrics.latency !== undefined) {
      if (isNaN(metrics.latency)) {
        errors.push({ id, field: 'metrics.latency', message: 'Latency is NaN', severity: 'error' })
      }

      if (metrics.latency < 0) {
        errors.push({ id, field: 'metrics.latency', message: 'Latency is negative', severity: 'error' })
      }

      // Sanity check: latency should be reasonable (nanoseconds)
      if (metrics.latency > 1e12) {
        warnings.push({ id, field: 'metrics.latency', message: 'Latency suspiciously high (>1000s)', severity: 'warning' })
      }
    }

    // Validate percentiles
    const percentiles = ['p50', 'p90', 'p95', 'p99', 'p999'] as const

    for (const percentile of percentiles) {
      const value = metrics[percentile]

      if (value !== undefined) {
        if (isNaN(value)) {
          errors.push({ id, field: `metrics.${percentile}`, message: `${percentile} is NaN`, severity: 'error' })
        }

        if (value < 0) {
          errors.push({ id, field: `metrics.${percentile}`, message: `${percentile} is negative`, severity: 'error' })
        }
      }
    }

    // Validate percentile ordering (p50 <= p90 <= p95 <= p99 <= p999)
    if (metrics.p50 !== undefined && metrics.p99 !== undefined && metrics.p50 > metrics.p99) {
      errors.push({ id, field: 'metrics.percentiles', message: 'p50 > p99 (invalid ordering)', severity: 'error' })
    }

    // Validate CPU
    if (metrics.cpu !== undefined) {
      if (isNaN(metrics.cpu)) {
        errors.push({ id, field: 'metrics.cpu', message: 'CPU is NaN', severity: 'error' })
      }

      if (metrics.cpu < 0 || metrics.cpu > 100) {
        warnings.push({ id, field: 'metrics.cpu', message: 'CPU percentage outside 0-100 range', severity: 'warning' })
      }
    }

    // Validate memory
    if (metrics.memory !== undefined) {
      if (isNaN(metrics.memory)) {
        errors.push({ id, field: 'metrics.memory', message: 'Memory is NaN', severity: 'error' })
      }

      if (metrics.memory < 0) {
        errors.push({ id, field: 'metrics.memory', message: 'Memory is negative', severity: 'error' })
      }
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
    }
  }

  /**
   * Validate multiple benchmark results
   */
  static validateResults(results: BenchmarkResult[]): {
    valid: boolean
    errors: ValidationError[]
    warnings: ValidationError[]
    results: Map<string, ValidationResult>
  } {
    const allErrors: ValidationError[] = []
    const allWarnings: ValidationError[] = []
    const resultMap = new Map<string, ValidationResult>()

    for (const result of results) {
      const validation = this.validateResult(result)
      resultMap.set(result.id, validation)
      allErrors.push(...validation.errors)
      allWarnings.push(...validation.warnings)
    }

    return {
      valid: allErrors.length === 0,
      errors: allErrors,
      warnings: allWarnings,
      results: resultMap,
    }
  }

  /**
   * Detect outliers in metrics
   */
  static detectOutliers(
    results: BenchmarkResult[],
    metric: keyof BenchmarkMetrics
  ): Array<{ id: string; name: string; value: number; zScore: number }> {
    const values = results
      .map(r => ({ id: r.id, name: r.name, value: r.metrics[metric] as number }))
      .filter(v => v.value !== undefined && !isNaN(v.value))

    if (values.length < 3) {
      return [] // Need at least 3 data points
    }

    // Calculate mean and standard deviation
    const mean = values.reduce((sum, v) => sum + v.value, 0) / values.length
    const variance = values.reduce((sum, v) => sum + Math.pow(v.value - mean, 2), 0) / values.length
    const stdDev = Math.sqrt(variance)

    // Detect outliers (z-score > 3)
    const outliers = values
      .map(v => ({ ...v, zScore: Math.abs((v.value - mean) / stdDev) }))
      .filter(v => v.zScore > 3)

    return outliers
  }

  /**
   * Check for missing expected benchmarks
   */
  static checkMissingBenchmarks(
    results: BenchmarkResult[],
    expected: string[]
  ): Array<string> {
    const foundNames = new Set(results.map(r => r.name.toLowerCase()))

    return expected.filter(name => !foundNames.has(name.toLowerCase()))
  }

  /**
   * Validate time series consistency
   */
  static validateTimeSeries(results: BenchmarkResult[]): {
    valid: boolean
    issues: string[]
  } {
    const issues: string[] = []

    // Sort by timestamp
    const sorted = [...results].sort((a, b) => a.timestamp.getTime() - b.timestamp.getTime())

    // Check for duplicates
    const seen = new Set<string>()
    for (const result of sorted) {
      if (seen.has(result.id)) {
        issues.push(`Duplicate result ID: ${result.id}`)
      }
      seen.add(result.id)
    }

    // Check for future timestamps
    const now = new Date()
    for (const result of sorted) {
      if (result.timestamp > now) {
        issues.push(`Future timestamp: ${result.id} at ${result.timestamp.toISOString()}`)
      }
    }

    // Check for gaps in time series (if we have multiple results for same benchmark)
    const byName = new Map<string, BenchmarkResult[]>()
    for (const result of sorted) {
      const existing = byName.get(result.name) || []
      existing.push(result)
      byName.set(result.name, existing)
    }

    for (const [name, nameResults] of byName.entries()) {
      if (nameResults.length > 1) {
        // Check for reasonable spacing
        for (let i = 1; i < nameResults.length; i++) {
          const gap = nameResults[i].timestamp.getTime() - nameResults[i - 1].timestamp.getTime()
          const gapDays = gap / (1000 * 60 * 60 * 24)

          if (gapDays > 365) {
            issues.push(`Large gap (${gapDays.toFixed(0)} days) between results for ${name}`)
          }
        }
      }
    }

    return {
      valid: issues.length === 0,
      issues,
    }
  }
}
