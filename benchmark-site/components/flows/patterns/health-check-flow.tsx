'use client';

import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@radix-ui/themes';
import { Button } from '@radix-ui/themes';
import { Badge } from '@radix-ui/themes';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@radix-ui/themes';

type HealthStatus = 'healthy' | 'degraded' | 'unhealthy';

interface ServiceHealth {
  id: string;
  name: string;
  status: HealthStatus;
  dependencies: string[];
  lastCheck: number;
  responseTime: number;
  failureCount: number;
}

export default function HealthCheckFlow() {
  const [services, setServices] = useState<ServiceHealth[]>([
    { id: 'api-gateway', name: 'API Gateway', status: 'healthy', dependencies: ['auth-service', 'user-service'], lastCheck: Date.now(), responseTime: 50, failureCount: 0 },
    { id: 'auth-service', name: 'Auth Service', status: 'healthy', dependencies: ['database'], lastCheck: Date.now(), responseTime: 30, failureCount: 0 },
    { id: 'user-service', name: 'User Service', status: 'healthy', dependencies: ['database', 'cache'], lastCheck: Date.now(), responseTime: 40, failureCount: 0 },
    { id: 'database', name: 'Database', status: 'healthy', dependencies: [], lastCheck: Date.now(), responseTime: 10, failureCount: 0 },
    { id: 'cache', name: 'Cache', status: 'healthy', dependencies: [], lastCheck: Date.now(), responseTime: 5, failureCount: 0 }
  ]);

  const [isSimulating, setIsSimulating] = useState(false);
  const [selectedService, setSelectedService] = useState<string | null>(null);

  useEffect(() => {
    if (!isSimulating) return;

    const interval = setInterval(() => {
      setServices(prev => prev.map(service => {
        // Random status changes
        const roll = Math.random();
        let newStatus: HealthStatus = service.status;
        let newResponseTime = service.responseTime + (Math.random() - 0.5) * 20;
        newResponseTime = Math.max(5, Math.min(500, newResponseTime));

        if (roll < 0.05) {
          newStatus = 'unhealthy';
        } else if (roll < 0.1) {
          newStatus = 'degraded';
        } else if (roll > 0.95 && service.status !== 'healthy') {
          newStatus = 'healthy';
        }

        // Check dependencies
        const dependenciesHealth = prev.filter(s => service.dependencies.includes(s.id));
        const hasUnhealthyDependency = dependenciesHealth.some(d => d.status === 'unhealthy');

        if (hasUnhealthyDependency && newStatus === 'healthy') {
          newStatus = 'degraded';
        }

        return {
          ...service,
          status: newStatus,
          lastCheck: Date.now(),
          responseTime: Math.round(newResponseTime),
          failureCount: newStatus === 'unhealthy' ? service.failureCount + 1 : 0
        };
      }));
    }, 2000);

    return () => clearInterval(interval);
  }, [isSimulating]);

  const triggerFailure = (serviceId: string) => {
    setServices(prev => prev.map(s => {
      if (s.id === serviceId) {
        return { ...s, status: 'unhealthy', failureCount: s.failureCount + 1, lastCheck: Date.now() };
      }
      return s;
    }));
  };

  const reset = () => {
    setServices([
      { id: 'api-gateway', name: 'API Gateway', status: 'healthy', dependencies: ['auth-service', 'user-service'], lastCheck: Date.now(), responseTime: 50, failureCount: 0 },
      { id: 'auth-service', name: 'Auth Service', status: 'healthy', dependencies: ['database'], lastCheck: Date.now(), responseTime: 30, failureCount: 0 },
      { id: 'user-service', name: 'User Service', status: 'healthy', dependencies: ['database', 'cache'], lastCheck: Date.now(), responseTime: 40, failureCount: 0 },
      { id: 'database', name: 'Database', status: 'healthy', dependencies: [], lastCheck: Date.now(), responseTime: 10, failureCount: 0 },
      { id: 'cache', name: 'Cache', status: 'healthy', dependencies: [], lastCheck: Date.now(), responseTime: 5, failureCount: 0 }
    ]);
    setSelectedService(null);
  };

  const getStatusColor = (status: HealthStatus) => {
    switch (status) {
      case 'healthy': return 'bg-green-500';
      case 'degraded': return 'bg-yellow-500';
      case 'unhealthy': return 'bg-red-500';
    }
  };

  const getStatusBorderColor = (status: HealthStatus) => {
    switch (status) {
      case 'healthy': return 'border-green-500';
      case 'degraded': return 'border-yellow-500';
      case 'unhealthy': return 'border-red-500';
    }
  };

  const healthyCount = services.filter(s => s.status === 'healthy').length;
  const degradedCount = services.filter(s => s.status === 'degraded').length;
  const unhealthyCount = services.filter(s => s.status === 'unhealthy').length;

  const codeExample = `// Health Check Implementation
public sealed interface HealthStatus {
    record Healthy() implements HealthStatus {}
    record Degraded() implements HealthStatus {}
    record Unhealthy() implements HealthStatus {}
}

public final class HealthChecker {
    private final Map<String, HealthCheck> checks;

    public HealthResult checkHealth(String service) {
        HealthCheck check = checks.get(service);
        HealthStatus status = check.execute();

        // Check dependencies
        for (String dep : check.dependencies()) {
            HealthStatus depStatus = checkHealth(dep).status();
            if (depStatus instanceof HealthStatus.Unhealthy) {
                return new HealthResult(
                    new HealthStatus.Degraded(),
                    Map.of("dependency", dep + " is down")
                );
            }
        }

        return new HealthResult(status, Map.of());
    }
}

// Individual health check
public interface HealthCheck {
    HealthStatus execute();
    List<String> dependencies();
}

// Usage in JOTP Supervisor
Supervisor.create()
    .addChild(
        ChildSpec.create("api-gateway", ApiGateway::new)
            .withHealthCheck(() -> checkDatabase())
    )
    .start();`;

  return (
    <div className="space-y-6">
      {/* Controls */}
      <Card>
        <CardHeader>
          <CardTitle>Health Check Pattern</CardTitle>
          <CardDescription>
            Monitor service health and detect cascading failures across dependencies
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
            <div className="text-sm text-slate-600 dark:text-slate-400">
              Click a service to trigger failure
            </div>
          </div>
        </CardContent>
      </Card>

      {/* System Health Overview */}
      <Card>
        <CardHeader>
          <CardTitle>System Health Overview</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-3 gap-4">
            <div className="text-center p-4 bg-green-50 dark:bg-green-900/20 rounded-lg">
              <div className="text-3xl font-bold text-green-600 dark:text-green-400">{healthyCount}</div>
              <div className="text-sm text-slate-600 dark:text-slate-400">Healthy</div>
            </div>
            <div className="text-center p-4 bg-yellow-50 dark:bg-yellow-900/20 rounded-lg">
              <div className="text-3xl font-bold text-yellow-600 dark:text-yellow-400">{degradedCount}</div>
              <div className="text-sm text-slate-600 dark:text-slate-400">Degraded</div>
            </div>
            <div className="text-center p-4 bg-red-50 dark:bg-red-900/20 rounded-lg">
              <div className="text-3xl font-bold text-red-600 dark:text-red-400">{unhealthyCount}</div>
              <div className="text-sm text-slate-600 dark:text-slate-400">Unhealthy</div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Service Dependency Graph */}
      <Card>
        <CardHeader>
          <CardTitle>Service Dependency Graph</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="bg-slate-50 dark:bg-slate-900 rounded-lg p-6 min-h-96">
            <svg className="w-full h-96" viewBox="0 0 800 400">
              {/* Service Nodes */}
              {services.map((service, index) => {
                const positions: Record<string, { x: number; y: number }> = {
                  'api-gateway': { x: 400, y: 50 },
                  'auth-service': { x: 250, y: 150 },
                  'user-service': { x: 550, y: 150 },
                  'database': { x: 350, y: 300 },
                  'cache': { x: 600, y: 300 }
                };

                const pos = positions[service.id];
                const isSelected = selectedService === service.id;

                return (
                  <g key={service.id}>
                    {/* Dependencies (lines) */}
                    {service.dependencies.map(depId => {
                      const depPos = positions[depId];
                      const depService = services.find(s => s.id === depId);
                      const isUnhealthy = depService?.status === 'unhealthy';

                      return (
                        <line
                          key={depId}
                          x1={pos.x}
                          y1={pos.y + 25}
                          x2={depPos.x}
                          y2={depPos.y - 25}
                          stroke={isUnhealthy ? '#ef4444' : '#94a3b8'}
                          strokeWidth="2"
                          strokeDasharray={isUnhealthy ? '5,5' : 'none'}
                          className={isUnhealthy ? 'animate-pulse' : ''}
                        />
                      );
                    })}

                    {/* Service Node */}
                    <g
                      transform={`translate(${pos.x - 60}, ${pos.y - 25})`}
                      className="cursor-pointer"
                      onClick={() => triggerFailure(service.id)}
                    >
                      <rect
                        width="120"
                        height="50"
                        rx="8"
                        fill="white"
                        stroke={isSelected ? '#3b82f6' : service.status === 'healthy' ? '#22c55e' : service.status === 'degraded' ? '#eab308' : '#ef4444'}
                        strokeWidth="3"
                        className={isSelected ? 'animate-pulse' : ''}
                      />
                      <text
                        x="60"
                        y="20"
                        textAnchor="middle"
                        className="text-xs font-semibold fill-slate-900"
                      >
                        {service.name}
                      </text>
                      <text
                        x="60"
                        y="38"
                        textAnchor="middle"
                        className="text-xs fill-slate-600"
                      >
                        {service.responseTime}ms
                      </text>
                    </g>

                    {/* Status Badge */}
                    <circle
                      cx={pos.x + 50}
                      cy={pos.y - 15}
                      r="8"
                      fill={service.status === 'healthy' ? '#22c55e' : service.status === 'degraded' ? '#eab308' : '#ef4444'}
                      className={service.status !== 'healthy' ? 'animate-pulse' : ''}
                    />
                  </g>
                );
              })}
            </svg>

            <div className="flex gap-4 mt-4 text-sm">
              <div className="flex items-center gap-2">
                <div className="w-4 h-4 rounded-full bg-green-500" />
                <span>Healthy</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="w-4 h-4 rounded-full bg-yellow-500" />
                <span>Degraded</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="w-4 h-4 rounded-full bg-red-500" />
                <span>Unhealthy</span>
              </div>
              <div className="ml-auto text-slate-600 dark:text-slate-400">
                Click a service to trigger failure
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Service Details */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {services.map(service => (
          <Card key={service.id} className={`border-2 ${getStatusBorderColor(service.status)}`}>
            <CardHeader className="pb-3">
              <div className="flex items-center justify-between">
                <CardTitle className="text-lg">{service.name}</CardTitle>
                <Badge className={getStatusColor(service.status)}>{service.status}</Badge>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="flex justify-between text-sm">
                <span className="text-slate-600 dark:text-slate-400">Response Time</span>
                <span className="font-mono">{service.responseTime}ms</span>
              </div>

              <div className="flex justify-between text-sm">
                <span className="text-slate-600 dark:text-slate-400">Failures</span>
                <span className="font-mono">{service.failureCount}</span>
              </div>

              {service.dependencies.length > 0 && (
                <div>
                  <div className="text-sm text-slate-600 dark:text-slate-400 mb-1">Dependencies</div>
                  <div className="flex flex-wrap gap-1">
                    {service.dependencies.map(depId => {
                      const dep = services.find(s => s.id === depId);
                      return (
                        <Badge
                          key={depId}
                          variant="outline"
                          className={dep?.status === 'unhealthy' ? 'border-red-500 text-red-600' : ''}
                        >
                          {dep?.name}
                        </Badge>
                      );
                    })}
                  </div>
                </div>
              )}

              {service.status !== 'healthy' && (
                <div className="p-2 bg-red-50 dark:bg-red-900/20 rounded text-xs">
                  {service.status === 'degraded' ? '⚠️ Operating with reduced capacity' : '🔴 Service unavailable'}
                </div>
              )}
            </CardContent>
          </Card>
        ))}
      </div>

      {/* Cascading Failure Warning */}
      {(unhealthyCount > 0 || degradedCount > 0) && (
        <Card className="border-red-500">
          <CardHeader>
            <CardTitle className="text-red-600 dark:text-red-400">⚠️ Cascading Failure Detected</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Services are showing degraded health. This may indicate:
              </p>
              <ul className="list-disc pl-6 text-sm text-slate-600 dark:text-slate-400 space-y-1">
                <li>Dependency failures causing upstream services to degrade</li>
                <li>Network latency or connectivity issues</li>
                <li>Resource exhaustion (memory, CPU, connections)</li>
              </ul>
              <Button onClick={reset} variant="outline" className="mt-4">
                Simulate Recovery
              </Button>
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
                  <li><strong>Periodic Checks:</strong> Services expose health endpoints polled by monitoring</li>
                  <li><strong>Dependency Awareness:</strong> Health checks include downstream service status</li>
                  <li><strong>Status Propagation:</strong> Failures cascade up through dependency graph</li>
                  <li><strong>Self-Healing:</strong> Unhealthy services can be restarted or removed from load balancer</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Health Indicators</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li><strong>Healthy:</strong> All checks passing, normal operation</li>
                  <li><strong>Degraded:</strong> Service works but with limitations (slow, some features down)</li>
                  <li><strong>Unhealthy:</strong> Service is down or non-functional</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Benefits</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li>Early detection of failures before users notice</li>
                  <li>Automated remediation (restart, scaling, circuit breaking)</li>
                  <li>Visibility into system health across dependencies</li>
                  <li>Supports blue-green and canary deployments</li>
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
                  <h5 className="font-semibold mb-2">🔍 Load Balancer Health Checks</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Remove unhealthy instances from rotation automatically
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🚨 Alerting & Paging</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Trigger alerts when services become unhealthy
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🔄 Auto-Scaling</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Scale up when services are degraded due to load
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🚢 Deployment Verification</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Check health after deployment before shifting traffic
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
                Fail fast based on health checks
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Supervisor</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Auto-restart unhealthy processes
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Bulkhead</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Isolate failing services
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
