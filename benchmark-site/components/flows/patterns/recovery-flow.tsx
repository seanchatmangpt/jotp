'use client';

import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@radix-ui/themes';
import { Button } from '@radix-ui/themes';
import { Badge } from '@radix-ui/themes';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@radix-ui/themes';

type ProcessStatus = 'running' | 'crashed' | 'restarting' | 'restarted';

interface Process {
  id: string;
  name: string;
  status: ProcessStatus;
  restartCount: number;
  lastCrash: number;
  uptime: number;
  crashReason: string;
}

interface SupervisorMetrics {
  totalRestarts: number;
  maxRestarts: number;
  timeWindow: number;
  crashesInWindow: number;
}

export default function RecoveryFlow() {
  const [processes, setProcesses] = useState<Process[]>([
    { id: 'p1', name: 'Database Worker', status: 'running', restartCount: 0, lastCrash: 0, uptime: Date.now(), crashReason: '' },
    { id: 'p2', name: 'API Handler', status: 'running', restartCount: 0, lastCrash: 0, uptime: Date.now(), crashReason: '' },
    { id: 'p3', name: 'Background Job', status: 'running', restartCount: 0, lastCrash: 0, uptime: Date.now(), crashReason: '' },
    { id: 'p4', name: 'Event Processor', status: 'running', restartCount: 0, lastCrash: 0, uptime: Date.now(), crashReason: '' }
  ]);

  const [metrics, setMetrics] = useState<SupervisorMetrics>({
    totalRestarts: 0,
    maxRestarts: 5,
    timeWindow: 10000,
    crashesInWindow: 0
  });

  const [isSimulating, setIsSimulating] = useState(false);
  const [crashRate, setCrashRate] = useState(0.15);
  const [logEntries, setLogEntries] = useState<Array<{ time: number; message: string; type: 'info' | 'error' | 'success' }>>([]);

  useEffect(() => {
    if (!isSimulating) return;

    const interval = setInterval(() => {
      // Random crashes
      setProcesses(prev => prev.map(process => {
        if (process.status !== 'running') return process;

        if (Math.random() < crashRate) {
          const crashReasons = [
            'OutOfMemoryError',
            'NullPointerException',
            'ConnectionTimeout',
            'DatabaseLocked',
            'StackOverflowError'
          ];

          addLogEntry(`Process ${process.name} crashed: ${crashReasons[Math.floor(Math.random() * crashReasons.length)]}`, 'error');

          return {
            ...process,
            status: 'crashed',
            lastCrash: Date.now(),
            crashReason: crashReasons[Math.floor(Math.random() * crashReasons.length)]
          };
        }

        return process;
      }));
    }, 2000);

    return () => clearInterval(interval);
  }, [isSimulating, crashRate]);

  // Auto-restart crashed processes
  useEffect(() => {
    const crashedProcesses = processes.filter(p => p.status === 'crashed');

    crashedProcesses.forEach(process => {
      setTimeout(() => {
        setProcesses(prev => prev.map(p => {
          if (p.id === process.id && p.status === 'crashed') {
            addLogEntry(`Supervisor restarting ${process.name} (restart #${p.restartCount + 1})`, 'info');

            setMetrics(m => ({
              ...m,
              totalRestarts: m.totalRestarts + 1,
              crashesInWindow: m.crashesInWindow + 1
            }));

            return {
              ...p,
              status: 'restarting',
              restartCount: p.restartCount + 1
            };
          }
          return p;
        }));

        // Complete restart after delay
        setTimeout(() => {
          setProcesses(prev => prev.map(p => {
            if (p.id === process.id && p.status === 'restarting') {
              addLogEntry(`${process.name} restarted successfully`, 'success');
              return {
                ...p,
                status: 'running',
                uptime: Date.now()
              };
            }
            return p;
          }));
        }, 1000);
      }, 500); // Delay before restart
    });
  }, [processes]);

  const addLogEntry = (message: string, type: 'info' | 'error' | 'success') => {
    setLogEntries(prev => [{ time: Date.now(), message, type }, ...prev].slice(0, 50));
  };

  const triggerCrash = (processId: string) => {
    setProcesses(prev => prev.map(p => {
      if (p.id === processId && p.status === 'running') {
        addLogEntry(`Manually triggered crash of ${p.name}`, 'error');
        return {
          ...p,
          status: 'crashed',
          lastCrash: Date.now(),
          crashReason: 'Manual crash trigger'
        };
      }
      return p;
    }));
  };

  const reset = () => {
    setProcesses([
      { id: 'p1', name: 'Database Worker', status: 'running', restartCount: 0, lastCrash: 0, uptime: Date.now(), crashReason: '' },
      { id: 'p2', name: 'API Handler', status: 'running', restartCount: 0, lastCrash: 0, uptime: Date.now(), crashReason: '' },
      { id: 'p3', name: 'Background Job', status: 'running', restartCount: 0, lastCrash: 0, uptime: Date.now(), crashReason: '' },
      { id: 'p4', name: 'Event Processor', status: 'running', restartCount: 0, lastCrash: 0, uptime: Date.now(), crashReason: '' }
    ]);
    setMetrics({
      totalRestarts: 0,
      maxRestarts: 5,
      timeWindow: 10000,
      crashesInWindow: 0
    });
    setLogEntries([]);
  };

  const getStatusColor = (status: ProcessStatus) => {
    switch (status) {
      case 'running': return 'bg-green-500';
      case 'crashed': return 'bg-red-500';
      case 'restarting': return 'bg-yellow-500 animate-pulse';
      case 'restarted': return 'bg-blue-500';
    }
  };

  const runningProcesses = processes.filter(p => p.status === 'running').length;
  const crashedProcesses = processes.filter(p => p.status === 'crashed').length;
  const restartingProcesses = processes.filter(p => p.status === 'restarting').length;

  const codeExample = `// "Let It Crash" + Supervisor Recovery
public final class Supervisor {
    private final List<ChildSpec> children;
    private final RestartStrategy strategy;
    private final Map<ProcessId, Integer> restartCounts = new ConcurrentHashMap<>();

    public void supervise() {
        while (true) {
            // Wait for exit signal from any child
            ExitSignal signal = receiveExitSignal();

            ProcessId pid = signal.processId();
            Throwable reason = signal.reason();

            // Check restart strategy
            RestartDecision decision = strategy.shouldRestart(pid, reason, restartCounts.get(pid));

            switch (decision) {
                case RESTART ->
                    restartChild(pid);
                case STOP ->
                    terminateChild(pid);
            }
        }
    }

    private void restartChild(ProcessId pid) {
        restartCounts.merge(pid, 1, Integer::sum);

        // Start new process with same spec
        Process newProcess = startProcess(childSpec(pid));

        // Link to supervisor again
        link(newProcess);

        log("Restarted process " + pid + " (" + restartCounts.get(pid) + " restarts)");
    }
}

// Restart strategy with max restart limit
public record RestartStrategy(
    int maxRestarts,
    Duration timeWindow
) {
    public RestartDecision shouldRestart(ProcessId pid, Throwable reason, Integer restartCount) {
        if (restartCount == null) return RestartDecision.RESTART;

        if (restartCount >= maxRestarts) {
            return RestartDecision.STOP; // Give up
        }

        return RestartDecision.RESTART;
    }
}`;

  return (
    <div className="space-y-6">
      {/* Controls */}
      <Card>
        <CardHeader>
          <CardTitle>Crash Recovery Pattern</CardTitle>
          <CardDescription>
            "Let It Crash" principle with automatic supervisor restart and retry strategies
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

            <div className="flex items-center gap-2 ml-auto">
              <label className="text-sm">Crash Rate:</label>
              <input
                type="range"
                value={crashRate * 100}
                onChange={(e) => setCrashRate(Number(e.target.value) / 100)}
                className="w-32"
                min="0"
                max="50"
              />
              <span className="text-sm font-mono">{(crashRate * 100).toFixed(0)}%</span>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Statistics */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Running</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-green-600 dark:text-green-400">{runningProcesses}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Crashed</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-red-600 dark:text-red-400">{crashedProcesses}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Restarting</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-yellow-600 dark:text-yellow-400">{restartingProcesses}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Total Restarts</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-blue-600 dark:text-blue-400">{metrics.totalRestarts}</div>
          </CardContent>
        </Card>
      </div>

      {/* Supervisor Tree Visualization */}
      <Card>
        <CardHeader>
          <CardTitle>Supervisor Tree</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="bg-slate-50 dark:bg-slate-900 rounded-lg p-6">
            <div className="flex flex-col items-center">
              {/* Supervisor */}
              <div className="w-24 h-24 bg-purple-500 rounded-full flex items-center justify-center text-white mb-4">
                <div className="text-center">
                  <div className="text-2xl">👁️</div>
                  <div className="text-xs mt-1">Supervisor</div>
                </div>
              </div>

              {/* Connection lines */}
              <div className="flex gap-8 mb-4">
                {[1, 2, 3, 4].map(i => (
                  <div key={i} className="w-0.5 h-8 bg-slate-400" />
                ))}
              </div>

              {/* Child processes */}
              <div className="grid grid-cols-4 gap-4">
                {processes.map(process => (
                  <div
                    key={process.id}
                    className={`relative cursor-pointer transition-all hover:scale-105`}
                    onClick={() => triggerCrash(process.id)}
                  >
                    <div className={`w-16 h-16 rounded-full flex items-center justify-center text-white ${getStatusColor(process.status)}`}>
                      <div className="text-center">
                        <div className="text-xl">
                          {process.status === 'running' ? '✓' :
                           process.status === 'crashed' ? '✗' :
                           process.status === 'restarting' ? '↻' : '✓'}
                        </div>
                      </div>
                    </div>

                    <div className="text-xs text-center mt-2 font-semibold">
                      {process.name}
                    </div>

                    <div className="text-xs text-center text-slate-600 dark:text-slate-400">
                      {process.restartCount} restarts
                    </div>

                    {process.status === 'restarting' && (
                      <div className="absolute -top-2 -right-2">
                        <Badge className="animate-pulse">Restarting</Badge>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </div>

            <div className="mt-6 text-xs text-center text-slate-600 dark:text-slate-400">
              Click a process to manually trigger a crash and watch it auto-restart
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Process Details */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {processes.map(process => (
          <Card
            key={process.id}
            className={`cursor-pointer transition-all hover:shadow-lg ${
              process.status === 'crashed' ? 'border-red-500 animate-pulse' :
              process.status === 'restarting' ? 'border-yellow-500' :
              'border-green-500'
            }`}
            onClick={() => triggerCrash(process.id)}
          >
            <CardHeader className="pb-3">
              <div className="flex items-center justify-between">
                <CardTitle className="text-sm">{process.name}</CardTitle>
                <Badge className={getStatusColor(process.status)}>
                  {process.status}
                </Badge>
              </div>
            </CardHeader>
            <CardContent className="space-y-2">
              <div className="flex justify-between text-sm">
                <span className="text-slate-600 dark:text-slate-400">Restart Count</span>
                <span className="font-mono">{process.restartCount}</span>
              </div>

              <div className="flex justify-between text-sm">
                <span className="text-slate-600 dark:text-slate-400">Uptime</span>
                <span className="font-mono">
                  {process.uptime > 0
                    ? `${Math.floor((Date.now() - process.uptime) / 1000)}s`
                    : '-'}
                </span>
              </div>

              {process.crashReason && (
                <div className="p-2 bg-red-50 dark:bg-red-900/20 rounded text-xs">
                  <div className="font-semibold text-red-600 dark:text-red-400">Last Crash</div>
                  <div className="text-slate-600 dark:text-slate-400 truncate">
                    {process.crashReason}
                  </div>
                </div>
              )}

              {process.status === 'restarting' && (
                <div className="p-2 bg-yellow-50 dark:bg-yellow-900/20 rounded text-xs animate-pulse">
                  <div className="font-semibold text-yellow-600 dark:text-yellow-400">Restarting...</div>
                  <div className="text-slate-600 dark:text-slate-400">
                    Supervisor is starting new instance
                  </div>
                </div>
              )}

              {process.restartCount >= metrics.maxRestarts - 1 && process.status === 'running' && (
                <div className="p-2 bg-orange-50 dark:bg-orange-900/20 rounded text-xs">
                  <div className="font-semibold text-orange-600 dark:text-orange-400">Warning</div>
                  <div className="text-slate-600 dark:text-slate-400">
                    Approaching max restart limit
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        ))}
      </div>

      {/* Supervisor Log */}
      <Card>
        <CardHeader>
          <CardTitle>Supervisor Log</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="bg-slate-900 rounded-lg p-4 h-64 overflow-y-auto font-mono text-sm">
            {logEntries.length === 0 ? (
              <div className="text-slate-500">No events yet. Start simulation to see supervisor actions.</div>
            ) : (
              logEntries.map((entry, i) => (
                <div key={i} className={`mb-1 ${entry.type === 'error' ? 'text-red-400' : entry.type === 'success' ? 'text-green-400' : 'text-slate-300'}`}>
                  <span className="text-slate-500">[{new Date(entry.time).toLocaleTimeString()}]</span> {entry.message}
                </div>
              ))
            )}
          </div>
        </CardContent>
      </Card>

      {/* Key Insight */}
      {metrics.totalRestarts > 0 && (
        <Card className="border-blue-500">
          <CardHeader>
            <CardTitle className="text-blue-600 dark:text-blue-400">"Let It Crash" in Action</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Notice the key benefits of this pattern:
              </p>
              <ul className="list-disc pl-6 text-sm text-slate-600 dark:text-slate-400 space-y-1">
                <li>No try-catch blocks - processes simply crash when something goes wrong</li>
                <li>Supervisor automatically detects crashes and restarts processes</li>
                <li>Each restart gets a fresh state, no corruption from previous errors</li>
                <li>Maximum restart limit prevents infinite crash loops</li>
              </ul>
            </div>
          </CardContent>
        </Card>
      )}

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
                  <li><strong>Let It Crash:</strong> Don't catch exceptions - let processes fail fast</li>
                  <li><strong>Supervision:</strong> Parent process monitors child for crashes</li>
                  <li><strong>Auto-Restart:</strong> Supervisor restarts crashed children automatically</li>
                  <li><strong>Fresh State:</strong> New process starts with clean state</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Restart Strategies</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li><strong>One-for-One:</strong> Restart only crashed child</li>
                  <li><strong>One-for-All:</strong> Restart all children if one crashes</li>
                  <li><strong>Rest-for-One:</strong> Restart crashed and all started after it</li>
                  <li><strong>Max Restarts:</strong> Give up after N restarts in time window</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Benefits</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li>Cleaner code without complex error handling</li>
                  <li>Automatic recovery without manual intervention</li>
                  <li>Fresh state eliminates corruption bugs</li>
                  <li>Built-in resilience and self-healing</li>
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
                  <h5 className="font-semibold mb-2">🔄 Stateful Services</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Database connections, session managers, caches - auto-recover from crashes
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">📡 Message Handlers</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Message consumers that crash on bad messages, restart to process more
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🌐 API Servers</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Request handlers that crash on unexpected input
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">⚙️ Background Workers</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Job processors, periodic tasks, cleanup jobs
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
              <h5 className="font-semibold mb-2">Supervisor Tree</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Hierarchical fault containment
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Circuit Breaker</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Prevent repeated crash triggers
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Health Check</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Monitor process health
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
