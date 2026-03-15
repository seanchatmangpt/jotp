/**
 * Benchmark Data Parser
 *
 * Parses raw benchmark data from various formats (JSON, Markdown, logs)
 * into standardized BenchmarkResult objects
 */

import { BenchmarkResult, JMHResult, MarkdownBenchmark, BenchmarkCategory } from './types/benchmark'

export class BenchmarkDataParser {
  /**
   * Parse JMH JSON results
   */
  static parseJMHResults(data: JMHResult[]): BenchmarkResult[] {
    return data.map((result, index) => {
      const category = this.inferCategory(result.benchmark)
      const metrics = this.extractJMHMetrics(result)

      return {
        id: `jmh-${index}-${Date.now()}`,
        name: result.benchmark.split('.').pop() || result.benchmark,
        category,
        timestamp: new Date(),
        config: {
          jvmVersion: result.vmVersion,
          jvmVendor: result.vmVendor,
          javaVersion: result.vmVersion.split('+')[0],
          platform: process.platform,
          mode: result.mode as any,
          threads: result.threads,
          forks: result.forks,
          warmupIterations: result.warmupIterations,
          measurementIterations: result.measurementIterations,
        },
        metrics,
        status: 'pass',
        source: 'jmh-json',
      }
    })
  }

  /**
   * Parse markdown benchmark results
   */
  static parseMarkdownResults(
    content: string,
    category: BenchmarkCategory,
    source: string
  ): BenchmarkResult[] {
    const results: BenchmarkResult[] = []
    const sections = this.extractMarkdownSections(content)

    // Extract metadata
    const metadata = this.extractMarkdownMetadata(content)

    for (const section of sections) {
      const metrics = this.extractMetricsFromTable(section.content)
      if (metrics) {
        results.push({
          id: `${category}-${section.heading}-${Date.now()}`,
          name: section.heading,
          category,
          timestamp: metadata.date || new Date(),
          config: {
            jvmVersion: metadata.javaVersion as string || 'unknown',
            jvmVendor: metadata.jvmVendor as string || 'unknown',
            javaVersion: metadata.javaVersion as string || '26',
            platform: metadata.platform as string || process.platform,
            mode: 'Throughput',
            threads: 1,
            forks: 1,
            warmupIterations: 0,
            measurementIterations: 0,
            ...metadata,
          },
          metrics,
          status: this.inferStatus(metrics, metadata),
          source,
        })
      }
    }

    return results
  }

  /**
   * Parse log files for benchmark data
   */
  static parseLogResults(content: string, category: BenchmarkCategory, source: string): BenchmarkResult[] {
    const results: BenchmarkResult[] = []
    const lines = content.split('\n')

    let currentBenchmark: Partial<BenchmarkResult> | null = null

    for (const line of lines) {
      // Detect benchmark start
      const benchmarkMatch = line.match(/===?\s*BENCHMARK\s*\d+:\s*(.+)/)
      if (benchmarkMatch) {
        if (currentBenchmark) {
          results.push(currentBenchmark as BenchmarkResult)
        }
        currentBenchmark = {
          id: `${category}-${benchmarkMatch[1].trim().toLowerCase().replace(/\s+/g, '-')}-${Date.now()}`,
          name: benchmarkMatch[1].trim(),
          category,
          timestamp: new Date(),
          config: {
            jvmVersion: '26',
            jvmVendor: 'unknown',
            javaVersion: '26',
            platform: process.platform,
            mode: 'Throughput',
            threads: 1,
            forks: 1,
            warmupIterations: 0,
            measurementIterations: 0,
          },
          metrics: {},
          status: 'pass',
          source,
        }
      }

      // Extract metrics from log lines
      if (currentBenchmark) {
        const iterationsMatch = line.match(/Iterations:\s*(\d+)/)
        if (iterationsMatch && currentBenchmark.config) {
          currentBenchmark.config.measurementIterations = parseInt(iterationsMatch[1])
        }

        const totalTimeMatch = line.match(/Total time:\s*(\d+)\s*ms/)
        if (totalTimeMatch && currentBenchmark.metrics) {
          currentBenchmark.metrics.latency = parseInt(totalTimeMatch[1]) * 1_000_000 // Convert to ns
        }

        const avgMatch = line.match(/Average\s+(.+)[:\s]+([\d.]+)\s*(ns\/op|µs|ms)/)
        if (avgMatch && currentBenchmark.metrics) {
          const value = parseFloat(avgMatch[2])
          const unit = avgMatch[3]
          currentBenchmark.metrics.latency = this.convertToNanoseconds(value, unit)
        }

        const opsMatch = line.match(/([\d.]+)\s*ops\/sec/)
        if (opsMatch && currentBenchmark.metrics) {
          currentBenchmark.metrics.throughput = parseFloat(opsMatch[1])
        }
      }
    }

    if (currentBenchmark) {
      results.push(currentBenchmark as BenchmarkResult)
    }

    return results
  }

  /**
   * Infer benchmark category from benchmark name
   */
  private static inferCategory(benchmarkName: string): BenchmarkCategory {
    const name = benchmarkName.toLowerCase()

    if (name.includes('baseline') || name.includes('disabled')) return 'baseline'
    if (name.includes('throughput') || name.includes('eventbus')) return 'throughput'
    if (name.includes('capacity') || name.includes('planner')) return 'capacity'
    if (name.includes('precision') || name.includes('latency')) return 'precision'
    if (name.includes('stress') || name.includes('tsunami') || name.includes('storm')) return 'stress'
    if (name.includes('observability') || name.includes('metrics') || name.includes('hotpath')) return 'observability'
    if (name.includes('memory') || name.includes('allocation')) return 'memory'
    if (name.includes('jit') || name.includes('optimization')) return 'jit'
    if (name.includes('architecture') || name.includes('supervisor')) return 'architecture'

    return 'baseline'
  }

