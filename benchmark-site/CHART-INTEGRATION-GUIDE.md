# Chart Integration Guide

Step-by-step guide for integrating JOTP chart components into your pages.

## Table of Contents

1. [Installation](#installation)
2. [Basic Usage](#basic-usage)
3. [Common Patterns](#common-patterns)
4. [Real Data Integration](#real-data-integration)
5. [Styling & Theming](#styling--theming)
6. [Performance Optimization](#performance-optimization)

## Installation

Dependencies are already installed. Verify:

```bash
cd /Users/sac/jotp/benchmark-site
npm list recharts
```

Expected output: `recharts@2.10.3`

## Basic Usage

### Step 1: Import Components

```tsx
import {
  ThroughputChart,
  LatencyChart,
  SparklineCard
} from '@/components/charts';
```

### Step 2: Prepare Data

```tsx
const throughputData = [
  { name: 'Config A', disabled: 85000, enabled: 92000 },
  { name: 'Config B', disabled: 95000, enabled: 98000 }
];
```

### Step 3: Render Chart

```tsx
<ThroughputChart
  data={throughputData}
  title="Throughput Comparison"
  height={300}
/>
```

## Common Patterns

### Pattern 1: Dashboard with Sparklines

```tsx
import { SparklineCard } from '@/components/charts';
import { generateTimeSeriesData } from '@/lib/chart-utils';

export function Dashboard() {
  const [metrics, setMetrics] = useState({
    throughput: { current: 95234, change: 5.2, history: generateTimeSeriesData(95000, 5000, 20) },
    latency: { current: 2.1, change: -3.1, history: generateTimeSeriesData(2.1, 0.5, 20) },
    cpu: { current: 45, change: 0.8, history: generateTimeSeriesData(45, 10, 20) }
  });

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
      <SparklineCard
        title="Throughput"
        value={`${metrics.throughput.current.toLocaleString()} ops/s`}
        change={metrics.throughput.change}
        data={metrics.throughput.history}
        color="#3b82f6"
      />
      <SparklineCard
        title="Latency"
        value={`${metrics.latency.current}ms`}
        change={metrics.latency.change}
        data={metrics.latency.history}
        color="#10b981"
      />
      <SparklineCard
        title="CPU Usage"
        value={`${metrics.cpu.current}%`}
        change={metrics.cpu.change}
        data={metrics.cpu.history}
        color="#f59e0b"
      />
    </div>
  );
}
```

### Pattern 2: Multi-Chart Analysis Page

```tsx
import {
  ThroughputChart,
  LatencyChart,
  TimeseriesChart,
  ComparisonChart
} from '@/components/charts';

export function BenchmarkAnalysis({ results }: { results: BenchmarkResults }) {
  return (
    <div className="space-y-8">
      {/* Top metrics */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
        <ThroughputChart
          data={results.throughput}
          title="Throughput Comparison"
          height={300}
        />
        <LatencyChart
          data={results.latency}
          title="Latency Distribution"
          height={300}
        />
      </div>

      {/* Time series */}
      <TimeseriesChart
        data={results.timeseries}
        metrics={['throughput', 'latency', 'cpu']}
        title="Performance Over Time"
        chartType="area"
        height={400}
      />

      {/* System comparison */}
      <ComparisonChart
        data={results.comparison}
        systems={['JOTP', 'Akka', 'Erlang/OTP']}
        title="System Comparison"
        height={400}
      />
    </div>
  );
}
```

### Pattern 3: Interactive Capacity Planner

```tsx
'use client';

import { useState } from 'react';
import { CapacityChart } from '@/components/charts';

export function CapacityPlanner() {
  const [selectedMetric, setSelectedMetric] = useState<'throughput' | 'cpu' | 'memory'>('throughput');

  const datasets = [
    {
      name: 'Small Instance',
      data: [
        { load: 100, throughput: 50000, cpu: 45, memory: 1024 },
        { load: 500, throughput: 80000, cpu: 78, memory: 2048 }
      ]
    },
    {
      name: 'Large Instance',
      data: [
        { load: 100, throughput: 60000, cpu: 25, memory: 1024 },
        { load: 500, throughput: 120000, cpu: 42, memory: 2048 }
      ]
    }
  ];

  return (
    <div>
      <div className="flex gap-2 mb-4">
        {['throughput', 'cpu', 'memory'].map(metric => (
          <button
            key={metric}
            onClick={() => setSelectedMetric(metric as any)}
            className={`px-4 py-2 rounded ${
              selectedMetric === metric ? 'bg-blue-600 text-white' : 'bg-gray-200'
            }`}
          >
            {metric.toUpperCase()}
          </button>
        ))}
      </div>
      <CapacityChart
        datasets={datasets}
        title="Capacity Planning"
        height={400}
      />
    </div>
  );
}
```

## Real Data Integration

### Fetching Data from API

```tsx
'use client';

import { useEffect, useState } from 'react';
import { ThroughputChart } from '@/components/charts';

export function BenchmarkResults() {
  const [data, setData] = useState<ThroughputData[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    async function fetchData() {
      try {
        const response = await fetch('/api/benchmarks/throughput');
        const results = await response.json();
        setData(results);
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Unknown error');
      } finally {
        setLoading(false);
      }
    }

    fetchData();
  }, []);

  if (loading) return <div>Loading...</div>;
  if (error) return <div>Error: {error}</div>;

  return <ThroughputChart data={data} title="Benchmark Results" height={300} />;
}
```

### Real-time Data Updates

```tsx
'use client';

import { useEffect, useState } from 'react';
import { TimeseriesChart } from '@/components/charts';

export function LiveMetrics() {
  const [data, setData] = useState<TimeSeriesData[]>([]);

  useEffect(() => {
    const ws = new WebSocket('ws://localhost:8080/metrics');

    ws.onmessage = (event) => {
      const metric = JSON.parse(event.data);
      setData(prev => [...prev.slice(-50), metric]); // Keep last 50 points
    };

    return () => ws.close();
  }, []);

  return (
    <TimeseriesChart
      data={data}
      metrics={['throughput', 'latency', 'cpu']}
      title="Live Metrics"
      height={300}
    />
  );
}
```

### Server-Side Rendering

```tsx
import { ThroughputChart } from '@/components/charts';
import { getBenchmarkResults } from '@/lib/benchmark-service';

export default async function ServerChartPage() {
  const results = await getBenchmarkResults();

  return (
    <ThroughputChart
      data={results.throughput}
      title="Benchmark Results"
      height={300}
    />
  );
}
```

## Styling & Theming

### Custom Colors

```tsx
import { getColor } from '@/lib/chart-themes';

<ThroughputChart
  data={data}
  title="Custom Colors"
  className="shadow-lg rounded-lg"
/>
```

### Dark Mode Support

```tsx
'use client';

import { useTheme } from 'next-themes';
import { ThroughputChart } from '@/components/charts';

export function ThemedChart({ data }: { data: ThroughputData[] }) {
  const { theme } = useTheme();
  const isDark = theme === 'dark';

  return (
    <div className={isDark ? 'bg-gray-800' : 'bg-white'}>
      <ThroughputChart
        data={data}
        title="Themed Chart"
        height={300}
        className={isDark ? 'text-white' : 'text-gray-900'}
      />
    </div>
  );
}
```

### Custom Container Styling

```tsx
<div className="bg-gradient-to-br from-blue-50 to-indigo-100 rounded-xl shadow-2xl p-8">
  <ThroughputChart
    data={data}
    title="Styled Container"
    height={400}
  />
</div>
```

## Performance Optimization

### Memoizing Data

```tsx
'use client';

import { useMemo } from 'react';
import { TimeseriesChart } from '@/components/charts';

export function OptimizedChart({ rawData }: { rawData: MetricData[] }) {
  const processedData = useMemo(() => {
    return rawData.map(item => ({
      timestamp: item.timestamp,
      throughput: item.value * 1000,
      latency: item.duration,
      cpu: item.cpuPercent
    }));
  }, [rawData]);

  return <TimeseriesChart data={processedData} metrics={['throughput', 'latency', 'cpu']} />;
}
```

### Lazy Loading Charts

```tsx
'use client';

import dynamic from 'next/dynamic';

const HeavyChart = dynamic(() => import('@/components/charts/CapacityChart'), {
  loading: () => <div>Loading chart...</div>,
  ssr: false
});

export function Page() {
  return <HeavyChart datasets={data} title="Lazy Loaded" height={400} />;
}
```

### Debouncing Real-time Updates

```tsx
'use client';

import { useEffect, useState } from 'react';
import { useDeferredValue } from 'react';

export function RealtimeChart() {
  const [rawData, setRawData] = useState<TimeSeriesData[]>([]);
  const deferredData = useDeferredValue(rawData);

  useEffect(() => {
    const interval = setInterval(() => {
      // Add new data point
      setRawData(prev => [...prev.slice(-100), generatePoint()]);
    }, 1000);

    return () => clearInterval(interval);
  }, []);

  return <TimeseriesChart data={deferredData} metrics={['value']} />;
}
```

## Testing

### Unit Test Example

```tsx
import { render, screen } from '@testing-library/react';
import { ThroughputChart } from '@/components/charts';

describe('ThroughputChart', () => {
  const mockData = [
    { name: 'Test', disabled: 85000, enabled: 92000 }
  ];

  it('renders chart with data', () => {
    render(<ThroughputChart data={mockData} title="Test" height={300} />);
    expect(screen.getByText('Test')).toBeInTheDocument();
  });

  it('shows target line when enabled', () => {
    const { container } = render(
      <ThroughputChart
        data={mockData}
        showTarget={true}
        targetValue={100000}
        height={300}
      />
    );
    expect(container.textContent).toContain('Target');
  });
});
```

## Troubleshooting

### Chart Not Rendering

**Problem**: Chart container has zero height

**Solution**:
```tsx
// Explicitly set height
<ThroughputChart data={data} height={400} />

// Or use container with defined height
<div style={{ height: '400px' }}>
  <ThroughputChart data={data} />
</div>
```

### Tooltip Not Showing

**Problem**: CSS z-index conflict

**Solution**:
```tsx
<ThroughputChart
  data={data}
  className="relative z-10"  // Ensure container has stacking context
/>
```

### Data Not Updating

**Problem**: Missing React key or memoization

**Solution**:
```tsx
// Use useMemo for data transformations
const processedData = useMemo(() => transform(rawData), [rawData]);

// Add key prop
<ThroughputChart key={dataVersion} data={processedData} />
```

## Best Practices

1. **Always provide data arrays** (even if empty) to prevent crashes
2. **Memoize large datasets** to prevent unnecessary re-renders
3. **Use appropriate chart types** for your data (bar vs line vs radar)
4. **Set explicit heights** for containers to ensure proper rendering
5. **Handle loading states** when fetching data asynchronously
6. **Provide accessible labels** for screen readers
7. **Test with realistic data volumes** to ensure performance

## Additional Resources

- [Full Documentation](/benchmark-site/CHART-LIBRARY.md)
- [Component Examples](/benchmark-site/charts/examples)
- [Recharts Documentation](https://recharts.org/)
- [Data Generator](/benchmark-site/lib/benchmark-data-generator.ts)
