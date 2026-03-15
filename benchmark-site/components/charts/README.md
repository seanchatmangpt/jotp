# Chart Components

A comprehensive chart library for the JOTP benchmark site, built on [Recharts](https://recharts.org/).

## Overview

This directory contains React components for visualizing performance metrics, system comparisons, and real-time benchmark data. All components are fully responsive, accessible, and themeable.

## Components

### Main Charts

- **ThroughputChart** - Bar chart comparing operations per second
- **LatencyChart** - Percentile distribution (P50, P95, P99, P999)
- **CapacityChart** - Multi-line capacity planning visualization
- **TimeseriesChart** - Time-series performance trends
- **ComparisonChart** - Radar chart for multi-system comparison

### Sparkline Components

- **Sparkline** - Mini trend chart (100x40px default)
- **SparklineWithLabel** - Sparkline with current value and change indicator
- **SparklineCard** - Pre-styled card component for dashboards

## Quick Start

```tsx
import { ThroughputChart } from '@/components/charts';

<ThroughputChart
  data={[
    { name: 'Config A', disabled: 85000, enabled: 92000 },
    { name: 'Config B', disabled: 95000, enabled: 98000 }
  ]}
  title="Throughput Comparison"
  height={300}
/>
```

## File Structure

```
components/charts/
├── index.ts                    # Component exports
├── throughput-chart.tsx        # Throughput visualization
├── latency-chart.tsx           # Latency percentiles
├── capacity-chart.tsx          # Capacity planning
├── timeseries-chart.tsx        # Time-series trends
├── comparison-chart.tsx        # System comparison (radar)
└── sparkline.tsx               # Mini trend charts

lib/
├── chart-utils.ts              # Formatters, statistical functions
├── chart-themes.ts             # Theme configuration
└── benchmark-data-generator.ts # Sample data generation
```

## Documentation

See [CHART-LIBRARY.md](/benchmark-site/CHART-LIBRARY.md) for complete documentation including:

- All component APIs
- Usage examples
- Styling and theming
- Utility functions
- Best practices

## Examples

Interactive examples are available at:

- **Chart Library Overview**: `/benchmark-site/charts`
- **Complete Examples**: `/benchmark-site/charts/examples`

## Data Generation

Generate realistic benchmark data using the included utilities:

```tsx
import { generateCompleteBenchmarkDataset } from '@/lib/benchmark-data-generator';

const data = generateCompleteBenchmarkDataset();
// Returns: { throughput, latency, capacity, timeseries, comparison }
```

## Features

- ✅ Fully responsive
- ✅ TypeScript with full type definitions
- ✅ Accessible (ARIA labels, keyboard navigation)
- ✅ Customizable themes
- ✅ Interactive tooltips
- ✅ Smooth animations
- ✅ Auto-formatting (throughput, latency, memory, percentages)
- ✅ Statistical utilities (percentiles, smoothing, normalization)

## Dependencies

- `recharts` ^2.10.3 - Chart library
- `react` ^19.2.4 - UI framework
- `next` ^16.1.6 - React framework

## Browser Support

All modern browsers supporting:
- ES2020+
- CSS Grid
- SVG (for chart rendering)

## Contributing

When adding new chart components:

1. Create component file in `components/charts/`
2. Export from `components/charts/index.ts`
3. Add TypeScript types
4. Include usage examples
5. Update documentation

## License

MIT
