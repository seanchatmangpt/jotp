#!/usr/bin/env node
/**
 * Benchmark Data Processing Script
 *
 * Reads all benchmark result files and generates processed JSON data
 * for the benchmark dashboard
 */

import * as fs from 'fs'
import * as path from 'path'
import { BenchmarkDataParser } from './benchmark-data-parser'
import { BenchmarkDataNormalizer } from './benchmark-data-normalizer'
import { BenchmarkDataAggregator } from './benchmark-data-aggregator'
import { BenchmarkResult } from './types/benchmark'

interface ProcessingOptions {
  benchmarkResultsDir: string
  outputDir: string
}

export class BenchmarkProcessor {
  private options: ProcessingOptions

  constructor(options: ProcessingOptions) {
    this.options = options
    this.ensureOutputDir()
  }

  /**
   * Process all benchmark files
   */
  async processAll(): Promise<{
    benchmarks: BenchmarkResult[]
    summary: any
    timeseries: any[]
  }> {
    console.log('🔍 Scanning benchmark results directory:', this.options.benchmarkResultsDir)

    const allResults: BenchmarkResult[] = []

    // Process JMH JSON files
    const jmhResults = await this.processJMHFiles()
    allResults.push(...jmhResults)
    console.log(`✅ Processed ${jmhResults.length} JMH JSON results`)

    // Process markdown files
    const markdownResults = await this.processMarkdownFiles()
    allResults.push(...markdownResults)
    console.log(`✅ Processed ${markdownResults.length} markdown results`)

    // Process log files
    const logResults = await this.processLogFiles()
    allResults.push(...logResults)
    console.log(`✅ Processed ${logResults.length} log results`)

    console.log(`\n📊 Total benchmarks processed: ${allResults.length}`)

    // Normalize all results
    console.log('\n🔧 Normalizing benchmark data...')
    const normalizedResults = BenchmarkDataNormalizer.normalizeResults(allResults)

    // Generate aggregated data
    console.log('📈 Generating aggregations...')
    const summary = BenchmarkDataAggregator.generateSummary(normalizedResults)
    const timeseries = BenchmarkDataAggregator.generateTimeSeries(normalizedResults)
    const slaCompliance = BenchmarkDataAggregator.generateSLACompliance(normalizedResults)
    const capacityProfiles = BenchmarkDataAggregator.generateCapacityProfiles(normalizedResults)
    const hotPathValidation = BenchmarkDataAggregator.generateHotPathValidation(normalizedResults)
    const throughputScaling = BenchmarkDataAggregator.generateThroughputScaling(normalizedResults)

    // Write output files
    console.log('\n💾 Writing output files...')
    await this.writeOutputFiles({
      benchmarks: normalizedResults,
      summary,
      timeseries,
      slaCompliance,
      capacityProfiles,
      hotPathValidation,
      throughputScaling,
    })

    return {
      benchmarks: normalizedResults,
      summary,
      timeseries,
    }
  }

  /**
   * Process JMH JSON files
   */
  private async processJMHFiles(): Promise<BenchmarkResult[]> {
    const results: BenchmarkResult[] = []
    const jmhFile = path.join(this.options.benchmarkResultsDir, 'jmh-results.json')

    if (!fs.existsSync(jmhFile)) {
      console.log('⚠️  JMH results file not found:', jmhFile)
      return results
    }

    try {
      const content = fs.readFileSync(jmhFile, 'utf-8')
      const jmhData = JSON.parse(content)

      if (Array.isArray(jmhData)) {
        return BenchmarkDataParser.parseJMHResults(jmhData)
      }
    } catch (error) {
      console.error('❌ Error processing JMH file:', error)
    }

    return results
  }

  /**
   * Process markdown files
   */
  private async processMarkdownFiles(): Promise<BenchmarkResult[]> {
    const results: BenchmarkResult[] = []
    const files = fs.readdirSync(this.options.benchmarkResultsDir)

    // File patterns to categorize
    const patterns: Record<string, string> = {
      'baseline-results.md': 'baseline',
      'throughput-results.md': 'throughput',
      'capacity-planning-results.md': 'capacity',
      'precision-results.md': 'precision',
      'stress-test-results.md': 'stress',
      'ACTUAL-framework-observability.md': 'observability',
      'ACTUAL-hot-path-validation.md': 'hot-path',
      'ACTUAL-observability-performance.md': 'observability',
    }

    for (const file of files) {
      if (!file.endsWith('.md')) continue

      const category = patterns[file] || this.inferCategoryFromFilename(file)
      const filePath = path.join(this.options.benchmarkResultsDir, file)

      try {
        const content = fs.readFileSync(filePath, 'utf-8')
        const parsed = BenchmarkDataParser.parseMarkdownResults(content, category as any, file)
        results.push(...parsed)
      } catch (error) {
        console.error(`❌ Error processing ${file}:`, error)
      }
    }

    return results
  }

