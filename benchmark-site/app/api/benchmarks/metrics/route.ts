/**
 * Mock API endpoint for benchmark metrics
 * In production, this would stream real benchmark data
 */

import { NextRequest, NextResponse } from 'next/server';
import { FlowMetrics } from '@/lib/state-machines/flow-machine';

// Simulated benchmark data
const mockMetrics: Record<string, FlowMetrics> = {
  'proc': {
    timestamp: Date.now(),
    throughput: 125000,
    latency: { p50: 0.8, p95: 1.2, p99: 2.5 },
    errorRate: 0.01,
    memory: { used: 256 * 1024 * 1024, total: 1024 * 1024 * 1024 },
    cpu: 35.5
  },
  'supervisor': {
    timestamp: Date.now(),
    throughput: 98000,
    latency: { p50: 1.2, p95: 2.1, p99: 4.8 },
    errorRate: 0.05,
    memory: { used: 512 * 1024 * 1024, total: 2048 * 1024 * 1024 },
    cpu: 45.2
  },
  'statemachine': {
    timestamp: Date.now(),
    throughput: 87500,
    latency: { p50: 1.5, p95: 2.8, p99: 6.2 },
    errorRate: 0.02,
    memory: { used: 384 * 1024 * 1024, total: 1024 * 1024 * 1024 },
    cpu: 42.8
  },
  'crashrecovery': {
    timestamp: Date.now(),
    throughput: 75000,
    latency: { p50: 2.1, p95: 3.5, p99: 8.1 },
    errorRate: 0.08,
    memory: { used: 640 * 1024 * 1024, total: 2048 * 1024 * 1024 },
    cpu: 55.7
  },
  'parallel': {
    timestamp: Date.now(),
    throughput: 150000,
    latency: { p50: 0.5, p95: 0.9, p99: 1.8 },
    errorRate: 0.00,
    memory: { used: 128 * 1024 * 1024, total: 512 * 1024 * 1024 },
    cpu: 28.3
  }
};

// Simulate real-time updates with slight variations
function varyMetrics(base: FlowMetrics): FlowMetrics {
  const variation = () => 1 + (Math.random() - 0.5) * 0.1; // ±5% variation

  return {
    timestamp: Date.now(),
    throughput: Math.floor(base.throughput * variation()),
    latency: {
      p50: base.latency.p50 * variation(),
      p95: base.latency.p95 * variation(),
      p99: base.latency.p99 * variation()
    },
    errorRate: Math.max(0, base.errorRate * variation()),
    memory: {
      used: Math.floor(base.memory.used * variation()),
      total: base.memory.total
    },
    cpu: Math.max(0, Math.min(100, base.cpu * variation()))
  };
}

export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams;
  const nodeId = searchParams.get('node');

  // Simulate network delay
  await new Promise(resolve => setTimeout(resolve, 50));

  // Return specific node metrics or all
  if (nodeId) {
    const metrics = mockMetrics[nodeId];
    if (!metrics) {
      return NextResponse.json({ error: 'Node not found' }, { status: 404 });
    }
    return NextResponse.json(varyMetrics(metrics));
  }

  // Return all metrics with variations
  const allMetrics: Record<string, FlowMetrics> = {};
  for (const [id, base] of Object.entries(mockMetrics)) {
    allMetrics[id] = varyMetrics(base);
  }

  return NextResponse.json(allMetrics);
}

// SSE endpoint for real-time streaming
export async function GET_SSE(request: NextRequest) {
  const encoder = new TextEncoder();

  const stream = new ReadableStream({
    async start(controller) {
      let counter = 0;

      const sendEvent = (data: unknown) => {
        controller.enqueue(
          encoder.encode(`data: ${JSON.stringify(data)}\n\n`)
        );
      };

      // Send updates every second
      const interval = setInterval(() => {
        const allMetrics: Record<string, FlowMetrics> = {};
        for (const [id, base] of Object.entries(mockMetrics)) {
          allMetrics[id] = varyMetrics(base);
        }

        sendEvent({
          type: 'metrics',
          timestamp: Date.now(),
          data: allMetrics
        });

        counter++;
        if (counter > 60) {
          clearInterval(interval);
          controller.close();
        }
      }, 1000);

      request.signal.addEventListener('abort', () => {
        clearInterval(interval);
        controller.close();
      });
    }
  });

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive'
    }
  });
}
