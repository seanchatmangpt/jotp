/**
 * Generate realistic benchmark data for JOTP demonstration and testing
 */

import {
  ThroughputData,
  LatencyData,
  CapacityData,
  TimeSeriesData,
  ComparisonData,
  generateSystemComparison,
} from '@/components/charts';
import { generateTimeSeriesData, calculatePercentiles } from '@/lib/chart-utils';

/**
 * Generate throughput comparison data
 */
export function generateThroughputData(
  configs: string[],
  baseThroughput: number = 90000,
  variance: number = 10000
): ThroughputData[] {
  return configs.map(config => ({
    name: config,
    disabled: baseThroughput + (Math.random() - 0.5) * variance,
    enabled: baseThroughput + Math.random() * variance,
    withSubscribers: baseThroughput + (Math.random() - 0.2) * variance,
  }));
}

/**
 * Generate latency percentile data
 */
export function generateLatencyData(
  systems: string[],
  baseLatency: number = 2.0,
  variance: number = 1.0
): LatencyData[] {
  return systems.map(system => {
    // Generate a sample of latency values
    const sampleSize = 1000;
    const samples = Array.from({ length: sampleSize }, () =>
      Math.max(0.1, baseLatency + (Math.random() - 0.3) * variance)
    );

    const percentiles = calculatePercentiles(samples);

    return {
      name: system,
      p50: percentiles.p50,
      p95: percentiles.p95,
      p99: percentiles.p99,
      p999: percentiles.p999,
    };
  });
}

/**
 * Generate capacity planning data for different instance sizes
 */
export function generateCapacityData(
  instanceTypes: { name: string; cpu: number; memory: number }[],
  loadPoints: number[] = [100, 500, 1000, 2000, 5000]
): { name: string; data: CapacityData[] }[] {
  return instanceTypes.map(instance => {
    const cpuCapacity = instance.cpu * 1000; // Scale factor
    const memoryCapacity = instance.memory * 1024 * 1024; // Convert to bytes

    return {
      name: instance.name,
      data: loadPoints.map(load => {
        const utilization = load / 5000; // Normalize to 0-1
        const throughput = Math.min(
          cpuCapacity * (1 - Math.pow(utilization, 2)),
          instance.cpu * 50000 * (1 - utilization * 0.1)
        );

        return {
          load,
          throughput: Math.max(0, throughput),
          cpu: Math.min(100, utilization * 100 * (0.8 + Math.random() * 0.4)),
          memory: Math.min(
            memoryCapacity,
            load * 1024 * 1024 * (1 + Math.random() * 0.5)
          ),
        };
      }),
    };
  });
}

/**
 * Generate realistic time-series benchmark data
 */
export function generateBenchmarkTimeSeries(
  duration: number = 60, // minutes
  interval: number = 1, // minutes
  baseThroughput: number = 95000,
  baseLatency: number = 2.0,
  baseCpu: number = 45
): TimeSeriesData[] {
  const points = Math.floor(duration / interval);
  const data: TimeSeriesData[] = [];

  for (let i = 0; i < points; i++) {
    const timestamp = new Date(
      Date.now() - (points - i) * interval * 60000
    ).toISOString();

    // Add some realistic patterns (diurnal variation, random spikes)
    const timeOfDay = (i % 24) / 24; // 0-1
    const diurnalFactor = Math.sin(timeOfDay * Math.PI * 2) * 0.1 + 1;

    const randomFactor = 0.9 + Math.random() * 0.2;

    data.push({
      timestamp,
      throughput: baseThroughput * diurnalFactor * randomFactor,
      latency: baseLatency * (2 - diurnalFactor) * randomFactor,
      cpu: baseCpu * diurnalFactor * randomFactor,
      memory: baseCpu * 1024 * 1024 * (0.8 + Math.random() * 0.4),
    });
  }

  return data;
}

/**
 * Generate system comparison data for radar charts
 */
