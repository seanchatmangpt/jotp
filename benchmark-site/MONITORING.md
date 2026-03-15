# JOTP Benchmark Monitoring Guide

This guide explains how to use the real-time monitoring infrastructure for JOTP benchmarks.

## Overview

The benchmark monitoring system provides:
- **Real-time benchmark execution** with live progress updates
- **Server-Sent Events (SSE)** for streaming benchmark data
- **Historical analysis** of past benchmark runs
- **System metrics** dashboard showing CPU, memory, and load
- **Live log streaming** with syntax highlighting

## Getting Started

### Installation

```bash
cd benchmark-site
npm install
```

### Development

```bash
npm run dev
```

The monitoring dashboard will be available at `http://localhost:3000`

### Production Build

```bash
npm run build
npm start
```

## Pages

### 1. Home Page (`/`)

Landing page with navigation to:
- Monitoring Dashboard
- Live Benchmarks
- Historical Data

### 2. Monitoring Dashboard (`/monitoring`)

Main dashboard featuring:
- **Status cards**: Active benchmarks, system load, memory/CPU usage
- **Active benchmarks**: Real-time progress and metrics
- **Recent completions**: Last 4 completed benchmarks

**Auto-refresh**: Updates every 5 seconds

### 3. Live Benchmarks (`/monitoring/live`)

Real-time benchmark execution with:
- **Benchmark selector**: Choose active benchmark to monitor
- **Progress bar**: Live progress updates
- **Metrics display**: Real-time throughput, latency, memory, CPU
- **Live logs**: Auto-scrolling log output with SSE

**Real-time updates**: Uses Server-Sent Events for instant updates

### 4. Historical Data (`/monitoring/history`)

Browse past benchmark runs:
- **Filter by status**: All, Completed, Failed
- **Detailed metrics**: Per-benchmark performance data
- **Timestamps**: Start/end times and duration

## API Routes

### GET `/api/benchmarks`

Returns all benchmarks.

**Response:**
```json
[
  {
    "id": "baseline",
    "name": "Baseline Performance",
    "category": "Core Primitives",
    "status": "completed",
    "progress": 100,
    "startTime": "2026-03-14T10:00:00Z",
    "endTime": "2026-03-14T10:01:00Z",
    "duration": 57,
    "metrics": {
      "throughput": 425000000,
      "latency": { "p50": 2.35, "p95": 3.5, "p99": 5.2, "mean": 2.5 },
      "memory": { "allocated": 5, "used": 2 },
      "cpu": 45
    }
  }
]
```

### GET `/api/benchmarks/[id]`

Returns a single benchmark by ID.

**Response:** Single benchmark object (same structure as above)

### GET `/api/benchmarks/stream`

Server-Sent Events stream for real-time updates.

**Event types:**
- `connected`: Initial connection established
- `progress`: Benchmark progress update
- `log`: New log entry
- `complete`: Benchmark finished
- `error`: Benchmark failed

**Example event:**
```
data: {"benchmarkId":"benchmark-1","type":"progress","data":{"progress":50,"message":"Running iteration 5..."}}

```

### GET `/api/metrics`

Returns aggregated system metrics.

**Response:**
```json
{
  "activeBenchmarks": 1,
  "totalBenchmarks": 15,
  "systemLoad": 2.5,
  "memoryUsage": 65,
  "cpuUsage": 78
}
```

## Running Benchmarks with Live Monitoring

### Simulation Mode (Current)

The current implementation simulates benchmark execution for demonstration purposes:

1. Start the dev server: `npm run dev`
2. Navigate to `http://localhost:3000/monitoring/live`
3. The system automatically simulates:
   - Progress updates every 1 second
   - Log entries every 2.5 seconds
   - Completion after 10 seconds

### Integration with Actual Benchmarks

To integrate with real JMH benchmark execution:

