'use client';

import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@radix-ui/themes';
import { Button } from '@radix-ui/themes';
import { Badge } from '@radix-ui/themes';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@radix-ui/themes';

interface BackpressureConfig {
  producerRate: number;
  consumerRate: number;
  queueCapacity: number;
  timeout: number;
}

interface BackpressureMetrics {
  produced: number;
  consumed: number;
  dropped: number;
  queueSize: number;
  backpressureEvents: number;
}

export default function BackpressureFlow() {
  const [config, setConfig] = useState<BackpressureConfig>({
    producerRate: 100,
    consumerRate: 150,
    queueCapacity: 20,
    timeout: 5000
  });

  const [metrics, setMetrics] = useState<BackpressureMetrics>({
    produced: 0,
    consumed: 0,
    dropped: 0,
    queueSize: 0,
    backpressureEvents: 0
  });

  const [isSimulating, setIsSimulating] = useState(false);
  const [queueItems, setQueueItems] = useState<number[]>([]);
  const [producerActive, setProducerActive] = useState(false);
  const [consumerActive, setConsumerActive] = useState(false);
  const [backpressureActive, setBackpressureActive] = useState(false);

  useEffect(() => {
    if (!isSimulating) return;

    const producerInterval = setInterval(() => {
      setProducerActive(true);

      setMetrics(prev => {
        const newQueueSize = queueItems.length;
        let shouldProduce = true;

        // Check if backpressure should be applied
        if (newQueueSize >= config.queueCapacity * 0.8) {
          setBackpressureActive(true);
          setMetrics(p => ({ ...p, backpressureEvents: p.backpressureEvents + 1 }));

          // Apply timeout-based backpressure
          if (Math.random() > 0.3) {
            shouldProduce = false;
          }
        } else {
          setBackpressureActive(false);
        }

        if (shouldProduce && newQueueSize < config.queueCapacity) {
          const newItem = Date.now();
          setQueueItems(prev => [...prev, newItem].slice(-config.queueCapacity));
          return { ...prev, produced: prev.produced + 1, queueSize: Math.min(newQueueSize + 1, config.queueCapacity) };
        } else if (!shouldProduce) {
          return { ...prev, dropped: prev.dropped + 1 };
        }

        return prev;
      });

      setTimeout(() => setProducerActive(false), 100);
    }, config.producerRate);

    const consumerInterval = setInterval(() => {
      setConsumerActive(true);

      setQueueItems(prev => {
        if (prev.length > 0) {
          setMetrics(p => ({ ...p, consumed: p.consumed + 1, queueSize: Math.max(p.queueSize - 1, 0) }));
          return prev.slice(1);
        }
        return prev;
      });

      setTimeout(() => setConsumerActive(false), 100);
    }, config.consumerRate);

    return () => {
      clearInterval(producerInterval);
      clearInterval(consumerInterval);
    };
  }, [isSimulating, config, queueItems.length]);

  const reset = () => {
    setQueueItems([]);
    setMetrics({
      produced: 0,
      consumed: 0,
      dropped: 0,
      queueSize: 0,
      backpressureEvents: 0
    });
    setBackpressureActive(false);
  };

  const queueUtilization = (metrics.queueSize / config.queueCapacity) * 100;
  const throughput = metrics.consumed / Math.max(metrics.produced, 1) * 100;

  const codeExample = `// Backpressure with Timeout
public final class BackpressureQueue<T> {
    private final BlockingQueue<T> queue;
    private final long timeoutMs;
    private final TimeUnit unit;

    public boolean offer(T item, long timeout, TimeUnit unit) {
        try {
            // Block producer if queue is full
            return queue.offer(item, timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public T poll(long timeout, TimeUnit unit) {
        try {
            // Wait for item with timeout
            return queue.poll(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

// Virtual Thread Producer
public void producer(BackpressureQueue<String> queue) {
    while (running) {
        if (!queue.offer(item, 5, TimeUnit.SECONDS)) {
            // Backpressure: slow down or drop
            Thread.sleep(1000); // Slow down
        }
    }
}`;

  return (
    <div className="space-y-6">
      {/* Controls */}
      <Card>
        <CardHeader>
          <CardTitle>Backpressure Pattern</CardTitle>
          <CardDescription>
            Flow control mechanism to prevent overload by managing producer-consumer imbalance
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex gap-4 items-center flex-wrap">
            <Button
              onClick={() => setIsSimulating(!isSimulating)}
              variant={isSimulating ? 'destructive' : 'default'}
            >
              {isSimulating ? 'Stop Simulation' : 'Start Simulation'}
            </Button>
            <Button onClick={reset} variant="ghost">
              Reset
            </Button>

            <div className="flex items-center gap-4 ml-auto">
              <div className="flex items-center gap-2">
                <label className="text-sm">Producer Rate:</label>
                <input
                  type="number"
                  value={config.producerRate}
                  onChange={(e) => setConfig(prev => ({ ...prev, producerRate: Number(e.target.value) }))}
                  className="w-24 px-3 py-1 border rounded"
                  min="50"
                  max="500"
                  step="50"
                />
                <span className="text-xs text-slate-500">ms</span>
              </div>
              <div className="flex items-center gap-2">
                <label className="text-sm">Consumer Rate:</label>
                <input
                  type="number"
                  value={config.consumerRate}
                  onChange={(e) => setConfig(prev => ({ ...prev, consumerRate: Number(e.target.value) }))}
                  className="w-24 px-3 py-1 border rounded"
                  min="50"
                  max="500"
                  step="50"
                />
                <span className="text-xs text-slate-500">ms</span>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Flow Visualization */}
      <Card>
        <CardHeader>
          <CardTitle>Flow Control Pipeline</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="relative h-64 bg-slate-50 dark:bg-slate-900 rounded-lg p-6 overflow-hidden">
            {/* Producer */}
            <div className="absolute left-8 top-1/2 -translate-y-1/2">
              <div className={`w-24 h-24 rounded-lg ${producerActive ? 'bg-blue-500 animate-pulse' : 'bg-blue-300'} flex items-center justify-center`}>
                <div className="text-white text-center">
                  <div className="text-3xl">🏭</div>
                  <div className="text-xs mt-1">Producer</div>
                </div>
              </div>
              <div className="text-xs text-center text-slate-600 dark:text-slate-400 mt-2">
                Rate: {config.producerRate}ms
              </div>
            </div>

            {/* Queue Buffer */}
            <div className="absolute left-1/2 top-1/2 -translate-y-1/2 -translate-x-1/2">
              <div className="w-48 h-32 bg-white dark:bg-slate-800 rounded-lg border-2 border-slate-300 dark:border-slate-600 p-2">
                <div className="text-xs text-slate-600 dark:text-slate-400 mb-1">
                  Queue: {metrics.queueSize} / {config.queueCapacity}
                </div>
                <div className="flex flex-wrap gap-1 h-20 overflow-hidden">
                  {Array.from({ length: config.queueCapacity }).map((_, i) => (
                    <div
                      key={i}
                      className={`w-4 h-4 rounded-sm ${i < queueItems.length ? 'bg-green-500' : 'bg-slate-200 dark:bg-slate-700'}`}
                    />
                  ))}
                </div>
                <div className="w-full bg-slate-200 dark:bg-slate-700 rounded-full h-2 mt-1">
                  <div
                    className={`h-2 rounded-full transition-all ${queueUtilization >= 90 ? 'bg-red-500' : queueUtilization >= 70 ? 'bg-yellow-500' : 'bg-green-500'}`}
                    style={{ width: `${queueUtilization}%` }}
                  />
                </div>
              </div>
              {backpressureActive && (
                <div className="absolute -top-2 -right-2">
                  <Badge variant="destructive" className="animate-pulse">
                    Backpressure
                  </Badge>
                </div>
              )}
            </div>

            {/* Consumer */}
            <div className="absolute right-8 top-1/2 -translate-y-1/2">
              <div className={`w-24 h-24 rounded-lg ${consumerActive ? 'bg-green-500 animate-pulse' : 'bg-green-300'} flex items-center justify-center`}>
                <div className="text-white text-center">
                  <div className="text-3xl">📦</div>
                  <div className="text-xs mt-1">Consumer</div>
                </div>
              </div>
              <div className="text-xs text-center text-slate-600 dark:text-slate-400 mt-2">
                Rate: {config.consumerRate}ms
              </div>
            </div>

            {/* Flow Arrows */}
            <svg className="absolute inset-0 w-full h-full pointer-events-none">
              <defs>
                <marker id="arrow-blue" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
                  <polygon points="0 0, 10 3.5, 0 7" fill="#3b82f6" />
                </marker>
                <marker id="arrow-green" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
                  <polygon points="0 0, 10 3.5, 0 7" fill="#22c55e" />
                </marker>
              </defs>

              {/* Producer to Queue */}
              <path
                d="M 140 128 L 220 128"
                stroke="#3b82f6"
                strokeWidth="3"
                fill="none"
                markerEnd="url(#arrow-blue)"
                className={producerActive ? 'animate-pulse' : ''}
              />

              {/* Queue to Consumer */}
              <path
                d="M 420 128 L 500 128"
                stroke="#22c55e"
                strokeWidth="3"
                fill="none"
                markerEnd="url(#arrow-green)"
                className={consumerActive ? 'animate-pulse' : ''}
              />
            </svg>
          </div>
        </CardContent>
      </Card>

      {/* Metrics Grid */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Produced</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-blue-600 dark:text-blue-400">{metrics.produced}</div>
            <div className="text-xs text-slate-500 mt-1">
              {((metrics.produced / Math.max(metrics.produced + metrics.dropped, 1)) * 100).toFixed(1)}% success
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Consumed</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-green-600 dark:text-green-400">{metrics.consumed}</div>
            <div className="text-xs text-slate-500 mt-1">
              {throughput.toFixed(1)}% throughput
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Queue Size</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-yellow-600 dark:text-yellow-400">{metrics.queueSize}</div>
            <div className="text-xs text-slate-500 mt-1">
              {queueUtilization.toFixed(1)}% utilized
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Backpressure Events</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-red-600 dark:text-red-400">{metrics.backpressureEvents}</div>
            <div className="text-xs text-slate-500 mt-1">
              {metrics.dropped} dropped
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Backpressure Visualization */}
      <Card>
        <CardHeader>
          <CardTitle>Backpressure Analysis</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            <div>
              <div className="flex justify-between mb-2">
                <span className="text-sm text-slate-600 dark:text-slate-400">Queue Capacity</span>
                <span className="font-mono text-sm">{metrics.queueSize} / {config.queueCapacity}</span>
              </div>
              <div className="w-full bg-slate-200 dark:bg-slate-700 rounded-full h-4">
                <div
                  className={`h-4 rounded-full transition-all ${queueUtilization >= 90 ? 'bg-red-500' : queueUtilization >= 70 ? 'bg-yellow-500' : 'bg-green-500'}`}
                  style={{ width: `${queueUtilization}%` }}
                />
              </div>
              <div className="flex justify-between text-xs text-slate-500 mt-1">
                <span>Empty</span>
                <span>Full</span>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div className="p-4 bg-blue-50 dark:bg-blue-900/20 rounded-lg">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-sm font-semibold">Producer Rate</span>
                  <Badge variant="outline">{config.producerRate}ms</Badge>
                </div>
                <div className="text-xs text-slate-600 dark:text-slate-400">
                  {1000 / config.producerRate} items/sec
                </div>
              </div>

              <div className="p-4 bg-green-50 dark:bg-green-900/20 rounded-lg">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-sm font-semibold">Consumer Rate</span>
                  <Badge variant="outline">{config.consumerRate}ms</Badge>
                </div>
                <div className="text-xs text-slate-600 dark:text-slate-400">
                  {1000 / config.consumerRate} items/sec
                </div>
              </div>
            </div>

            {backpressureActive && (
              <div className="p-4 bg-red-50 dark:bg-red-900/20 rounded-lg">
                <div className="text-sm font-semibold text-red-600 dark:text-red-400 mb-2">
                  ⚠️ Backpressure Active
                </div>
                <div className="text-xs text-slate-600 dark:text-slate-400 space-y-1">
                  <div>• Producer is being throttled to prevent overload</div>
                  <div>• Queue at {queueUtilization.toFixed(1)}% capacity</div>
                  <div>• Timeout: {config.timeout}ms before dropping</div>
                </div>
              </div>
            )}

            {!backpressureActive && metrics.queueSize < config.queueCapacity * 0.5 && (
              <div className="p-4 bg-green-50 dark:bg-green-900/20 rounded-lg">
                <div className="text-sm font-semibold text-green-600 dark:text-green-400 mb-2">
                  ✓ System Healthy
                </div>
                <div className="text-xs text-slate-600 dark:text-slate-400">
                  Flow is balanced. No backpressure needed.
                </div>
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Implementation Details */}
      <Card>
        <CardHeader>
          <CardTitle>Implementation Details</CardTitle>
        </CardHeader>
        <CardContent>
          <Tabs defaultValue="description">
            <TabsList>
              <TabsTrigger value="description">Description</TabsTrigger>
              <TabsTrigger value="code">Java Code</TabsTrigger>
              <TabsTrigger value="usecases">Use Cases</TabsTrigger>
            </TabsList>

            <TabsContent value="description" className="space-y-4">
              <div className="prose dark:prose-invert max-w-none">
                <h4 className="text-lg font-semibold mb-2">How It Works</h4>
                <ul className="list-disc pl-6 space-y-2">
                  <li><strong>Timeout-based Control:</strong> Producers block for timeout when queue is full</li>
                  <li><strong>Flow Regulation:</strong> Slow down producers when consumers can't keep up</li>
                  <li><strong>Graceful Degradation:</strong> Drop messages instead of crashing</li>
                  <li><strong>Self-regulating:</strong> System finds optimal flow rate automatically</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Key Strategies</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li><strong>Blocking:</strong> Producer blocks until queue has space (with timeout)</li>
                  <li><strong>Buffering:</strong> Bounded queue absorbs temporary spikes</li>
                  <li><strong>Dropping:</strong> Reject messages when timeout expires</li>
                  <li><strong>Throttling:</strong> Explicit rate limiting on producer side</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Benefits</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li>Prevents memory exhaustion from unbounded queues</li>
                  <li>Maintains system stability under heavy load</li>
                  <li>Avoids cascading failures from slow consumers</li>
                  <li>Predictable resource usage</li>
                </ul>
              </div>
            </TabsContent>

            <TabsContent value="code">
              <pre className="bg-slate-100 dark:bg-slate-800 p-4 rounded-lg overflow-x-auto text-sm">
                <code>{codeExample}</code>
              </pre>
            </TabsContent>

            <TabsContent value="usecases" className="space-y-4">
              <div className="space-y-4">
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">📊 Real-time Data Pipelines</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Handle bursty data streams without dropping critical events
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🔄 Async Messaging Systems</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Prevent message queue overflow when consumers are slow
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🌐 API Rate Limiting</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Protect downstream services from request spikes
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">💾 Database Batch Operations</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Control batch size to prevent connection pool exhaustion
                  </p>
                </div>
              </div>
            </TabsContent>
          </Tabs>
        </CardContent>
      </Card>

      {/* Related Patterns */}
      <Card>
        <CardHeader>
          <CardTitle>Related Patterns</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Bulkhead</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Isolate resources to prevent overload
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Circuit Breaker</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Fail fast when services are down
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Retry</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Handle transient failures gracefully
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