  /**
   * Process log files
   */
  private async processLogFiles(): Promise<BenchmarkResult[]> {
    const results: BenchmarkResult[] = []
    const files = fs.readdirSync(this.options.benchmarkResultsDir)

    const logPatterns: Record<string, string> = {
      'baseline-execution.log': 'baseline',
      'throughput-execution.log': 'throughput',
      'throughput-execution-final.log': 'throughput',
      'precision-execution.log': 'precision',
      'capacity-execution.log': 'capacity',
      'stress-test-execution.log': 'stress',
    }

    for (const file of files) {
      if (!file.endsWith('.log')) continue

      const category = logPatterns[file] || this.inferCategoryFromFilename(file)
      const filePath = path.join(this.options.benchmarkResultsDir, file)

      try {
        const content = fs.readFileSync(filePath, 'utf-8')
        const parsed = BenchmarkDataParser.parseLogResults(content, category as any, file)
        results.push(...parsed)
      } catch (error) {
        console.error(`❌ Error processing ${file}:`, error)
      }
    }

    return results
  }

  /**
   * Infer category from filename
   */
  private inferCategoryFromFilename(filename: string): string {
    const lower = filename.toLowerCase()

    if (lower.includes('baseline')) return 'baseline'
    if (lower.includes('throughput')) return 'throughput'
    if (lower.includes('capacity')) return 'capacity'
    if (lower.includes('precision')) return 'precision'
    if (lower.includes('stress')) return 'stress'
    if (lower.includes('observability') || lower.includes('hot-path')) return 'observability'
    if (lower.includes('memory') || lower.includes('allocation')) return 'memory'
    if (lower.includes('jit')) return 'jit'
    if (lower.includes('architecture')) return 'architecture'

    return 'baseline'
  }

  /**
   * Write output JSON files
   */
  private async writeOutputFiles(data: {
    benchmarks: BenchmarkResult[]
    summary: any
    timeseries: any[]
    slaCompliance: any[]
    capacityProfiles: any[]
    hotPathValidation: any[]
    throughputScaling: any[]
  }): Promise<void> {
    const files = [
      { name: 'benchmarks.json', content: data.benchmarks },
      { name: 'summary.json', content: data.summary },
      { name: 'timeseries.json', content: data.timeseries },
      { name: 'sla-compliance.json', content: data.slaCompliance },
      { name: 'capacity-profiles.json', content: data.capacityProfiles },
      { name: 'hot-path-validation.json', content: data.hotPathValidation },
      { name: 'throughput-scaling.json', content: data.throughputScaling },
    ]

    for (const file of files) {
      const filePath = path.join(this.options.outputDir, file.name)
      fs.writeFileSync(filePath, JSON.stringify(file.content, null, 2))
      console.log(`  ✅ ${file.name}`)
    }

    // Write a combined index
    const index = {
      lastUpdated: new Date().toISOString(),
      files: files.map(f => f.name),
      totalBenchmarks: data.benchmarks.length,
      summary: data.summary,
    }

    const indexPath = path.join(this.options.outputDir, 'index.json')
    fs.writeFileSync(indexPath, JSON.stringify(index, null, 2))
    console.log(`  ✅ index.json`)
  }

  /**
   * Ensure output directory exists
   */
  private ensureOutputDir(): void {
    if (!fs.existsSync(this.options.outputDir)) {
      fs.mkdirSync(this.options.outputDir, { recursive: true })
    }
  }
}

/**
 * Main execution
 */
async function main() {
  const benchmarkResultsDir = path.join(process.cwd(), '..', 'benchmark-results')
  const outputDir = path.join(process.cwd(), 'public', 'data')

  console.log('🚀 JOTP Benchmark Data Processor')
  console.log('=' .repeat(60))

  const processor = new BenchmarkProcessor({
    benchmarkResultsDir,
    outputDir,
  })

  try {
    await processor.processAll()
    console.log('\n✅ Processing complete!')
    console.log(`📁 Output files written to: ${outputDir}`)
  } catch (error) {
    console.error('\n❌ Processing failed:', error)
    process.exit(1)
  }
}

// Run if executed directly
if (require.main === module) {
  main()
}

export { BenchmarkProcessor }