1. **Modify `/lib/benchmark-streamer.ts`**:
   ```typescript
   // Replace simulateUpdates() with actual JMH integration
   import { exec } from 'child_process';

   async function runBenchmark(benchmarkId: string) {
     const benchmark = exec('mvnd test -Dtest=' + benchmarkId);
     
     benchmark.stdout.on('data', (data) => {
       this.broadcast({
         benchmarkId,
         type: 'log',
         data: { level: 'info', message: data.toString() }
       });
     });

     benchmark.on('close', (code) => {
       this.broadcast({
         benchmarkId,
         type: code === 0 ? 'complete' : 'error',
         data: { exitCode: code }
       });
     });
   }
   ```

2. **Parse JMH output**: Extract metrics from JMH JSON output
3. **Update `/api/benchmarks`**: Store results in database or filesystem

### Example: Running Baseline Benchmark

```bash
# From the JOTP root directory
cd /Users/sac/jotp
mvnd test -Dtest=*Benchmark -Djmh.format=json
```

The results can be parsed and stored in `benchmark-results/`, then consumed by the monitoring API.

## Data Types

### Benchmark

```typescript
interface Benchmark {
  id: string;
  name: string;
  category: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  progress: number;
  startTime?: string;
  endTime?: string;
  duration?: number;
  metrics?: BenchmarkMetrics;
  logs?: LogEntry[];
}
```

### BenchmarkMetrics

```typescript
interface BenchmarkMetrics {
  throughput: number;
  latency: {
    p50: number;
    p95: number;
    p99: number;
    mean: number;
  };
  memory: {
    allocated: number;
    used: number;
  };
  cpu: number;
}
```

### LogEntry

```typescript
interface LogEntry {
  timestamp: string;
  level: 'info' | 'warn' | 'error' | 'debug';
  message: string;
  data?: any;
}
```

## Customization

### Adding New Benchmarks

1. Update `/lib/mock-data.ts` with new benchmark definitions
2. Or integrate with actual JMH benchmark execution

### Modifying Refresh Intervals

- **Dashboard polling**: `/app/monitoring/page.tsx` (line 28)
- **Metrics polling**: `/components/monitoring/status-dashboard.tsx` (line 22)
- **Live updates**: SSE streams in real-time (no polling)

### Styling

The app uses Tailwind CSS. Modify `tailwind.config.js` for custom themes.

## Troubleshooting

### SSE Connection Issues

**Problem**: Live updates not working

**Solutions**:
1. Check browser console for SSE errors
2. Verify `/api/benchmarks/stream` is accessible
3. Ensure no proxy is interfering with SSE

### Data Not Updating

**Problem**: Dashboard shows stale data

**Solutions**:
1. Check browser network tab for failed API requests
2. Verify API routes are returning data
3. Check console for JavaScript errors

### Build Errors

**Problem**: `npm run build` fails

**Solutions**:
1. Clear `.next` directory: `rm -rf .next`
2. Reinstall dependencies: `rm -rf node_modules && npm install`
3. Check TypeScript errors: `npm run lint`

## Performance Considerations

- **SSE connections**: Each client maintains a persistent connection
- **Polling overhead**: Dashboard polls every 5 seconds (configurable)
- **Memory usage**: Log history grows unbounded in current implementation
- **Recommendation**: Implement log pagination for production

## Future Enhancements

1. **Database integration**: Store benchmark results in PostgreSQL/TimescaleDB
2. **Authentication**: Add user accounts and access control
3. **Alerts**: Email/Slack notifications for failed benchmarks
4. **Export**: Download results as CSV/JSON
5. **Comparison**: Side-by-side benchmark comparison
6. **Charts**: Visualize trends with recharts (already installed)
7. **WebSocket fallback**: Alternative to SSE for better compatibility

## Support

For issues or questions:
1. Check this documentation
2. Review API responses in browser network tab
3. Examine console logs for errors
4. Verify JMH benchmarks run successfully independently
