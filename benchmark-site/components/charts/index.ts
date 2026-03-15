/**
 * Chart components exports
 *
 * This module provides all chart components for the benchmark site.
 */

export { ThroughputChart } from './throughput-chart';
export type { ThroughputData } from './throughput-chart';

export { LatencyChart } from './latency-chart';
export type { LatencyData } from './latency-chart';

export { CapacityChart } from './capacity-chart';
export type { CapacityData } from './capacity-chart';

export { TimeseriesChart } from './timeseries-chart';
export type { TimeSeriesData } from './timeseries-chart';

export { ComparisonChart, generateSystemComparison } from './comparison-chart';
export type { ComparisonData } from './comparison-chart';

export { Sparkline, SparklineWithLabel, SparklineCard } from './sparkline';
export type { SparklineData } from './sparkline';
