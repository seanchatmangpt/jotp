'use client';

import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@radix-ui/themes';
import { Button } from '@radix-ui/themes';
import { Badge } from '@radix-ui/themes';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@radix-ui/themes';

interface BulkheadConfig {
  maxConcurrent: number;
  maxQueueSize: number;
}

interface BulkheadInstance {
  id: string;
  activeTasks: number;
  queueSize: number;
  rejectedTasks: number;
  completedTasks: number;
}

interface Task {
  id: number;
  bulkheadId: string;
  status: 'queued' | 'running' | 'completed' | 'rejected';
  startTime: number;
  duration: number;
}

export default function BulkheadFlow() {
  const [bulkheads, setBulkheads] = useState<BulkheadInstance[]>([
    { id: 'bh1', activeTasks: 0, queueSize: 0, rejectedTasks: 0, completedTasks: 0 },
    { id: 'bh2', activeTasks: 0, queueSize: 0, rejectedTasks: 0, completedTasks: 0 },
    { id: 'bh3', activeTasks: 0, queueSize: 0, rejectedTasks: 0, completedTasks: 0 }
  ]);

  const [config, setConfig] = useState<BulkheadConfig>({
    maxConcurrent: 3,
    maxQueueSize: 5
  });

  const [tasks, setTasks] = useState<Task[]>([]);
  const [isSimulating, setIsSimulating] = useState(false);
  const [taskId, setTaskId] = useState(0);

  useEffect(() => {
    if (!isSimulating) return;

    const interval = setInterval(() => {
      // Add new task to random bulkhead
      const randomBulkhead = bulkheads[Math.floor(Math.random() * bulkheads.length)];
      const newTask: Task = {
        id: taskId,
        bulkheadId: randomBulkhead.id,
        status: 'queued',
        startTime: Date.now(),
        duration: 2000 + Math.random() * 3000
      };

      setTaskId(prev => prev + 1);

      setTasks(prev => [...prev, newTask]);
      setBulkheads(prev => prev.map(bh => {
        if (bh.id === randomBulkhead.id) {
          if (bh.activeTasks < config.maxConcurrent) {
            // Start immediately
            return { ...bh, activeTasks: bh.activeTasks + 1 };
          } else if (bh.queueSize < config.maxQueueSize) {
            // Queue it
            return { ...bh, queueSize: bh.queueSize + 1 };
          } else {
            // Reject
            return { ...bh, rejectedTasks: bh.rejectedTasks + 1 };
          }
        }
        return bh;
      }));

      // Update task status after processing
      setTimeout(() => {
        setTasks(prev => prev.map(t => {
          if (t.id === newTask.id) {
            setBulkheads(bhPrev => bhPrev.map(bh => {
              if (bh.id === t.bulkheadId && t.status === 'running') {
                return { ...bh, activeTasks: bh.activeTasks - 1, completedTasks: bh.completedTasks + 1 };
              }
              return bh;
            }));
            return { ...t, status: 'completed' };
          }
          return t;
        }));
      }, newTask.duration);

      // Process queue
      setTimeout(() => {
        setTasks(prev => prev.map(t => {
          if (t.id === newTask.id && t.status === 'queued') {
            setBulkheads(bhPrev => {
              let updated = false;
              const newBhs = bhPrev.map(bh => {
                if (bh.id === t.bulkheadId && bh.queueSize > 0 && bh.activeTasks < config.maxConcurrent && !updated) {
                  updated = true;
                  return { ...bh, queueSize: bh.queueSize - 1, activeTasks: bh.activeTasks + 1 };
                }
                return bh;
              });
              return newBhs;
            });
            return { ...t, status: 'running' };
          }
          return t;
        }));
      }, 100);

    }, 1500);

    return () => clearInterval(interval);
  }, [isSimulating, bulkheads, config, taskId]);

  const reset = () => {
    setTasks([]);
    setBulkheads([
      { id: 'bh1', activeTasks: 0, queueSize: 0, rejectedTasks: 0, completedTasks: 0 },
      { id: 'bh2', activeTasks: 0, queueSize: 0, rejectedTasks: 0, completedTasks: 0 },
      { id: 'bh3', activeTasks: 0, queueSize: 0, rejectedTasks: 0, completedTasks: 0 }
    ]);
    setTaskId(0);
  };

  const totalTasks = tasks.length;
  const totalRejected = bulkheads.reduce((sum, bh) => sum + bh.rejectedTasks, 0);
  const totalCompleted = bulkheads.reduce((sum, bh) => sum + bh.completedTasks, 0);
  const totalActive = bulkheads.reduce((sum, bh) => sum + bh.activeTasks, 0);

  const codeExample = `// Bulkhead Pattern Implementation
public final class Bulkhead {
    private final Semaphore semaphore;
    private final int maxQueueSize;
    private final BlockingQueue<Runnable> queue;

    public <T> T execute(Supplier<T> task) {
        if (!semaphore.tryAcquire()) {
            if (queue.size() >= maxQueueSize) {
                throw new BulkheadException("Bulkhead full");
            }
            // Queue the task
        }

        try {
            return task.get();
        } finally {
            semaphore.release();
        }
    }
}

// Isolated thread pools per bulkhead
ExecutorService bulkhead1 = Executors.newFixedThreadPool(3);
ExecutorService bulkhead2 = Executors.newFixedThreadPool(3);
ExecutorService bulkhead3 = Executors.newFixedThreadPool(3);

// Task distribution
public void submitTask(Task task) {
    Bulkhead bulkhead = selectBulkhead(task);
    bulkhead.execute(() -> process(task));
}`;

  return (
    <div className="space-y-6">
      {/* Controls */}
      <Card>
        <CardHeader>
          <CardTitle>Bulkhead Pattern</CardTitle>
          <CardDescription>
            Isolate resources to prevent cascading failures across system components
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
                <label className="text-sm">Max Concurrent:</label>
                <input
                  type="number"
                  value={config.maxConcurrent}
                  onChange={(e) => setConfig(prev => ({ ...prev, maxConcurrent: Number(e.target.value) }))}
                  className="w-20 px-3 py-1 border rounded"
                  min="1"
                  max="10"
                />
              </div>
              <div className="flex items-center gap-2">
                <label className="text-sm">Queue Size:</label>
                <input
                  type="number"
                  value={config.maxQueueSize}
                  onChange={(e) => setConfig(prev => ({ ...prev, maxQueueSize: Number(e.target.value) }))}
                  className="w-20 px-3 py-1 border rounded"
                  min="1"
                  max="20"
                />
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Bulkhead Visualization */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {bulkheads.map((bulkhead) => {
          const capacity = (bulkhead.activeTasks / config.maxConcurrent) * 100;
          const queueCapacity = (bulkhead.queueSize / config.maxQueueSize) * 100;

          return (
            <Card key={bulkhead.id} className="relative">
              <CardHeader>
                <CardTitle className="text-lg">Bulkhead {bulkhead.id.toUpperCase()}</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                {/* Active Tasks */}
                <div>
                  <div className="flex justify-between mb-2">
                    <span className="text-sm text-slate-600 dark:text-slate-400">Active Tasks</span>
                    <span className="font-mono text-sm">{bulkhead.activeTasks} / {config.maxConcurrent}</span>
                  </div>
                  <div className="w-full bg-slate-200 dark:bg-slate-700 rounded-lg h-20 relative overflow-hidden">
                    {Array.from({ length: bulkhead.activeTasks }).map((_, i) => (
                      <div
                        key={i}
                        className="absolute top-0 w-6 h-6 bg-blue-500 rounded animate-pulse"
                        style={{ left: `${(i / config.maxConcurrent) * 100}%`, transform: 'translateX(-50%)' }}
                      />
                    ))}
                  </div>
                  <div className="w-full bg-slate-200 dark:bg-slate-700 rounded-full h-2 mt-2">
                    <div
                      className={`h-2 rounded-full transition-all ${capacity >= 90 ? 'bg-red-500' : capacity >= 70 ? 'bg-yellow-500' : 'bg-green-500'}`}
                      style={{ width: `${capacity}%` }}
                    />
                  </div>
                </div>

                {/* Queue */}
                <div>
                  <div className="flex justify-between mb-2">
                    <span className="text-sm text-slate-600 dark:text-slate-400">Queue</span>
                    <span className="font-mono text-sm">{bulkhead.queueSize} / {config.maxQueueSize}</span>
                  </div>
                  <div className="w-full bg-slate-200 dark:bg-slate-700 rounded-lg h-16 p-1 flex gap-1 overflow-hidden">
                    {Array.from({ length: bulkhead.queueSize }).map((_, i) => (
                      <div key={i} className="flex-shrink-0 w-4 h-full bg-yellow-500 rounded-sm" />
                    ))}
                    {bulkhead.queueSize === 0 && (
                      <div className="w-full h-full flex items-center justify-center text-xs text-slate-400">
                        Empty
                      </div>
                    )}
                  </div>
                  <div className="w-full bg-slate-200 dark:bg-slate-700 rounded-full h-2 mt-2">
                    <div
                      className={`h-2 rounded-full transition-all ${queueCapacity >= 90 ? 'bg-red-500' : 'bg-yellow-500'}`}
                      style={{ width: `${queueCapacity}%` }}
                    />
                  </div>
                </div>

                {/* Metrics */}
                <div className="grid grid-cols-3 gap-2 pt-2 border-t">
                  <div className="text-center p-2 bg-green-50 dark:bg-green-900/20 rounded">
                    <div className="text-lg font-bold text-green-600 dark:text-green-400">{bulkhead.completedTasks}</div>
                    <div className="text-xs text-slate-600 dark:text-slate-400">Done</div>
                  </div>
                  <div className="text-center p-2 bg-red-50 dark:bg-red-900/20 rounded">
                    <div className="text-lg font-bold text-red-600 dark:text-red-400">{bulkhead.rejectedTasks}</div>
                    <div className="text-xs text-slate-600 dark:text-slate-400">Rejected</div>
                  </div>
                  <div className="text-center p-2 bg-blue-50 dark:bg-blue-900/20 rounded">
                    <div className="text-lg font-bold text-blue-600 dark:text-blue-400">{bulkhead.activeTasks + bulkhead.queueSize}</div>
                    <div className="text-xs text-slate-600 dark:text-slate-400">In-Flight</div>
                  </div>
                </div>

                {/* Status Badge */}
                {bulkhead.queueSize >= config.maxQueueSize && bulkhead.activeTasks >= config.maxConcurrent && (
                  <div className="absolute top-2 right-2">
                    <Badge variant="destructive">Full</Badge>
                  </div>
                )}
              </CardContent>
            </Card>
          );
        })}
      </div>

      {/* Overall Metrics */}
      <Card>
        <CardHeader>
          <CardTitle>System Overview</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <div className="text-center p-4 bg-blue-50 dark:bg-blue-900/20 rounded-lg">
              <div className="text-3xl font-bold text-blue-600 dark:text-blue-400">{totalTasks}</div>
              <div className="text-sm text-slate-600 dark:text-slate-400">Total Tasks</div>
            </div>
            <div className="text-center p-4 bg-green-50 dark:bg-green-900/20 rounded-lg">
              <div className="text-3xl font-bold text-green-600 dark:text-green-400">{totalCompleted}</div>
              <div className="text-sm text-slate-600 dark:text-slate-400">Completed</div>
            </div>
            <div className="text-center p-4 bg-yellow-50 dark:bg-yellow-900/20 rounded-lg">
              <div className="text-3xl font-bold text-yellow-600 dark:text-yellow-400">{totalActive}</div>
              <div className="text-sm text-slate-600 dark:text-slate-400">Active</div>
            </div>
            <div className="text-center p-4 bg-red-50 dark:bg-red-900/20 rounded-lg">
              <div className="text-3xl font-bold text-red-600 dark:text-red-400">{totalRejected}</div>
              <div className="text-sm text-slate-600 dark:text-slate-400">Rejected</div>
            </div>
          </div>

          {totalRejected > 0 && (
            <div className="mt-4 p-4 bg-red-50 dark:bg-red-900/20 rounded-lg">
              <div className="text-sm font-semibold text-red-600 dark:text-red-400 mb-1">
                ⚠️ Tasks Rejected
              </div>
              <div className="text-xs text-slate-600 dark:text-slate-400">
                {((totalRejected / totalTasks) * 100).toFixed(1)}% of tasks were rejected due to bulkhead capacity
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Task Flow Visualization */}
      <Card>
        <CardHeader>
          <CardTitle>Task Distribution</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="relative h-32 bg-slate-50 dark:bg-slate-900 rounded-lg overflow-hidden">
            {/* Incoming tasks */}
            <div className="absolute left-4 top-1/2 -translate-y-1/2">
              <div className="text-4xl">📦</div>
              <div className="text-xs text-center text-slate-600 dark:text-slate-400 mt-1">Incoming</div>
            </div>

            {/* Router */}
            <div className="absolute left-1/4 top-1/2 -translate-y-1/2">
              <div className="w-16 h-16 bg-blue-500 rounded-full flex items-center justify-center">
                <span className="text-white text-2xl">➔</span>
              </div>
              <div className="text-xs text-center text-slate-600 dark:text-slate-400 mt-1">Router</div>
            </div>

            {/* Bulkheads */}
            {bulkheads.map((bh, i) => (
              <div key={bh.id} className="absolute right-4 top-1/2 -translate-y-1/2" style={{ transform: `translateY(${(i - 1) * 40}px)` }}>
                <div className={`w-20 h-12 rounded-lg ${bh.activeTasks >= config.maxConcurrent ? 'bg-red-500' : bh.activeTasks >= config.maxConcurrent * 0.7 ? 'bg-yellow-500' : 'bg-green-500'} flex items-center justify-center`}>
                  <span className="text-white text-sm font-bold">{bh.id.toUpperCase()}</span>
                </div>
                <div className="text-xs text-center text-slate-600 dark:text-slate-400 mt-1">
                  {bh.activeTasks}/{config.maxConcurrent}
                </div>
              </div>
            ))}

            {/* Animated connection lines */}
            {isSimulating && (
              <div className="absolute inset-0 pointer-events-none">
                <div className="absolute left-1/4 top-1/2 w-px h-px bg-blue-500 animate-ping" />
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
                  <li><strong>Resource Isolation:</strong> Each bulkhead has its own thread pool and queue</li>
                  <li><strong>Capacity Limits:</strong> Max concurrent tasks and queue size prevent overload</li>
                  <li><strong>Fail Fast:</strong> Tasks are rejected when bulkhead is full, not queued indefinitely</li>
                  <li><strong>Independent Failures:</strong> One bulkhead's failure doesn't affect others</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Key Components</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li><strong>Semaphore:</strong> Limits concurrent access to resources</li>
                  <li><strong>BlockingQueue:</strong> Bounded queue for waiting tasks</li>
                  <li><strong>Thread Pool:</strong> Dedicated executor per bulkhead</li>
                  <li><strong>Router:</strong> Distributes tasks across bulkheads</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Benefits</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li>Prevents resource exhaustion in one area from affecting entire system</li>
                  <li>Maintains service availability even under partial load</li>
                  <li>Predictable performance per service boundary</li>
                  <li>Graceful degradation rather than total failure</li>
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
                  <h5 className="font-semibold mb-2">🔄 Database Connection Pools</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Separate pools for read vs write operations, or per-tenant isolation
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🌐 API Client Isolation</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Separate bulkheads for different external APIs to prevent one slow API from blocking others
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🏢 Multi-Tenant Systems</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Per-tenant resource limits to ensure fair resource allocation
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">⚡ High-Throughput Services</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Isolate critical path operations from background processing
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
              <h5 className="font-semibold mb-2">Circuit Breaker</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Fail fast when services are down
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Backpressure</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Control flow to prevent overload
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Retry</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Handle transient failures with retries
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
