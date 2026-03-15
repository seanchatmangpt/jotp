'use client';

import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@radix-ui/themes';
import { Button } from '@radix-ui/themes';
import { Badge } from '@radix-ui/themes';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@radix-ui/themes';

type CircuitState = 'CLOSED' | 'OPEN' | 'HALF_OPEN';

interface CircuitBreakerMetrics {
  state: CircuitState;
  failureCount: number;
  successCount: number;
  lastFailureTime: number;
  threshold: number;
  timeout: number;
  halfOpenMaxCalls: number;
  requestCount: number;
}

export default function CircuitBreakerFlow() {
  const [metrics, setMetrics] = useState<CircuitBreakerMetrics>({
    state: 'CLOSED',
    failureCount: 0,
    successCount: 0,
    lastFailureTime: 0,
    threshold: 5,
    timeout: 60000,
    halfOpenMaxCalls: 3,
    requestCount: 0
  });

  const [isAnimating, setIsAnimating] = useState(false);
  const [recentRequests, setRecentRequests] = useState<Array<{ id: number; success: boolean; time: number }>>([]);
  const [simulationSpeed, setSimulationSpeed] = useState(1000);

  useEffect(() => {
    if (!isAnimating) return;

    const interval = setInterval(() => {
      simulateRequest();
    }, simulationSpeed);

    return () => clearInterval(interval);
  }, [isAnimating, simulationSpeed, metrics]);

  const simulateRequest = () => {
    const requestId = Date.now();
    const failureRate = metrics.state === 'OPEN' ? 1 : metrics.state === 'HALF_OPEN' ? 0.3 : 0.2;
    const success = Math.random() > failureRate;

    setRecentRequests(prev => [{ id: requestId, success, time: Date.now() }, ...prev].slice(0, 20));

    setMetrics(prev => {
      const newMetrics = { ...prev, requestCount: prev.requestCount + 1 };

      if (prev.state === 'OPEN') {
        // Check if timeout has elapsed
        if (Date.now() - prev.lastFailureTime > prev.timeout) {
          newMetrics.state = 'HALF_OPEN';
          newMetrics.failureCount = 0;
        }
        return newMetrics;
      }

      if (success) {
        newMetrics.successCount++;
        if (prev.state === 'HALF_OPEN') {
          newMetrics.state = 'CLOSED';
          newMetrics.failureCount = 0;
        }
      } else {
        newMetrics.failureCount++;
        newMetrics.lastFailureTime = Date.now();

        if (prev.state === 'CLOSED' && newMetrics.failureCount >= prev.threshold) {
          newMetrics.state = 'OPEN';
        } else if (prev.state === 'HALF_OPEN') {
          newMetrics.state = 'OPEN';
          newMetrics.lastFailureTime = Date.now();
        }
      }

      return newMetrics;
    });
  };

  const triggerFailure = () => {
    setMetrics(prev => ({
      ...prev,
      failureCount: prev.threshold + 1,
      lastFailureTime: Date.now(),
      state: 'OPEN'
    }));
    setRecentRequests(prev => [{ id: Date.now(), success: false, time: Date.now() }, ...prev]);
  };

  const reset = () => {
    setMetrics({
      state: 'CLOSED',
      failureCount: 0,
      successCount: 0,
      lastFailureTime: 0,
      threshold: 5,
      timeout: 60000,
      halfOpenMaxCalls: 3,
      requestCount: 0
    });
    setRecentRequests([]);
  };

  const getStateColor = (state: CircuitState) => {
    switch (state) {
      case 'CLOSED': return 'bg-green-500';
      case 'OPEN': return 'bg-red-500';
      case 'HALF_OPEN': return 'bg-yellow-500';
    }
  };

  const getStateBorderColor = (state: CircuitState) => {
    switch (state) {
      case 'CLOSED': return 'border-green-500';
      case 'OPEN': return 'border-red-500';
      case 'HALF_OPEN': return 'border-yellow-500';
    }
  };

  const successRate = metrics.requestCount > 0
    ? ((metrics.successCount / metrics.requestCount) * 100).toFixed(1)
    : '0.0';

  const codeExample = `// Circuit Breaker State Machine
public sealed interface CircuitState {
    record Closed() implements CircuitState {}
    record Open() implements CircuitState {}
    record HalfOpen() implements CircuitState {}
}

// State transitions based on failures
Transition<CircuitState, Event, Context> handleEvent(
    CircuitState state, Event event, Context ctx) {

    return switch (state) {
        case Closed c -> {
            if (ctx.failureCount() >= ctx.threshold()) {
                yield Transition.nextState(new CircuitState.Open());
            }
            yield Transition.keepState();
        }

        case Open o -> {
            if (ctx.timeSinceLastFailure() > ctx.timeout()) {
                yield Transition.nextState(new CircuitState.HalfOpen());
            }
            yield Transition.keepState();
        }

        case HalfOpen ho -> {
            if (event.success()) {
                yield Transition.nextState(new CircuitState.Closed());
            } else {
                yield Transition.nextState(new CircuitState.Open());
            }
        }
    };
}`;

  return (
    <div className="space-y-6">
      {/* Controls */}
      <Card>
        <CardHeader>
          <CardTitle>Circuit Breaker Pattern</CardTitle>
          <CardDescription>
            Prevents cascading failures by failing fast when a service is down
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex gap-4 items-center flex-wrap">
            <Button
              onClick={() => setIsAnimating(!isAnimating)}
              variant={isAnimating ? 'destructive' : 'default'}
            >
              {isAnimating ? 'Stop Simulation' : 'Start Simulation'}
            </Button>
            <Button onClick={triggerFailure} variant="outline">
              Trigger Failure Burst
            </Button>
            <Button onClick={reset} variant="ghost">
              Reset
            </Button>
            <div className="flex items-center gap-2">
              <label className="text-sm">Speed:</label>
              <select
                value={simulationSpeed}
                onChange={(e) => setSimulationSpeed(Number(e.target.value))}
                className="px-3 py-1 border rounded"
              >
                <option value={2000}>Slow</option>
                <option value={1000}>Normal</option>
                <option value={500}>Fast</option>
              </select>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Circuit Breaker Visualization */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* State Diagram */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>State Machine</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="relative h-80 bg-slate-50 dark:bg-slate-900 rounded-lg p-4">
              {/* States */}
              <div className="absolute top-8 left-8">
                <div className={`w-24 h-24 rounded-full flex items-center justify-center text-white font-bold border-4 ${getStateBorderColor(metrics.state)} ${getStateColor(metrics.state)} ${metrics.state === 'CLOSED' ? 'ring-4 ring-offset-2' : ''}`}>
                  CLOSED
                </div>
                <p className="text-center mt-2 text-sm text-slate-600 dark:text-slate-400">
                  Normal operation
                </p>
              </div>

              <div className="absolute top-8 right-8">
                <div className={`w-24 h-24 rounded-full flex items-center justify-center text-white font-bold border-4 ${getStateBorderColor(metrics.state)} ${getStateColor(metrics.state)} ${metrics.state === 'OPEN' ? 'ring-4 ring-offset-2' : ''}`}>
                  OPEN
                </div>
                <p className="text-center mt-2 text-sm text-slate-600 dark:text-slate-400">
                  Failing fast
                </p>
              </div>

              <div className="absolute bottom-8 left-1/2 -translate-x-1/2">
                <div className={`w-28 h-28 rounded-full flex items-center justify-center text-white font-bold border-4 ${getStateBorderColor(metrics.state)} ${getStateColor(metrics.state)} ${metrics.state === 'HALF_OPEN' ? 'ring-4 ring-offset-2' : ''}`}>
                  HALF_OPEN
                </div>
                <p className="text-center mt-2 text-sm text-slate-600 dark:text-slate-400">
                  Testing recovery
                </p>
              </div>

              {/* Transition arrows */}
              <svg className="absolute inset-0 w-full h-full pointer-events-none">
                {/* CLOSED to OPEN */}
                <defs>
                  <marker id="arrowhead" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
                    <polygon points="0 0, 10 3.5, 0 7" fill="#64748b" />
                  </marker>
                </defs>
                <path d="M 130 70 Q 250 20 370 70" stroke="#64748b" strokeWidth="2" fill="none" markerEnd="url(#arrowhead)" />
                <text x="250" y="40" className="fill-slate-600 dark:fill-slate-400 text-xs">Failures ≥ threshold</text>

                {/* OPEN to HALF_OPEN */}
                <path d="M 400 130 Q 350 200 300 260" stroke="#64748b" strokeWidth="2" fill="none" markerEnd="url(#arrowhead)" />
                <text x="320" y="200" className="fill-slate-600 dark:fill-slate-400 text-xs">Timeout elapsed</text>

                {/* HALF_OPEN to CLOSED */}
                <path d="M 230 260 Q 150 200 130 130" stroke="#64748b" strokeWidth="2" fill="none" markerEnd="url(#arrowhead)" />
                <text x="130" y="220" className="fill-slate-600 dark:fill-slate-400 text-xs">Success</text>

                {/* HALF_OPEN to OPEN */}
                <path d="M 370 260 Q 450 200 470 130" stroke="#64748b" strokeWidth="2" fill="none" markerEnd="url(#arrowhead)" />
                <text x="430" y="220" className="fill-slate-600 dark:fill-slate-400 text-xs">Failure</text>
              </svg>
            </div>
          </CardContent>
        </Card>

        {/* Metrics */}
        <Card>
          <CardHeader>
            <CardTitle>Metrics</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div>
              <div className="flex justify-between items-center mb-2">
                <span className="text-sm text-slate-600 dark:text-slate-400">Current State</span>
                <Badge className={getStateColor(metrics.state)}>{metrics.state}</Badge>
              </div>
            </div>

            <div className="space-y-2">
              <div className="flex justify-between text-sm">
                <span className="text-slate-600 dark:text-slate-400">Failure Count</span>
                <span className="font-mono">{metrics.failureCount} / {metrics.threshold}</span>
              </div>
              <div className="w-full bg-slate-200 dark:bg-slate-700 rounded-full h-2">
                <div
                  className={`h-2 rounded-full transition-all ${metrics.failureCount >= metrics.threshold ? 'bg-red-500' : 'bg-green-500'}`}
                  style={{ width: `${Math.min((metrics.failureCount / metrics.threshold) * 100, 100)}%` }}
                />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4 pt-2">
              <div className="text-center p-3 bg-green-50 dark:bg-green-900/20 rounded-lg">
                <div className="text-2xl font-bold text-green-600 dark:text-green-400">{metrics.successCount}</div>
                <div className="text-xs text-slate-600 dark:text-slate-400">Successes</div>
              </div>
              <div className="text-center p-3 bg-red-50 dark:bg-red-900/20 rounded-lg">
                <div className="text-2xl font-bold text-red-600 dark:text-red-400">{metrics.failureCount}</div>
                <div className="text-xs text-slate-600 dark:text-slate-400">Failures</div>
              </div>
            </div>

            <div className="pt-2 border-t">
              <div className="flex justify-between text-sm mb-1">
                <span className="text-slate-600 dark:text-slate-400">Success Rate</span>
                <span className="font-mono font-bold">{successRate}%</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-slate-600 dark:text-slate-400">Total Requests</span>
                <span className="font-mono">{metrics.requestCount}</span>
              </div>
            </div>

            {metrics.state === 'OPEN' && (
              <div className="mt-4 p-3 bg-red-50 dark:bg-red-900/20 rounded-lg">
                <div className="text-sm font-semibold text-red-600 dark:text-red-400 mb-1">
                  ⚠️ Circuit Open
                </div>
                <div className="text-xs text-slate-600 dark:text-slate-400">
                  Rejecting requests for {Math.max(0, metrics.timeout - (Date.now() - metrics.lastFailureTime)) / 1000}s
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Request Timeline */}
      <Card>
        <CardHeader>
          <CardTitle>Recent Requests</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex gap-1 overflow-x-auto pb-2">
            {recentRequests.slice(0, 30).map((req) => (
              <div
                key={req.id}
                className={`w-3 h-8 rounded-sm flex-shrink-0 ${req.success ? 'bg-green-500' : 'bg-red-500'}`}
                title={req.success ? 'Success' : 'Failure'}
              />
            ))}
            {recentRequests.length === 0 && (
              <div className="text-slate-400 dark:text-slate-600 text-sm">
                No requests yet. Start simulation to see activity.
              </div>
            )}
          </div>
          <div className="flex gap-4 mt-2 text-xs text-slate-600 dark:text-slate-400">
            <div className="flex items-center gap-1">
              <div className="w-3 h-3 bg-green-500 rounded-sm" />
              Success
            </div>
            <div className="flex items-center gap-1">
              <div className="w-3 h-3 bg-red-500 rounded-sm" />
              Failure
            </div>
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
                  <li><strong>CLOSED state:</strong> Normal operation, requests pass through to the service</li>
                  <li><strong>OPEN state:</strong> Circuit is tripped, requests fail immediately without calling the service</li>
                  <li><strong>HALF_OPEN state:</strong> Testing if service has recovered, allows limited requests</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Key Parameters</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li><strong>Failure Threshold:</strong> Number of failures before opening the circuit</li>
                  <li><strong>Timeout:</strong> How long to stay open before attempting recovery</li>
                  <li><strong>Half-Open Max Calls:</strong> Test requests allowed during recovery</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Benefits</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li>Prevents cascading failures across microservices</li>
                  <li>Reduces load on struggling services</li>
                  <li>Fast failure means better user experience</li>
                  <li>Automatic recovery without manual intervention</li>
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
                  <h5 className="font-semibold mb-2">🔗 External API Calls</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Protect against third-party service outages, rate limits, or degradation
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">💾 Database Connections</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Fail fast when database is unreachable, preventing connection pool exhaustion
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">☁️ Cloud Service Dependencies</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Handle partial cloud region outages without global system failure
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🔌 Legacy System Integration</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Isolate flaky legacy systems from modern microservice architecture
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
              <h5 className="font-semibold mb-2">Bulkhead Pattern</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Isolate resources to prevent cascading failures
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Retry Pattern</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Handle transient failures with exponential backoff
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Health Check Pattern</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Monitor service health to trigger circuit breaker
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
