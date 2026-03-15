import { BenchmarkUpdate } from './types';

export class BenchmarkStreamer {
  private clients: Set<ReadableStreamDefaultController> = new Set();

  subscribe(controller: ReadableStreamDefaultController) {
    this.clients.add(controller);
  }

  unsubscribe(controller: ReadableStreamDefaultController) {
    this.clients.delete(controller);
  }

  broadcast(update: BenchmarkUpdate) {
    const data = `data: ${JSON.stringify(update)}\n\n`;
    
    this.clients.forEach(controller => {
      try {
        controller.enqueue(new TextEncoder().encode(data));
      } catch (error) {
        console.error('Error sending update to client:', error);
        this.clients.delete(controller);
      }
    });
  }

  // Simulate real-time updates (replace with actual benchmark integration)
  simulateUpdates() {
    const benchmarkId = 'benchmark-1';
    
    // Progress updates
    let progress = 0;
    const progressInterval = setInterval(() => {
      progress += 10;
      this.broadcast({
        benchmarkId,
        type: 'progress',
        data: { progress, message: `Running iteration ${progress / 10}...` }
      });

      if (progress >= 100) {
        clearInterval(progressInterval);
        this.broadcast({
          benchmarkId,
          type: 'complete',
          data: {
            progress: 100,
            metrics: {
              throughput: 15000000,
              latency: { p50: 120, p95: 250, p99: 400, mean: 150 },
              memory: { allocated: 1024, used: 512 },
              cpu: 75
            }
          }
        });
      }
    }, 1000);

    // Log updates
    const logs = [
      { level: 'info', message: 'Starting benchmark execution...' },
      { level: 'info', message: 'Warmup phase: 10 iterations' },
      { level: 'debug', message: 'JVM warmed up, starting measurements' },
      { level: 'info', message: 'Measurement phase: 100 iterations' },
    ];

    logs.forEach((log, index) => {
      setTimeout(() => {
        this.broadcast({
          benchmarkId,
          type: 'log',
          data: { ...log, timestamp: new Date().toISOString() }
        });
      }, (index + 1) * 2500);
    });
  }
}

// Singleton instance
let streamer: BenchmarkStreamer | null = null;

export function getBenchmarkStreamer(): BenchmarkStreamer {
  if (!streamer) {
    streamer = new BenchmarkStreamer();
  }
  return streamer;
}
