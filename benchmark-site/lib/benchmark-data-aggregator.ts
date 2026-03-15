/**
 * Benchmark Data Aggregator
 *
 * Aggregates benchmark results into summaries and statistics
 */

import {
  BenchmarkResult,
  BenchmarkSummary,
  BenchmarkCategory,
  CategorySummary,
  ComparisonResult,
  SLACompliance,
  CapacityPlanningProfile,
  HotPathValidation,
  ThroughputScaling,
} from './types/benchmark'
import { BenchmarkDataNormalizer } from './benchmark-data-normalizer'

export class BenchmarkDataAggregator {
  /**
   * Generate overall summary of all benchmarks
   */
  static generateSummary(results: BenchmarkResult[]): BenchmarkSummary {
    const summary: BenchmarkSummary = {
      total: results.length,
      passed: results.filter(r => r.status === 'pass').length,
      failed: results.filter(r => r.status === 'fail').length,
      warnings: results.filter(r => r.status === 'warning').length,
      categories: {} as Record<BenchmarkCategory, CategorySummary>,
      lastUpdated: new Date(),
    }

    // Group by category
    const byCategory = this.groupByCategory(results)

    // Calculate per-category summaries
    for (const [category, categoryResults] of Object.entries(byCategory)) {
      const scores = categoryResults
        .map((r: any) => r.metrics.score)
        .filter((s: any): s is number => s !== undefined)

      summary.categories[category as BenchmarkCategory] = {
        total: categoryResults.length,
        passed: categoryResults.filter((r: any) => r.status === 'pass').length,
        failed: categoryResults.filter((r: any) => r.status === 'fail').length,
        warnings: categoryResults.filter((r: any) => r.status === 'warning').length,
        avgScore: scores.length > 0 ? scores.reduce((a: number, b: number) => a + b, 0) / scores.length : undefined,
        bestScore: scores.length > 0 ? Math.max(...scores) : undefined,
        worstScore: scores.length > 0 ? Math.min(...scores) : undefined,
      }
    }

    return summary
  }

  /**
   * Generate time series data for trends
   */
  static generateTimeSeries(results: BenchmarkResult[]): Array<{
    timestamp: Date
    value: number
    benchmarkId: string
    benchmarkName: string
  }> {
    return results
      .filter(r => r.metrics.score !== undefined)
      .map(r => ({
        timestamp: r.timestamp,
        value: r.metrics.score!,
        benchmarkId: r.id,
        benchmarkName: r.name,
      }))
      .sort((a, b) => a.timestamp.getTime() - b.timestamp.getTime())
  }

  /**
   * Compare baseline vs current results
   */
  static compareBaselines(
    baselineResults: BenchmarkResult[],
    currentResults: BenchmarkResult[]
  ): ComparisonResult[] {
    const comparisons: ComparisonResult[] = []

    for (const baseline of baselineResults) {
      const current = currentResults.find(r => r.name === baseline.name)

      if (current && current.metrics.score !== undefined && baseline.metrics.score !== undefined) {
        const delta = current.metrics.score - baseline.metrics.score
        const deltaPercent = (delta / baseline.metrics.score) * 100

        comparisons.push({
          benchmark: baseline.name,
          baseline: baseline.metrics.score,
          current: current.metrics.score,
          delta,
          deltaPercent,
          improved: this.isImprovement(baseline.category, delta),
        })
      }
    }

    return comparisons
  }

  /**
   * Generate SLA compliance report
   */
  static generateSLACompliance(results: BenchmarkResult[]): SLACompliance[] {
    const compliances: SLACompliance[] = []

    // Define SLA targets per category
    const slaTargets: Record<string, Record<string, number>> = {
      baseline: { latency: 100, p99: 1000 },
      throughput: { throughput: 1_000_000, p99: 5000 },
      precision: { latency: 100, overhead: 100 },
      observability: { overhead: 100, p99: 1000 },
      hotpath: { overhead: 1, latency: 100 },
    }

    for (const result of results) {
      const targets = slaTargets[result.category]

      if (targets) {
        for (const [metric, target] of Object.entries(targets)) {
          const actual = result.metrics[metric as keyof typeof result.metrics] as number

          if (actual !== undefined) {
            compliances.push({
              metric: `${result.name}:${metric}`,
              target,
              actual,
              status: actual <= target ? 'pass' : 'fail',
              variance: actual - target,
              variancePercent: ((actual - target) / target) * 100,
            })
          }
        }
      }
    }

    return compliances
  }

