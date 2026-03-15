'use client';

import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@radix-ui/themes';
import { Button } from '@radix-ui/themes';
import { Badge } from '@radix-ui/themes';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@radix-ui/themes';

interface TenantSupervisor {
  id: string;
  name: string;
  status: 'running' | 'degraded' | 'failed';
  processes: number;
  maxProcesses: number;
  cpuUsage: number;
  memoryUsage: number;
  slaViolation: boolean;
  lastFailure: number;
}

interface TenantConfig {
  maxProcesses: number;
  cpuThreshold: number;
  memoryThreshold: number;
}

export default function MultiTenantFlow() {
  const [tenants, setTenants] = useState<TenantSupervisor[]>([
    { id: 'tenant1', name: 'Acme Corp', status: 'running', processes: 5, maxProcesses: 10, cpuUsage: 45, memoryUsage: 50, slaViolation: false, lastFailure: 0 },
    { id: 'tenant2', name: 'Globex Inc', status: 'running', processes: 7, maxProcesses: 10, cpuUsage: 62, memoryUsage: 55, slaViolation: false, lastFailure: 0 },
    { id: 'tenant3', name: 'Soylent Corp', status: 'running', processes: 3, maxProcesses: 10, cpuUsage: 30, memoryUsage: 35, slaViolation: false, lastFailure: 0 },
    { id: 'tenant4', name: 'Initech', status: 'running', processes: 8, maxProcesses: 10, cpuUsage: 78, memoryUsage: 72, slaViolation: false, lastFailure: 0 }
  ]);

  const [config, setConfig] = useState<TenantConfig>({
    maxProcesses: 10,
    cpuThreshold: 80,
    memoryThreshold: 85
  });

  const [isSimulating, setIsSimulating] = useState(false);
  const [selectedTenant, setSelectedTenant] = useState<string | null>(null);

  useEffect(() => {
    if (!isSimulating) return;

    const interval = setInterval(() => {
      setTenants(prev => prev.map(tenant => {
        // Simulate resource usage changes
        const processChange = Math.floor(Math.random() * 3) - 1; // -1, 0, or +1
        const newProcesses = Math.max(1, Math.min(config.maxProcesses, tenant.processes + processChange));

        const cpuChange = (Math.random() - 0.5) * 20;
        const newCpu = Math.max(10, Math.min(100, tenant.cpuUsage + cpuChange));

        const memChange = (Math.random() - 0.5) * 15;
        const newMem = Math.max(20, Math.min(100, tenant.memoryUsage + memChange));

        // Check SLA violations
        const slaViolated = newCpu > config.cpuThreshold || newMem > config.memoryThreshold;

        // Determine status
        let newStatus: TenantSupervisor['status'] = 'running';
        if (slaViolated) {
          newStatus = 'degraded';
        }
        if (newCpu > 95 || newMem > 95) {
          newStatus = 'failed';
        }

        return {
          ...tenant,
          processes: newProcesses,
          cpuUsage: Math.round(newCpu),
          memoryUsage: Math.round(newMem),
          slaViolation: slaViolated,
          status: newStatus,
          lastFailure: newStatus === 'failed' ? Date.now() : tenant.lastFailure
        };
      }));
    }, 2000);

    return () => clearInterval(interval);
  }, [isSimulating, config]);

  const triggerSpike = (tenantId: string) => {
    setTenants(prev => prev.map(t => {
      if (t.id === tenantId) {
        return {
          ...t,
          cpuUsage: Math.min(100, t.cpuUsage + 30),
          memoryUsage: Math.min(100, t.memoryUsage + 25),
          processes: Math.min(t.maxProcesses, t.processes + 2),
          status: 'degraded'
        };
      }
      return t;
    }));
  };

  const reset = () => {
    setTenants([
      { id: 'tenant1', name: 'Acme Corp', status: 'running', processes: 5, maxProcesses: 10, cpuUsage: 45, memoryUsage: 50, slaViolation: false, lastFailure: 0 },
      { id: 'tenant2', name: 'Globex Inc', status: 'running', processes: 7, maxProcesses: 10, cpuUsage: 62, memoryUsage: 55, slaViolation: false, lastFailure: 0 },
      { id: 'tenant3', name: 'Soylent Corp', status: 'running', processes: 3, maxProcesses: 10, cpuUsage: 30, memoryUsage: 35, slaViolation: false, lastFailure: 0 },
      { id: 'tenant4', name: 'Initech', status: 'running', processes: 8, maxProcesses: 10, cpuUsage: 78, memoryUsage: 72, slaViolation: false, lastFailure: 0 }
    ]);
    setSelectedTenant(null);
  };

  const getStatusColor = (status: TenantSupervisor['status']) => {
    switch (status) {
      case 'running': return 'bg-green-500';
      case 'degraded': return 'bg-yellow-500';
      case 'failed': return 'bg-red-500';
    }
  };

  const getStatusBorderColor = (status: TenantSupervisor['status']) => {
    switch (status) {
      case 'running': return 'border-green-500';
      case 'degraded': return 'border-yellow-500';
      case 'failed': return 'border-red-500';
    }
  };

  const slaViolations = tenants.filter(t => t.slaViolation).length;
  const failedTenants = tenants.filter(t => t.status === 'failed').length;

  const codeExample = `// Multi-Tenant Supervisor Isolation
public final class TenantSupervisor {
    private final String tenantId;
    private final Supervisor supervisor;
    private final ResourceLimits limits;

    public void startTenantProcess(ProcessSpec spec) {
        // Enforce per-tenant resource limits
        if (!limits.canAllocate(spec)) {
            throw new ResourceLimitExceeded(
                "Tenant " + tenantId + " at capacity"
            );
        }

        // Start process in tenant's supervisor tree
        supervisor.startChild(spec);
    }

    // Isolated failure handling
    private void handleProcessCrash(ProcessId pid, Throwable reason) {
        // Only restart this tenant's processes
        // Other tenants are unaffected
        supervisor.restartChild(pid);
    }
}

// Per-tenant resource allocation
TenantSupervisor acme = TenantSupervisor.create("acme")
    .withMaxProcesses(100)
    .withMaxMemory(512, MemoryUnit.MB)
    .withMaxCpu(0.5) // 50% of one core
    .build();

TenantSupervisor globex = TenantSupervisor.create("globex")
    .withMaxProcesses(200)
    .withMaxMemory(1024, MemoryUnit.MB)
    .withMaxCpu(1.0) // 100% of one core
    .build();`;

  return (
    <div className="space-y-6">
      {/* Controls */}
      <Card>
        <CardHeader>
          <CardTitle>Multi-Tenant Isolation Pattern</CardTitle>
          <CardDescription>
            Per-tenant supervisor trees ensuring resource isolation and SLA enforcement
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
              Click a tenant to trigger resource spike
            </div>
          </div>
        </CardContent>
      </Card>

      {/* System Overview */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Active Tenants</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-blue-600 dark:text-blue-400">{tenants.length}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">SLA Violations</CardTitle>
          </CardHeader>
          <CardContent>
            <div className={`text-3xl font-bold ${slaViolations > 0 ? 'text-red-600 dark:text-red-400' : 'text-green-600 dark:text-green-400'}`}>
              {slaViolations}
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Failed Tenants</CardTitle>
          </CardHeader>
          <CardContent>
            <div className={`text-3xl font-bold ${failedTenants > 0 ? 'text-red-600 dark:text-red-400' : 'text-green-600 dark:text-green-400'}`}>
              {failedTenants}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Tenant Isolation Visualization */}
      <Card>
        <CardHeader>
          <CardTitle>Tenant Supervisor Trees</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="bg-slate-50 dark:bg-slate-900 rounded-lg p-6">
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
              {tenants.map(tenant => (
                <div
                  key={tenant.id}
                  className={`border-2 rounded-lg p-4 cursor-pointer transition-all hover:shadow-lg ${getStatusBorderColor(tenant.status)}`}
                  onClick={() => triggerSpike(tenant.id)}
                >
                  <div className="flex items-center justify-between mb-3">
                    <h3 className="font-semibold">{tenant.name}</h3>
                    <Badge className={getStatusColor(tenant.status)}>
                      {tenant.status}
                    </Badge>
                  </div>

                  {/* Process Tree */}
                  <div className="mb-3">
                    <div className="text-xs text-slate-600 dark:text-slate-400 mb-1">Supervisor Tree</div>
                    <div className="flex flex-col items-center">
                      <div className="w-8 h-8 bg-blue-500 rounded-full flex items-center justify-center text-white text-xs mb-1">
                        S
                      </div>
                      <div className="w-0.5 h-4 bg-slate-400" />
                      <div className="flex gap-1">
                        {Array.from({ length: Math.min(tenant.processes, 4) }).map((_, i) => (
                          <div key={i} className="w-4 h-4 bg-green-500 rounded-sm" />
                        ))}
                        {tenant.processes > 4 && (
                          <div className="w-4 h-4 bg-slate-400 rounded-sm flex items-center justify-center text-xs">
                            +
                          </div>
                        )}
                      </div>
                    </div>
                    <div className="text-xs text-center text-slate-600 dark:text-slate-400 mt-1">
                      {tenant.processes}/{tenant.maxProcesses} processes
                    </div>
                  </div>

                  {/* Resource Usage */}
                  <div className="space-y-2">
                    <div>
                      <div className="flex justify-between text-xs mb-1">
                        <span>CPU</span>
                        <span>{tenant.cpuUsage}%</span>
                      </div>
                      <div className="w-full bg-slate-200 dark:bg-slate-700 rounded-full h-2">
                        <div
                          className={`h-2 rounded-full ${tenant.cpuUsage > config.cpuThreshold ? 'bg-red-500' : tenant.cpuUsage > config.cpuThreshold * 0.8 ? 'bg-yellow-500' : 'bg-green-500'}`}
                          style={{ width: `${tenant.cpuUsage}%` }}
                        />
                      </div>
                    </div>

                    <div>
                      <div className="flex justify-between text-xs mb-1">
                        <span>Memory</span>
                        <span>{tenant.memoryUsage}%</span>
                      </div>
                      <div className="w-full bg-slate-200 dark:bg-slate-700 rounded-full h-2">
                        <div
                          className={`h-2 rounded-full ${tenant.memoryUsage > config.memoryThreshold ? 'bg-red-500' : tenant.memoryUsage > config.memoryThreshold * 0.8 ? 'bg-yellow-500' : 'bg-green-500'}`}
                          style={{ width: `${tenant.memoryUsage}%` }}
                        />
                      </div>
                    </div>
                  </div>

                  {tenant.slaViolation && (
                    <div className="mt-3 p-2 bg-red-50 dark:bg-red-900/20 rounded text-xs">
                      <div className="font-semibold text-red-600 dark:text-red-400">SLA Violation</div>
                      <div className="text-slate-600 dark:text-slate-400">
                        Resource limits exceeded
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>

            <div className="mt-4 text-xs text-slate-600 dark:text-slate-400 text-center">
              Click a tenant card to simulate resource spike
            </div>
          </div>
        </CardContent>
      </Card>

      {/* SLA Thresholds Configuration */}
      <Card>
        <CardHeader>
          <CardTitle>SLA Thresholds</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            <div>
              <label className="text-sm font-medium text-slate-700 dark:text-slate-300">Max Processes per Tenant</label>
              <input
                type="number"
                value={config.maxProcesses}
                onChange={(e) => setConfig(prev => ({ ...prev, maxProcesses: Number(e.target.value) }))}
                className="w-full mt-1 px-3 py-2 border rounded"
                min="1"
                max="100"
              />
            </div>

            <div>
              <label className="text-sm font-medium text-slate-700 dark:text-slate-300">CPU Threshold (%)</label>
              <input
                type="number"
                value={config.cpuThreshold}
                onChange={(e) => setConfig(prev => ({ ...prev, cpuThreshold: Number(e.target.value) }))}
                className="w-full mt-1 px-3 py-2 border rounded"
                min="50"
                max="100"
              />
            </div>

            <div>
              <label className="text-sm font-medium text-slate-700 dark:text-slate-300">Memory Threshold (%)</label>
              <input
                type="number"
                value={config.memoryThreshold}
                onChange={(e) => setConfig(prev => ({ ...prev, memoryThreshold: Number(e.target.value) }))}
                className="w-full mt-1 px-3 py-2 border rounded"
                min="50"
                max="100"
              />
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Isolation Demonstration */}
      {(slaViolations > 0 || failedTenants > 0) && (
        <Card className="border-blue-500">
          <CardHeader>
            <CardTitle className="text-blue-600 dark:text-blue-400">Key Pattern Benefit</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Notice that tenants with SLA violations or failures don't affect other tenants:
              </p>
              <ul className="list-disc pl-6 text-sm text-slate-600 dark:text-slate-400 space-y-1">
                <li>Each tenant has its own supervisor tree</li>
                <li>Process failures are contained to the tenant</li>
                <li>Resource exhaustion is isolated per tenant</li>
                <li>One tenant's spike doesn't degrade others</li>
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
                  <li><strong>Per-Tenant Supervisors:</strong> Each tenant gets its own supervisor tree</li>
                  <li><strong>Resource Limits:</strong> CPU, memory, and process limits per tenant</li>
                  <li><strong>SLA Enforcement:</strong> Thresholds trigger alerts or throttling</li>
                  <li><strong>Failure Isolation:</strong> One tenant's crash doesn't affect others</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Key Properties</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li><strong>Fair Allocation:</strong> Guaranteed minimum resources per tenant</li>
                  <li><strong>Burst Tolerance:</strong> Allow temporary spikes within limits</li>
                  <li><strong>Independent Scaling:</strong> Scale tenants based on load</li>
                  <li><strong>Graceful Degradation:</strong> Throttle vs fail based on policy</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Benefits</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li>Prevents noisy neighbor problems</li>
                  <li>Ensures predictable performance per tenant</li>
                  <li>Simplifies capacity planning and pricing</li>
                  <li>Enables tiered service levels</li>
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
                  <h5 className="font-semibold mb-2">☁️ SaaS Applications</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Multiple customers on shared infrastructure with guaranteed performance
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🏢 Enterprise Platforms</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Department or division-level isolation within organization
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">📱 API Platforms</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Per-API key rate limiting and resource allocation
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🎮 Gaming Backend</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Per-game server isolation to prevent one game from affecting others
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
                Resource isolation for components
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Supervisor</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Hierarchical fault containment
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Circuit Breaker</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Per-tenant failure isolation
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
