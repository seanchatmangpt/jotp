/**
 * Chart utility functions for data formatting and processing
 */

export interface DataPoint {
  name: string;
  value: number;
  [key: string]: string | number;
}

export interface TimeSeriesDataPoint {
  timestamp: string;
  [key: string]: string | number;
}

/**
 * Format numbers with appropriate units
 */
export function formatNumber(value: number): string {
  if (value >= 1000000) {
    return `${(value / 1000000).toFixed(1)}M`;
  }
  if (value >= 1000) {
    return `${(value / 1000).toFixed(1)}K`;
  }
  return value.toFixed(0);
}

/**
 * Format throughput values (operations per second)
 */
export function formatThroughput(value: number): string {
  return `${formatNumber(value)} ops/s`;
}

/**
 * Format latency values (milliseconds)
 */
export function formatLatency(value: number): string {
  if (value >= 1000) {
    return `${(value / 1000).toFixed(2)}s`;
  }
  if (value >= 1) {
    return `${value.toFixed(2)}ms`;
  }
  return `${(value * 1000).toFixed(2)}μs`;
}

/**
 * Format percentage values
 */
export function formatPercent(value: number): string {
  return `${value.toFixed(1)}%`;
}

/**
 * Format memory values (bytes to human readable)
 */
export function formatMemory(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB'];
  let size = bytes;
  let unitIndex = 0;

  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex++;
  }

  return `${size.toFixed(1)} ${units[unitIndex]}`;
}

/**
 * Calculate percentiles from an array of numbers
 */
export function calculatePercentiles(values: number[]): {
  p50: number;
  p95: number;
  p99: number;
  p999: number;
  min: number;
  max: number;
  mean: number;
} {
  const sorted = [...values].sort((a, b) => a - b);
  const len = sorted.length;

  const percentile = (p: number) => {
    const index = Math.ceil((p / 100) * len) - 1;
    return sorted[Math.max(0, index)];
  };

  const mean = sorted.reduce((sum, val) => sum + val, 0) / len;

  return {
    p50: percentile(50),
    p95: percentile(95),
    p99: percentile(99),
    p999: percentile(99.9),
    min: sorted[0],
    max: sorted[len - 1],
    mean
  };
}

/**
 * Generate color scale for categories
 */
export const categoryColors = [
  '#3b82f6', // blue
  '#10b981', // emerald
  '#f59e0b', // amber
  '#ef4444', // red
  '#8b5cf6', // violet
  '#ec4899', // pink
  '#06b6d4', // cyan
  '#84cc16', // lime
];

export function getCategoryColor(index: number): string {
  return categoryColors[index % categoryColors.length];
}

/**
 * Generate time series data with trend
 */
export function generateTimeSeriesData(
  baseValue: number,
  variance: number,
  points: number = 20,
  trend: 'up' | 'down' | 'stable' = 'stable'
): TimeSeriesDataPoint[] {
  const data: TimeSeriesDataPoint[] = [];
  const now = Date.now();

  for (let i = 0; i < points; i++) {
    const timestamp = new Date(now - (points - i) * 60000).toISOString();
    const trendFactor = trend === 'up' ? i * 0.1 : trend === 'down' ? -i * 0.1 : 0;
    const randomVariance = (Math.random() - 0.5) * variance;
    const value = baseValue + trendFactor * baseValue + randomVariance;

    data.push({ timestamp, value: Math.max(0, value) });
  }

  return data;
}

/**
 * Smooth data using moving average
 */
export function smoothData(data: DataPoint[], windowSize: number = 3): DataPoint[] {
  return data.map((point, index) => {
    const start = Math.max(0, index - Math.floor(windowSize / 2));
    const end = Math.min(data.length, index + Math.floor(windowSize / 2) + 1);
    const window = data.slice(start, end);

    const avgValue = window.reduce((sum, p) => sum + p.value, 0) / window.length;

    return { ...point, value: avgValue };
  });
}

/**
 * Calculate growth rate between two values
 */
export function calculateGrowthRate(previous: number, current: number): number {
  if (previous === 0) return 0;
  return ((current - previous) / previous) * 100;
}

/**
 * Normalize values to 0-100 scale
 */
export function normalizeToScale(values: number[], min: number, max: number): number[] {
  const range = max - min;
  if (range === 0) return values.map(() => 50);
  return values.map(v => ((v - min) / range) * 100);
}

/**
 * Generate comparison data for radar charts
 */
export interface RadarData {
  metric: string;
  [key: string]: string | number;
}

export function generateRadarData(
  metrics: string[],
  systems: { name: string; values: number[] }[]
): RadarData[] {
  return metrics.map((metric, index) => {
    const dataPoint: RadarData = { metric };

    systems.forEach(system => {
      dataPoint[system.name] = system.values[index];
    });

    return dataPoint;
  });
}