  /**
   * Generate capacity planning profiles
   */
  static generateCapacityProfiles(results: BenchmarkResult[]): CapacityPlanningProfile[] {
    const capacityResults = results.filter(r => r.category === 'capacity')

    return capacityResults.map(result => {
      const violations: string[] = []

      // Check CPU target
      if (result.metrics.cpuOverhead !== undefined) {
        const cpuTarget = (result.config as any).cpuTarget || 10
        if (result.metrics.cpuOverhead > cpuTarget) {
          violations.push(`CPU overhead ${result.metrics.cpuOverhead}% exceeds target ${cpuTarget}%`)
        }
      }

      // Check P99 latency target
      if (result.metrics.p99 !== undefined) {
        const p99Target = (result.config as any).p99Target || 10
        const p99Ms = result.metrics.p99 / 1_000_000
        if (p99Ms > p99Target) {
          violations.push(`P99 latency ${BenchmarkDataNormalizer.formatLatency(result.metrics.p99)} exceeds target ${p99Target}ms`)
        }
      }

      return {
        name: result.name,
        targetThroughput: (result.config as any).targetThroughput || 0,
        actualThroughput: result.metrics.throughput || 0,
        cpuOverhead: result.metrics.cpuOverhead || 0,
        cpuTarget: (result.config as any).cpuTarget || 10,
        p99Latency: result.metrics.p99 || 0,
        p99Target: (result.config as any).p99Target || 10,
        memoryOverhead: result.metrics.memoryOverhead || 0,
        memoryTarget: (result.config as any).memoryTarget || 10_485_760, // 10MB
        status: violations.length === 0 ? 'pass' : 'fail',
        violations,
      }
    })
  }

  /**
   * Generate hot path validation results
   */
  static generateHotPathValidation(results: BenchmarkResult[]): HotPathValidation[] {
    const hotPathResults = results.filter(r => (r.category as string) === 'hotpath' || r.name.includes('baseline'))

    return hotPathResults
      .filter(r => r.metrics.withObservability !== undefined && r.metrics.baseline !== undefined)
      .map(result => {
        const baseline = result.metrics.baseline as number
        const withObs = result.metrics.withObservability as number
        const overhead = withObs - baseline
        const overheadPercent = (overhead / baseline) * 100
        const threshold = (result.config as any).threshold || 100

        return {
          operation: result.name,
          baseline,
          withObservability: withObs,
          overhead,
          overheadPercent,
          threshold,
          status: overhead <= threshold ? 'pass' : 'fail',
        }
      })
  }

  /**
   * Generate throughput scaling analysis
   */
  static generateThroughputScaling(results: BenchmarkResult[]): ThroughputScaling[] {
    const throughputResults = results.filter(r =>
      r.name.includes('subscriber') || r.name.includes('scaling')
    )

    // Extract subscriber count from name or config
    return throughputResults
      .filter(r => r.metrics.throughput !== undefined)
      .map(result => {
        const subscribers = (result.config as any).subscribers || 1
        const baseline = throughputResults.find(r => (r.config as any).subscribers === 1)
        const baselineThroughput = baseline?.metrics.throughput || result.metrics.throughput!

        return {
          subscribers,
          throughput: result.metrics.throughput!,
          stdDev: result.metrics.scoreError || 0,
          min: result.metrics.throughputMin || result.metrics.throughput!,
          max: result.metrics.throughputMax || result.metrics.throughput!,
          efficiency: (result.metrics.throughput! / baselineThroughput) * 100,
        }
      })
      .sort((a, b) => a.subscribers - b.subscribers)
  }

  /**
   * Group results by category
   */
  private static groupByCategory(
    results: BenchmarkResult[]
  ): Map<BenchmarkCategory, BenchmarkResult[]> {
    const grouped = new Map<BenchmarkCategory, BenchmarkResult[]>()

    for (const result of results) {
      const categoryResults = grouped.get(result.category) || []
      categoryResults.push(result)
      grouped.set(result.category, categoryResults)
    }

    return grouped
  }

  /**
   * Determine if delta is an improvement
   */
  private static isImprovement(category: BenchmarkCategory, delta: number): boolean {
    // For throughput and similar metrics, higher is better
    const higherIsBetter = ['throughput', 'capacity'].includes(category)

    return higherIsBetter ? delta > 0 : delta < 0
  }

  /**
   * Calculate statistics for a metric
   */
  static calculateStatistics(values: number[]): {
    mean: number
    median: number
    stdDev: number
    min: number
    max: number
    p25: number
    p75: number
  } {
    if (values.length === 0) {
      return { mean: 0, median: 0, stdDev: 0, min: 0, max: 0, p25: 0, p75: 0 }
    }

    const sorted = [...values].sort((a, b) => a - b)
    const mean = values.reduce((a, b) => a + b, 0) / values.length
    const median = sorted[Math.floor(sorted.length / 2)]
    const min = sorted[0]
    const max = sorted[sorted.length - 1]
    const p25 = sorted[Math.floor(sorted.length * 0.25)]
    const p75 = sorted[Math.floor(sorted.length * 0.75)]

    const variance = values.reduce((sum, val) => sum + Math.pow(val - mean, 2), 0) / values.length
    const stdDev = Math.sqrt(variance)

    return { mean, median, stdDev, min, max, p25, p75 }
  }
}