  /**
   * Extract metrics from JMH result
   */
  private static extractJMHMetrics(result: JMHResult) {
    const metric = result.primaryMetric
    const isNanoseconds = metric.scoreUnit.includes('ns') || metric.scoreUnit.includes('op')

    return {
      score: metric.score,
      scoreUnit: metric.scoreUnit,
      scoreError: metric.scoreError,
      scoreConfidence: metric.scoreConfidence,
      scorePercentiles: metric.scorePercentiles,
      rawData: metric.rawData,

      // Extract percentiles
      p50: metric.scorePercentiles?.['50.0'],
      p90: metric.scorePercentiles?.['90.0'],
      p95: metric.scorePercentiles?.['95.0'],
      p99: metric.scorePercentiles?.['99.0'],
      p999: metric.scorePercentiles?.['99.9'],
      min: metric.min,
      max: metric.max,

      // Calculate throughput if in ops/s mode
      throughput: metric.scoreUnit.includes('ops/s') ? metric.score : undefined,

      // Calculate latency if in time mode
      latency: isNanoseconds ? this.extractTimeValue(metric.score, metric.scoreUnit) : undefined,
    }
  }

  /**
   * Extract markdown sections
   */
  private static extractMarkdownSections(content: string): Array<{ heading: string; content: string }> {
    const sections: Array<{ heading: string; content: string }> = []
    const lines = content.split('\n')
    let currentSection: { heading: string; content: string } | null = null

    for (const line of lines) {
      const headingMatch = line.match(/^#{2,4}\s+(.+)/)
      if (headingMatch) {
        if (currentSection) {
          sections.push(currentSection)
        }
        currentSection = { heading: headingMatch[1].trim(), content: '' }
      } else if (currentSection) {
        currentSection.content += line + '\n'
      }
    }

    if (currentSection) {
      sections.push(currentSection)
    }

    return sections
  }

  /**
   * Extract metadata from markdown front matter or key sections
   */
  private static extractMarkdownMetadata(content: string): Record<string, string | number | Date> {
    const metadata: Record<string, string | number | Date> = {}

    // Extract common metadata fields
    const dateMatch = content.match(/\*\*Date:\*\*\s*(\d{4}-\d{2}-\d{2})/)
    if (dateMatch) {
      metadata.date = new Date(dateMatch[1])
    }

    const javaMatch = content.match(/\*\*Java Version:\*\*\s*([^\n*]+)/)
    if (javaMatch) {
      metadata.javaVersion = javaMatch[1].trim()
    }

    const jvmMatch = content.match(/\*\*JVM:\*\*\s*([^\n*]+)/)
    if (jvmMatch) {
      metadata.jvmVersion = jvmMatch[1].trim()
    }

    return metadata
  }

  /**
   * Extract metrics from markdown tables
   */
  private static extractMetricsFromTable(content: string): any {
    const metrics: any = {}

    // Extract table rows
    const tableRows = content.match(/\|[^|]+\|/g)
    if (!tableRows) return null

    for (const row of tableRows.slice(1)) { // Skip header
      const cells = row.split('|').filter(c => c.trim()).map(c => c.trim())
      if (cells.length < 2) continue

      const key = cells[0].toLowerCase().replace(/\s+/g, '_')
      const value = this.parseValue(cells[1])

      metrics[key] = value
    }

    return Object.keys(metrics).length > 0 ? metrics : null
  }

  /**
   * Parse a value string to appropriate type
   */
  private static parseValue(value: string): string | number | boolean {
    const trimmed = value.trim()

    // Remove common markers
    const cleanValue = trimmed.replace(/[✅❌*]/g, '').trim()

    // Check for boolean
    if (cleanValue.toLowerCase() === 'pass') return true
    if (cleanValue.toLowerCase() === 'fail') return false

    // Check for number
    const numberMatch = cleanValue.match(/([\d.]+)(\s*(ns|µs|ms|ops\/sec|%|bytes|MB|GB))?/)
    if (numberMatch) {
      const num = parseFloat(numberMatch[1])
      const unit = numberMatch[2]

      if (unit?.includes('ns')) return num
      if (unit?.includes('µs')) return num * 1000
      if (unit?.includes('ms')) return num * 1_000_000
      if (unit?.includes('%')) return num

      return num
    }

    return cleanValue
  }

  /**
   * Infer status from metrics
   */
  private static inferStatus(metrics: any, metadata: any): 'pass' | 'fail' | 'warning' {
    if (metadata.status === '✅' || metadata.status === 'PASS') return 'pass'
    if (metadata.status === '❌' || metadata.status === 'FAIL') return 'fail'

    // Check for common failure patterns
    if (metrics.violations && metrics.violations > 0) return 'fail'
    if (metrics.status === 'FAILED') return 'fail'
    if (metrics.status === 'PASSED') return 'pass'

    return 'pass'
  }

  /**
   * Extract time value from unit string
   */
  private static extractTimeValue(value: number, unit: string): number {
    if (unit.includes('ms')) return value * 1_000_000
    if (unit.includes('µs')) return value * 1000
    return value
  }

  /**
   * Convert time value to nanoseconds
   */
  private static convertToNanoseconds(value: number, unit: string): number {
    if (unit.includes('ms')) return value * 1_000_000
    if (unit.includes('µs')) return value * 1000
    return value
  }
}
