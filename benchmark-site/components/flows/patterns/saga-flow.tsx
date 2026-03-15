'use client';

import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@radix-ui/themes';
import { Button } from '@radix-ui/themes';
import { Badge } from '@radix-ui/themes';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@radix-ui/themes';

type SagaStepStatus = 'pending' | 'running' | 'completed' | 'failed' | 'compensating';

interface SagaStep {
  id: string;
  name: string;
  status: SagaStepStatus;
  compensationName: string;
}

interface SagaTransaction {
  id: string;
  steps: SagaStep[];
  currentStep: number;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'compensating';
  startTime: number;
}

export default function SagaFlow() {
  const [transactions, setTransactions] = useState<SagaTransaction[]>([]);
  const [isSimulating, setIsSimulating] = useState(false);
  const [transactionId, setTransactionId] = useState(0);
  const [failureRate, setFailureRate] = useState(0.3);

  const sagaSteps: Omit<SagaStep, 'status'>[] = [
    { id: '1', name: 'Reserve Inventory', compensationName: 'Release Inventory' },
    { id: '2', name: 'Process Payment', compensationName: 'Refund Payment' },
    { id: '3', name: 'Ship Order', compensationName: 'Cancel Shipment' },
    { id: '4', name: 'Send Confirmation', compensationName: 'Send Cancellation' }
  ];

  useEffect(() => {
    if (!isSimulating) return;

    const interval = setInterval(() => {
      // Start new transaction
      const newTx: SagaTransaction = {
        id: `TX-${transactionId}`,
        steps: sagaSteps.map(step => ({ ...step, status: 'pending' })),
        currentStep: 0,
        status: 'running',
        startTime: Date.now()
      };

      setTransactionId(prev => prev + 1);
      setTransactions(prev => [...prev, newTx]);

      // Process transaction steps
      processTransaction(newTx.id);
    }, 3000);

    return () => clearInterval(interval);
  }, [isSimulating, transactionId, failureRate]);

  const processTransaction = async (txId: string) => {
    let currentStep = 0;
    let failed = false;

    const executeStep = () => {
      return new Promise<void>((resolve) => {
        setTimeout(() => {
          setTransactions(prev => prev.map(tx => {
            if (tx.id !== txId) return tx;

            const newSteps = [...tx.steps];
            if (currentStep < newSteps.length) {
              // Determine if this step should fail
              const shouldFail = Math.random() < failureRate;

              if (shouldFail && !failed) {
                failed = true;
                newSteps[currentStep].status = 'failed';
                return { ...tx, steps: newSteps, status: 'compensating' };
              } else if (!failed) {
                newSteps[currentStep].status = 'completed';
                currentStep++;
                return { ...tx, steps: newSteps, currentStep };

                // Continue to next step if not failed
                if (currentStep < newSteps.length && !failed) {
                  executeStep();
                } else if (!failed) {
                  return { ...tx, steps: newSteps, status: 'completed' };
                }
              }
            }

            return tx;
          }));

          // If failed, start compensation
          if (failed) {
            compensateTransaction(txId, currentStep);
          }

          resolve();
        }, 1000);
      });
    };

    executeStep();
  };

  const compensateTransaction = async (txId: string, failedStepIndex: number) => {
    for (let i = failedStepIndex - 1; i >= 0; i--) {
      await new Promise(resolve => setTimeout(resolve, 800));

      setTransactions(prev => prev.map(tx => {
        if (tx.id !== txId) return tx;

        const newSteps = [...tx.steps];
        newSteps[i].status = 'compensating';

        setTimeout(() => {
          setTransactions(p => p.map(t => {
            if (t.id !== txId) return t;
            const steps = [...t.steps];
            steps[i].status = 'failed'; // Mark as compensated
            return { ...t, steps };
          }));
        }, 500);

        return { ...tx, steps: newSteps };
      }));
    }

    // Mark transaction as fully compensated
    setTransactions(prev => prev.map(tx => {
      if (tx.id !== txId) return tx;
      return { ...tx, status: 'failed' };
    }));
  };

  const reset = () => {
    setTransactions([]);
    setTransactionId(0);
  };

  const activeTransactions = transactions.filter(tx => tx.status === 'running' || tx.status === 'compensating');
  const completedTransactions = transactions.filter(tx => tx.status === 'completed').length;
  const failedTransactions = transactions.filter(tx => tx.status === 'failed').length;

  const codeExample = `// Saga Pattern Implementation
public sealed interface SagaState {
    record Pending() implements SagaState {}
    record Running() implements SagaState {}
    record Completed() implements SagaState {}
    record Compensating() implements SagaState {}
    record Failed() implements SagaState {}
}

public final class SagaCoordinator {
    private final List<SagaStep> steps;
    private int currentStep = 0;

    public Result<Void, SagaError> execute() {
        try {
            // Execute steps forward
            for (int i = 0; i < steps.size(); i++) {
                currentStep = i;
                steps.get(i).execute();
            }
            return Result.success(null);
        } catch (Exception e) {
            // Compensate completed steps
            compensate(currentStep - 1);
            return Result.failure(new SagaError("Saga failed", e));
        }
    }

    private void compensate(int fromIndex) {
        // Execute compensating actions in reverse
        for (int i = fromIndex; i >= 0; i--) {
            steps.get(i).compensate();
        }
    }
}

// Individual saga step
public record SagaStep(
    Runnable action,
    Runnable compensation
) {
    public void execute() { action.run(); }
    public void compensate() { compensation.run(); }
}`;

  return (
    <div className="space-y-6">
      {/* Controls */}
      <Card>
        <CardHeader>
          <CardTitle>Saga Pattern</CardTitle>
          <CardDescription>
            Distributed transaction management using compensating actions for failure recovery
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
              <label className="text-sm">Failure Rate:</label>
              <input
                type="range"
                value={failureRate * 100}
                onChange={(e) => setFailureRate(Number(e.target.value) / 100)}
                className="w-32"
                min="0"
                max="100"
              />
              <span className="text-sm font-mono">{(failureRate * 100).toFixed(0)}%</span>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Statistics */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Total Transactions</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-blue-600 dark:text-blue-400">{transactions.length}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Active</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-yellow-600 dark:text-yellow-400">{activeTransactions.length}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Completed</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-green-600 dark:text-green-400">{completedTransactions}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Failed/Compensated</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-red-600 dark:text-red-400">{failedTransactions}</div>
          </CardContent>
        </Card>
      </div>

      {/* Active Transactions */}
      {activeTransactions.length > 0 && (
        <div className="space-y-4">
          <h3 className="text-lg font-semibold">Active Transactions</h3>
          {activeTransactions.slice(-3).map(tx => (
            <Card key={tx.id} className="border-2">
              <CardHeader className="pb-3">
                <div className="flex items-center justify-between">
                  <CardTitle className="text-lg">{tx.id}</CardTitle>
                  <Badge
                    variant={tx.status === 'running' ? 'default' : 'destructive'}
                    className={tx.status === 'compensating' ? 'animate-pulse' : ''}
                  >
                    {tx.status.toUpperCase()}
                  </Badge>
                </div>
              </CardHeader>
              <CardContent>
                <div className="space-y-3">
                  {tx.steps.map((step, index) => (
                    <div key={step.id} className="relative">
                      <div className="flex items-center gap-4">
                        {/* Step indicator */}
                        <div className={`w-10 h-10 rounded-full flex items-center justify-center text-white font-bold ${
                          step.status === 'completed' ? 'bg-green-500' :
                          step.status === 'running' ? 'bg-blue-500 animate-pulse' :
                          step.status === 'failed' ? 'bg-red-500' :
                          step.status === 'compensating' ? 'bg-orange-500 animate-pulse' :
                          'bg-slate-300'
                        }`}>
                          {index + 1}
                        </div>

                        {/* Step details */}
                        <div className="flex-1">
                          <div className="flex items-center justify-between">
                            <div>
                              <div className="font-semibold">{step.name}</div>
                              {step.status === 'compensating' && (
                                <div className="text-sm text-orange-600 dark:text-orange-400">
                                  Compensating: {step.compensationName}
                                </div>
                              )}
                            </div>
                            <Badge variant="outline" className={
                              step.status === 'completed' ? 'border-green-500 text-green-600' :
                              step.status === 'running' ? 'border-blue-500 text-blue-600' :
                              step.status === 'failed' ? 'border-red-500 text-red-600' :
                              step.status === 'compensating' ? 'border-orange-500 text-orange-600' :
                              ''
                            }>
                              {step.status}
                            </Badge>
                          </div>
                        </div>
                      </div>

                      {/* Connection line */}
                      {index < tx.steps.length - 1 && (
                        <div className="ml-5 w-0.5 h-6 bg-slate-300 dark:bg-slate-600" />
                      )}
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Saga Flow Diagram */}
      <Card>
        <CardHeader>
          <CardTitle>Saga Flow Diagram</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="bg-slate-50 dark:bg-slate-900 rounded-lg p-6">
            {/* Forward flow */}
            <div className="mb-8">
              <h4 className="text-sm font-semibold mb-3 text-slate-600 dark:text-slate-400">Forward Path (Execute)</h4>
              <div className="flex items-center gap-2">
                {sagaSteps.map((step, i) => (
                  <div key={step.id} className="flex items-center">
                    <div className="bg-green-100 dark:bg-green-900/20 border-2 border-green-500 rounded-lg p-3">
                      <div className="text-sm font-semibold text-green-700 dark:text-green-400">{step.name}</div>
                    </div>
                    {i < sagaSteps.length - 1 && (
                      <div className="text-2xl text-green-500">→</div>
                    )}
                  </div>
                ))}
              </div>
            </div>

            {/* Compensation flow */}
            <div>
              <h4 className="text-sm font-semibold mb-3 text-slate-600 dark:text-slate-400">Reverse Path (Compensate)</h4>
              <div className="flex items-center gap-2">
                {sagaSteps.slice().reverse().map((step, i) => (
                  <div key={step.id} className="flex items-center">
                    <div className="bg-red-100 dark:bg-red-900/20 border-2 border-red-500 rounded-lg p-3">
                      <div className="text-sm font-semibold text-red-700 dark:text-red-400">{step.compensationName}</div>
                    </div>
                    {i < sagaSteps.length - 1 && (
                      <div className="text-2xl text-red-500">←</div>
                    )}
                  </div>
                ))}
              </div>
            </div>

            {/* Failure point indicator */}
            <div className="mt-6 p-4 bg-yellow-50 dark:bg-yellow-900/20 rounded-lg">
              <div className="flex items-center gap-2">
                <span className="text-2xl">⚠️</span>
                <div>
                  <div className="text-sm font-semibold text-yellow-700 dark:text-yellow-400">
                    Failure at any step triggers compensation
                  </div>
                  <div className="text-xs text-slate-600 dark:text-slate-400">
                    All completed steps are rolled back in reverse order
                  </div>
                </div>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Transaction History */}
      {transactions.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Transaction History</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-2 max-h-64 overflow-y-auto">
              {transactions.slice().reverse().slice(0, 10).map(tx => (
                <div key={tx.id} className="flex items-center justify-between p-3 border rounded-lg">
                  <div className="flex items-center gap-3">
                    <Badge
                      variant={
                        tx.status === 'completed' ? 'default' :
                        tx.status === 'failed' ? 'destructive' :
                        'secondary'
                      }
                    >
                      {tx.status}
                    </Badge>
                    <span className="font-mono text-sm">{tx.id}</span>
                  </div>
                  <div className="text-xs text-slate-500">
                    {tx.steps.filter(s => s.status === 'completed').length} / {tx.steps.length} steps
                  </div>
                </div>
              ))}
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
                  <li><strong>Forward Path:</strong> Execute each step in sequence</li>
                  <li><strong>Failure Detection:</strong> If any step fails, stop execution</li>
                  <li><strong>Compensation:</strong> Rollback completed steps in reverse order</li>
                  <li><strong>Atomic Result:</strong> Either all steps complete or all are rolled back</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Key Properties</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li><strong>Compensating Actions:</strong> Each step must have an undo operation</li>
                  <li><strong>Eventual Consistency:</strong> System may be temporarily inconsistent</li>
                  <li><strong>Long-running:</strong> Unlike ACID transactions, can span minutes/hours</li>
                  <li><strong>Distributed:</strong> Steps can run across different services</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Benefits</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li>Manages distributed transactions without distributed locks</li>
                  <li>Handles long-running business processes</li>
                  <li>Provides failure recovery without manual intervention</li>
                  <li>Enables loose coupling between services</li>
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
                  <h5 className="font-semibold mb-2">🛒 E-commerce Order Processing</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Reserve inventory → Process payment → Ship → Confirm (cancel on failure)
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">✈️ Travel Booking</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Book flight → Reserve hotel → Rent car → Send confirmation
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🏦 Financial Transactions</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Debit account → Transfer → Credit recipient → Record transaction
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🔄 Data Migration</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Validate → Transform → Load → Verify (rollback on error)
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
              <h5 className="font-semibold mb-2">Two-Phase Commit</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                ACID transactions with coordinator
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Event Sourcing</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Store events for state reconstruction
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">CQRS</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Separate read and write models
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
