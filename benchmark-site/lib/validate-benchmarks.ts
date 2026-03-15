#!/usr/bin/env node
/**
 * Benchmark Validation Script
 *
 * Validates processed benchmark data for integrity and consistency
 */

import * as fs from 'fs'
import * as path from 'path'
import { BenchmarkDataValidator } from './benchmark-data-validator'
import { BenchmarkResult } from './types/benchmark'

interface ValidationOptions {
  dataDir: string
}

export class BenchmarkValidator {
  private options: ValidationOptions

  constructor(options: ValidationOptions) {
    this.options = options
  }

  /**
   * Validate all benchmark data files
   */
  async validateAll(): Promise<void> {
    console.log('🔍 Validating benchmark data...\n')

    const benchmarksFile = path.join(this.options.dataDir, 'benchmarks.json')

    if (!fs.existsSync(benchmarksFile)) {
      console.error('❌ Benchmarks file not found:', benchmarksFile)
      console.log('💡 Run "npm run process-benchmarks" first')
      process.exit(1)
    }

    // Load benchmark data
    console.log('📖 Loading benchmark data...')
    const content = fs.readFileSync(benchmarksFile, 'utf-8')
    const results: BenchmarkResult[] = JSON.parse(content)

    console.log(`✅ Loaded ${results.length} benchmark results\n`)

    // Validate all results
    console.log('🔬 Validating results...')
    const validation = BenchmarkDataValidator.validateResults(results)

    // Report errors
    if (validation.errors.length > 0) {
      console.log(`\n❌ Found ${validation.errors.length} errors:\n`)

      for (const error of validation.errors) {
        console.log(`  [${error.severity.toUpperCase()}] ${error.field}`)
        console.log(`    Result ID: ${error.id}`)
        console.log(`    Message: ${error.message}\n`)
      }
    } else {
      console.log('✅ No errors found\n')
    }

    // Report warnings
    if (validation.warnings.length > 0) {
      console.log(`⚠️  Found ${validation.warnings.length} warnings:\n`)

      for (const warning of validation.warnings) {
        console.log(`  [${warning.severity.toUpperCase()}] ${warning.field}`)
        console.log(`    Result ID: ${warning.id}`)
        console.log(`    Message: ${warning.message}\n`)
      }
    } else {
      console.log('✅ No warnings found\n')
    }

    // Detect outliers
    console.log('📊 Detecting outliers...')
    const metricsToCheck: Array<keyof typeof results[0].metrics> = [
      'throughput',
      'latency',
      'p99',
      'cpu',
    ]

    for (const metric of metricsToCheck) {
      const outliers = BenchmarkDataValidator.detectOutliers(results, metric)

      if (outliers.length > 0) {
        console.log(`\n⚠️  Found ${outliers.length} outliers in ${metric}:`)

        for (const outlier of outliers) {
          console.log(`  - ${outlier.name}`)
          console.log(`    Value: ${outlier.value}`)
          console.log(`    Z-score: ${outlier.zScore.toFixed(2)}`)
        }
      }
    }

    // Validate time series
    console.log('\n📈 Validating time series...')
    const timeSeriesValidation = BenchmarkDataValidator.validateTimeSeries(results)

    if (!timeSeriesValidation.valid) {
      console.log(`\n⚠️  Found ${timeSeriesValidation.issues.length} time series issues:\n`)

      for (const issue of timeSeriesValidation.issues) {
        console.log(`  - ${issue}`)
      }
    } else {
      console.log('✅ Time series is consistent\n')
    }

    // Summary
    console.log('\n' + '='.repeat(60))
    console.log('📋 Validation Summary')
    console.log('='.repeat(60))
    console.log(`Total Results: ${results.length}`)
    console.log(`Errors: ${validation.errors.length}`)
    console.log(`Warnings: ${validation.warnings.length}`)
    console.log(`Valid: ${validation.valid ? '✅ Yes' : '❌ No'}`)
    console.log('='.repeat(60))

    // Exit with error code if validation failed
    if (!validation.valid) {
      process.exit(1)
    }
  }
}

/**
 * Main execution
 */
async function main() {
  const dataDir = path.join(process.cwd(), 'public', 'data')

  console.log('🔎 JOTP Benchmark Data Validator')
  console.log('='.repeat(60))

  const validator = new BenchmarkValidator({ dataDir })

  try {
    await validator.validateAll()
    console.log('\n✅ Validation complete!')
  } catch (error) {
    console.error('\n❌ Validation failed:', error)
    process.exit(1)
  }
}

// Run if executed directly
if (require.main === module) {
  main()
}

export { BenchmarkValidator }