export function generateSystemComparisonData(
  systems: {
    name: string;
    characteristics: {
      throughput: number; // 0-100
      latency: number; // 0-100 (lower is better, will be inverted)
      cpuEfficiency: number;
      memoryEfficiency: number;
      faultTolerance: number;
    };
  }[]
): ComparisonData[] {
  const metrics = [
    'Throughput',
    'Latency',
    'CPU Efficiency',
    'Memory Efficiency',
    'Fault Tolerance',
  ];

  return generateSystemComparison(
    metrics,
    systems.map(system => ({
      name: system.name,
      values: [
        system.characteristics.throughput,
        system.characteristics.latency, // Higher = better (already normalized)
        system.characteristics.cpuEfficiency,
        system.characteristics.memoryEfficiency,
        system.characteristics.faultTolerance,
      ],
    }))
  );
}

/**
 * Predefined system profiles for comparison
 */
export const systemProfiles = {
  jotp: {
    name: 'JOTP',
    characteristics: {
      throughput: 95,
      latency: 88,
      cpuEfficiency: 92,
      memoryEfficiency: 85,
      faultTolerance: 98,
    },
  },
  akka: {
    name: 'Akka',
    characteristics: {
      throughput: 82,
      latency: 90,
      cpuEfficiency: 78,
      memoryEfficiency: 88,
      faultTolerance: 85,
    },
  },
  erlang: {
    name: 'Erlang/OTP',
    characteristics: {
      throughput: 88,
      latency: 95,
      cpuEfficiency: 80,
      memoryEfficiency: 90,
      faultTolerance: 95,
    },
  },
  go: {
    name: 'Go',
    characteristics: {
      throughput: 78,
      latency: 82,
      cpuEfficiency: 85,
      memoryEfficiency: 75,
      faultTolerance: 65,
    },
  },
};

/**
 * Predefined instance profiles for capacity planning
 */
export const instanceProfiles = [
  { name: 'Small (2CPU, 4GB)', cpu: 2, memory: 4 },
  { name: 'Medium (4CPU, 8GB)', cpu: 4, memory: 8 },
  { name: 'Large (8CPU, 16GB)', cpu: 8, memory: 16 },
  { name: 'Enterprise (16CPU, 32GB)', cpu: 16, memory: 32 },
];

/**
 * Generate a complete benchmark dataset
 */
export function generateCompleteBenchmarkDataset() {
  return {
    throughput: generateThroughputData(
      ['Baseline', 'Optimized', 'Tuned'],
      90000,
      10000
    ),

    latency: generateLatencyData(['JOTP', 'Akka', 'Erlang/OTP'], 2.0, 1.0),

    capacity: generateCapacityData(instanceProfiles, [100, 500, 1000, 2000, 5000]),

    timeseries: generateBenchmarkTimeSeries(60, 1, 95000, 2.0, 45),

    comparison: generateSystemComparisonData([
      systemProfiles.jotp,
      systemProfiles.akka,
      systemProfiles.erlang,
    ]),
  };
}

/**
 * Generate scenario-specific data for demonstrations
 */
export const scenarios = {
  coldStart: {
    throughput: generateThroughputData(['Cold Start', 'Warm Start'], 70000, 15000),
    latency: generateLatencyData(['Cold Start', 'Warm Start'], 3.0, 2.0),
  },

  highLoad: {
    timeseries: generateBenchmarkTimeSeries(30, 0.5, 120000, 3.5, 85),
    capacity: generateCapacityData(
      [
        { name: 'Small', cpu: 2, memory: 4 },
        { name: 'Large', cpu: 8, memory: 16 },
      ],
      [1000, 2000, 5000, 10000]
    ),
  },

  faultTolerance: {
    comparison: generateSystemComparisonData([
      {
        name: 'JOTP',
        characteristics: {
          throughput: 90,
          latency: 85,
          cpuEfficiency: 88,
          memoryEfficiency: 82,
          faultTolerance: 98,
        },
      },
      {
        name: 'Akka',
        characteristics: {
          throughput: 80,
          latency: 88,
          cpuEfficiency: 75,
          memoryEfficiency: 85,
          faultTolerance: 85,
        },
      },
    ]),
  },
};
