# JOTP Benchmark Analysis Website

Next.js 14 website displaying comprehensive benchmark analysis for JOTP (Java 26 OTP) fault-tolerance primitives.

## Features

- **Throughput Analysis**: Operations per second across configurations
- **Hot Path Latency**: Microsecond-level breakdown with regression detection
- **Capacity Planning**: Resource utilization and SLA compliance
- **Precision Benchmarks**: JMH nanosecond-precision measurements
- **Comparison**: Cross-run and framework comparisons
- **Regression Detection**: Automated performance change tracking

## Tech Stack

- **Next.js 14**: App Router with React Server Components
- **TypeScript**: Type-safe development
- **Tailwind CSS**: Utility-first styling
- **Recharts**: Declarative charting library
- **Lucide React**: Icon library

## Getting Started

### Prerequisites

- Node.js 18+ 
- npm or yarn

### Installation

```bash
# Install dependencies
npm install
# or
yarn install
```

### Development

```bash
# Run development server
npm run dev
# or
yarn dev
```

Open [http://localhost:3000](http://localhost:3000) in your browser.

### Production Build

```bash
# Build for production
npm run build

# Start production server
npm start
# or
yarn start
```

## Project Structure

```
benchmark-site/
├── app/
│   ├── analysis/
│   │   ├── throughput/page.tsx    # Throughput analysis
│   │   ├── hot-path/page.tsx      # Latency breakdown
│   │   ├── capacity/page.tsx      # Capacity planning
│   │   ├── precision/page.tsx     # JMH benchmarks
│   │   ├── comparison/page.tsx    # Cross-run comparison
│   │   ├── regression/page.tsx    # Regression detection
│   │   └── layout.tsx             # Analysis layout
│   ├── globals.css                # Global styles
│   ├── layout.tsx                 # Root layout
│   └── page.tsx                   # Home page
├── components/
│   ├── analysis/
│   │   ├── analysis-section.tsx   # Reusable section
│   │   ├── metric-table.tsx       # Metrics table
│   │   ├── claim-validation.tsx   # Pass/fail indicator
│   │   ├── analysis-nav.tsx       # Navigation sidebar
│   │   └── benchmark-chart.tsx    # Chart wrapper
│   └── ui/                        # UI components
├── lib/
│   └── utils.ts                   # Utility functions
└── public/                        # Static assets
```

## Benchmark Data

All benchmark data is sourced from JMH (Java Microbenchmark Harness) runs comparing:
- **JOTP**: Java 26 OTP primitives (virtual threads, platform threads)
- **Erlang/OTP 27**: BEAM VM benchmarks
- **Akka 2.8**: Actor-based fault tolerance

### Test Configuration

- **Messages**: 10 million per configuration
- **Warm-up**: 100 million operations
- **JMH forks**: 5
- **Iterations**: 20
- **JVM**: OpenJDK 26 (64-bit)

## Key Findings

### Throughput
- JOTP (virtual threads): 238K ops/sec
- Erlang/OTP: 245K ops/sec
- **Gap: 2.86%** (within acceptable range)

### Hot Path Latency
- Message dispatch: 89ns (virtual threads)
- Supervisor restart: 2.3μs
- State machine transition: 234ns

### Capacity Planning
- 99.97% availability on 4 vCPU
- 2.1KB memory per process
- Linear scaling to 16 vCPU

### SLA Compliance
- P99 latency &lt; 100ms: 98.7%
- Availability: 99.97%
- Error rate: 0.03%

## License

MIT License - see LICENSE file for details
