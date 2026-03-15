# Chart Library Documentation

Complete guide to using the JOTP benchmark site chart components.

## Overview

The chart library provides React components built on [Recharts](https://recharts.org/) for visualizing performance metrics. All components are fully responsive, accessible, and themeable.

## Installation

```bash
npm install recharts
```

## Table of Contents

- [Chart Types](#chart-types)
- [Usage Examples](#usage-examples)
- [Styling & Themes](#styling--themes)
- [Utilities](#utilities)

## Chart Types

### 1. ThroughputChart

Bar chart comparing operations per second across different configurations.

**Features:**
- Multi-bar comparison (disabled, enabled, with subscribers)
- Target threshold line
- Auto-formatted labels (ops/s)
- Custom tooltip with detailed values

**Usage:**

```tsx
import { ThroughputChart } from '@/components/charts';

<ThroughputChart
  data={[
    {
      name: 'Config A',
      disabled: 85000,
      enabled: 92000,
      withSubscribers: 88000
    },
    {
      name: 'Config B',
      disabled: 95000,
      enabled: 98000,
      withSubscribers: 96000
    }
  ]}
  title="Throughput Comparison"
  showTarget={true}
  targetValue={100000}
  height={300}
/>
```

**Props:**

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `data` | `ThroughputData[]` | required | Array of throughput data points |
| `title` | `string` | - | Chart title |
| `showTarget` | `boolean` | `true` | Show target threshold line |
| `targetValue` | `number` | `100000` | Target throughput value |
| `height` | `number` | `300` | Chart height in pixels |
| `className` | `string` | `''` | Additional CSS classes |

---

### 2. LatencyChart

Bar chart showing latency percentiles (P50, P95, P99, P999).

**Features:**
- Percentile distribution visualization
- SLA threshold line
- Millisecond/microsecond auto-formatting
- Color-coded percentiles

**Usage:**

```tsx
import { LatencyChart } from '@/components/charts';

<LatencyChart
  data={[
    {
      name: 'System A',
      p50: 2.1,
      p95: 5.8,
      p99: 8.2,
      p999: 12.1
    },
    {
      name: 'System B',
      p50: 1.8,
      p95: 4.2,
      p99: 6.1,
      p999: 9.5
    }
  ]}
  title="Latency Distribution"
  showThreshold={true}
  thresholdValue={10}
  height={300}
/>
```

**Props:**

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `data` | `LatencyData[]` | required | Array of latency data points |
| `title` | `string` | - | Chart title |
| `showThreshold` | `boolean` | `true` | Show SLA threshold line |
| `thresholdValue` | `number` | `10` | SLA threshold in ms |
| `height` | `number` | `300` | Chart height in pixels |
| `className` | `string` | `''` | Additional CSS classes |

---

### 3. CapacityChart

Multi-line chart comparing system capacity across different instance profiles.

**Features:**
- Switch between CPU, memory, and throughput views
- Compare multiple instance sizes
- Interactive metric selector
- Auto-scaled axes

**Usage:**

```tsx
import { CapacityChart } from '@/components/charts';

<CapacityChart
  datasets={[
    {
      name: 'Small Instance',
      data: [
        { load: 100, throughput: 50000, cpu: 45, memory: 1024 },
        { load: 500, throughput: 80000, cpu: 78, memory: 2048 },
        { load: 1000, throughput: 95000, cpu: 95, memory: 3072 }
      ]
    },
    {
      name: 'Large Instance',
      data: [
        { load: 100, throughput: 60000, cpu: 25, memory: 1024 },
        { load: 500, throughput: 120000, cpu: 42, memory: 2048 },
        { load: 1000, throughput: 180000, cpu: 65, memory: 3072 }
      ]
    }
  ]}
  title="Capacity Planning"
  height={400}
/>
```

**Props:**

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `datasets` | `Array<{name: string, data: CapacityData[], color?: string}>` | required | Array of dataset configurations |
| `title` | `string` | - | Chart title |
| `height` | `number` | `400` | Chart height in pixels |
| `className` | `string` | `''` | Additional CSS classes |

---

### 4. TimeseriesChart

Time-series line/area chart for performance trends over time.

**Features:**
- Line or area chart modes
- Toggle metrics on/off
- Auto-formatted timestamps
- Smooth animations

**Usage:**

```tsx
import { TimeseriesChart } from '@/components/charts';

<TimeseriesChart
  data={[
    { timestamp: '2024-01-01T00:00:00Z', throughput: 85000, latency: 2.5, cpu: 45 },
    { timestamp: '2024-01-01T01:00:00Z', throughput: 92000, latency: 2.1, cpu: 52 },
    { timestamp: '2024-01-01T02:00:00Z', throughput: 88000, latency: 2.8, cpu: 48 }
  ]}
  metrics={['throughput', 'latency', 'cpu']}
  title="Performance Trends"
  chartType="line"
  height={300}
/>
```

**Props:**

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `data` | `TimeSeriesData[]` | required | Array of time-series data points |
| `metrics` | `string[]` | required | Available metric keys |
| `title` | `string` | - | Chart title |
| `chartType` | `'line' \| 'area'` | `'line'` | Chart rendering style |
| `height` | `number` | `300` | Chart height in pixels |
| `className` | `string` | `''` | Additional CSS classes |

---

### 5. ComparisonChart

Radar chart for multi-dimensional system comparison.

**Features:**
- Compare multiple systems across metrics
- Normalize metrics to 0-100 scale
- Interactive legend
- Customizable colors

**Usage:**

```tsx
import { ComparisonChart, generateSystemComparison } from '@/components/charts';

<ComparisonChart
  data={generateSystemComparison(
    ['Throughput', 'Latency', 'CPU Efficiency', 'Memory', 'Fault Tolerance'],
    [
      { name: 'JOTP', values: [95, 88, 92, 85, 98] },
      { name: 'Akka', values: [82, 90, 78, 88, 85] },
      { name: 'Erlang/OTP', values: [88, 95, 80, 90, 95] }
    ]
  )}
  systems={['JOTP', 'Akka', 'Erlang/OTP']}
  title="System Comparison"
  height={400}
/>
```

**Props:**

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `data` | `ComparisonData[]` | required | Array of comparison data points |
| `systems` | `string[]` | required | System names to display |
| `title` | `string` | - | Chart title |
| `height` | `number` | `400` | Chart height in pixels |
| `className` | `string` | `''` | Additional CSS classes |

---

### 6. Sparkline

Mini trend chart for displaying performance at a glance.

**Features:**
- Compact size (default 100x40px)
- Line or area rendering
- Optional tooltip
- Minimal styling

**Usage:**

```tsx
import { Sparkline } from '@/components/charts';

// Basic sparkline
<Sparkline
  data={[
    { timestamp: '2024-01-01T00:00:00Z', value: 85 },
    { timestamp: '2024-01-01T01:00:00Z', value: 92 },
    { timestamp: '2024-01-01T02:00:00Z', value: 88 }
  ]}
  type="line"
  color="#3b82f6"
  width={120}
  height={40}
/>
```

**SparklineCard** - Pre-styled card component:

```tsx
import { SparklineCard } from '@/components/charts';

<SparklineCard
  title="Current Throughput"
  value="92,450 ops/s"
  change={5.2}
  data={[
    { timestamp: '2024-01-01T00:00:00Z', value: 85 },
    { timestamp: '2024-01-01T01:00:00Z', value: 92 },
    { timestamp: '2024-01-01T02:00:00Z', value: 88 }
  ]}
  type="area"
  color="#10b981"
/>
```

**Sparkline Props:**

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `data` | `SparklineData[]` | required | Array of time-series data |
| `type` | `'line' \| 'area'` | `'line'` | Chart rendering style |
| `color` | `string` | `'#3b82f6'` | Line/area color |
| `width` | `number` | `100` | Width in pixels |
| `height` | `number` | `40` | Height in pixels |
| `showTooltip` | `boolean` | `false` | Enable hover tooltip |
| `showDots` | `boolean` | `false` | Show data points |
| `strokeWidth` | `number` | `2` | Line thickness |
| `valueFormatter` | `(value: number) => string` | - | Custom value formatter |

---

## Styling & Themes

### Theme Configuration

Chart themes are defined in `lib/chart-themes.ts`:

```typescript
import { lightTheme, darkTheme, getColor } from '@/lib/chart-themes';

// Use predefined theme colors
const primaryColor = lightTheme.colors.primary;
const gridColor = lightTheme.gridColor;

// Get color by index
const blue = getColor(0);  // #3b82f6
const green = getColor(1); // #10b981
```

### Custom Styling

All chart components accept a `className` prop for additional styling:

```tsx
<ThroughputChart
  data={data}
  className="shadow-lg rounded-lg bg-white p-6"
/>
```

### Chart Sizes

Use predefined size presets from `chart-themes.ts`:

```typescript
import { chartSizes } from '@/lib/chart-themes';

chartSizes.small  // { width: 300, height: 200 }
chartSizes.medium // { width: 500, height: 300 }
chartSizes.large  // { width: 800, height: 400 }
chartSizes.full   // { width: '100%', height: 400 }
```

---

## Utilities

### Formatters

Located in `lib/chart-utils.ts`:

```typescript
import {
  formatNumber,
  formatThroughput,
  formatLatency,
  formatPercent,
  formatMemory
} from '@/lib/chart-utils';

formatNumber(1234567);        // "1.2M"
formatThroughput(95000);      // "95.0K ops/s"
formatLatency(0.002);         // "2.00ms"
formatLatency(1500);          // "1.50s"
formatPercent(85.67);         // "85.7%"
formatMemory(1073741824);     // "1.0 GB"
```

### Statistical Functions

```typescript
import {
  calculatePercentiles,
  smoothData,
  calculateGrowthRate,
  normalizeToScale
} from '@/lib/chart-utils';

// Calculate percentiles from raw data
const values = [1.2, 2.5, 3.1, 4.8, 5.2, 6.7, 8.1];
const percentiles = calculatePercentiles(values);
// Returns: { p50: 4.8, p95: 8.1, p99: 8.1, p999: 8.1, min: 1.2, max: 8.1, mean: 4.51 }

// Smooth noisy data with moving average
const smooth = smoothData(dataPoints, 5);

// Calculate growth rate
const growth = calculateGrowthRate(100000, 115000); // 15.0%

// Normalize to 0-100 scale
const normalized = normalizeToScale([10, 50, 100], 0, 100); // [10, 50, 100]
```

### Data Generation

```typescript
import {
  generateTimeSeriesData,
  generateRadarData
} from '@/lib/chart-utils';

// Generate synthetic time series data
const timeSeries = generateTimeSeriesData(
  95000,    // base value
  5000,     // variance
  20,       // number of points
  'up'      // trend: 'up' | 'down' | 'stable'
);

// Generate radar chart data
const radar = generateRadarData(
  ['Metric 1', 'Metric 2', 'Metric 3'],
  [
    { name: 'System A', values: [80, 90, 85] },
    { name: 'System B', values: [70, 85, 90] }
  ]
);
```

---

## Complete Examples

### Dashboard Layout

```tsx
import {
  ThroughputChart,
  LatencyChart,
  CapacityChart,
  TimeseriesChart,
  ComparisonChart,
  SparklineCard
} from '@/components/charts';

export function BenchmarkDashboard() {
  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 p-6">
      {/* Summary cards with sparklines */}
      <div className="lg:col-span-2 grid grid-cols-1 md:grid-cols-3 gap-4">
        <SparklineCard
          title="Throughput"
          value="95,234 ops/s"
          change={5.2}
          data={throughputHistory}
          color="#3b82f6"
        />
        <SparklineCard
          title="Latency"
          value="2.1ms"
          change={-3.1}
          data={latencyHistory}
          color="#10b981"
        />
        <SparklineCard
          title="CPU Usage"
          value="45%"
          change={0.8}
          data={cpuHistory}
          color="#f59e0b"
        />
      </div>

      {/* Main charts */}
      <ThroughputChart
        data={throughputData}
        title="Throughput Comparison"
        height={300}
      />

      <LatencyChart
        data={latencyData}
        title="Latency Distribution"
        height={300}
      />

      <TimeseriesChart
        data={timeseriesData}
        metrics={['throughput', 'latency', 'cpu']}
        title="Performance Over Time"
        chartType="area"
        height={300}
      />

      <ComparisonChart
        data={comparisonData}
        systems={['JOTP', 'Akka', 'Erlang/OTP']}
        title="System Comparison"
        height={300}
      />

      {/* Full-width capacity chart */}
      <div className="lg:col-span-2">
        <CapacityChart
          datasets={capacityDatasets}
          title="Capacity Planning"
          height={400}
        />
      </div>
    </div>
  );
}
```

---

## Best Practices

1. **Responsive Design**: Always wrap charts in appropriate containers
2. **Data Validation**: Ensure data arrays are non-empty before rendering
3. **Color Consistency**: Use the predefined color palette for consistency
4. **Tooltips**: Enable tooltips for interactive charts, disable for sparklines
5. **Loading States**: Show skeleton loaders while data is being fetched
6. **Error Handling**: Display error messages when data is invalid

---

## TypeScript Support

All components include full TypeScript definitions. Import types alongside components:

```typescript
import type {
  ThroughputData,
  LatencyData,
  CapacityData,
  TimeSeriesData,
  ComparisonData,
  SparklineData
} from '@/components/charts';
```

---

## Accessibility

All chart components include:
- ARIA labels for screen readers
- Keyboard navigation support
- High-contrast color options
- Responsive text sizing

---

## Performance Tips

1. **Memoize Data**: Use `useMemo` for large datasets
2. **Limit Points**: Show max 100 data points for smooth animations
3. **Lazy Load**: Use code splitting for chart-heavy pages
4. **Debounce Updates**: Throttle real-time data updates to 1-2 seconds

---

## Troubleshooting

**Chart not rendering?**
- Check that data array is not empty
- Verify all required props are provided
- Ensure container has defined height

**Tooltip not showing?**
- Ensure `showTooltip` prop is `true`
- Check CSS z-index conflicts
- Verify parent container has `overflow: visible`

**Colors not displaying?**
- Use predefined color palette
- Check CSS specificity
- Verify color strings are valid hex codes

---

## Additional Resources

- [Recharts Documentation](https://recharts.org/)
- [Chart Examples](/benchmark-site/app/charts/page.tsx)
- [Design System](/benchmark-site/app/design-system/page.tsx)
