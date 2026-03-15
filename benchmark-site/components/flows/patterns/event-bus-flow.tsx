'use client';

import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@radix-ui/themes';
import { Button } from '@radix-ui/themes';
import { Badge } from '@radix-ui/themes';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@radix-ui/themes';

interface EventHandler {
  id: string;
  name: string;
  eventsHandled: number;
  eventsDropped: number;
  status: 'active' | 'crashed' | 'recovering';
  lastEvent: number;
}

interface EventBusEvent {
  id: string;
  type: string;
  timestamp: number;
  handlersCompleted: number;
  handlersTotal: number;
}

export default function EventBusFlow() {
  const [handlers, setHandlers] = useState<EventHandler[]>([
    { id: 'h1', name: 'Logger Handler', eventsHandled: 0, eventsDropped: 0, status: 'active', lastEvent: 0 },
    { id: 'h2', name: 'Metrics Handler', eventsHandled: 0, eventsDropped: 0, status: 'active', lastEvent: 0 },
    { id: 'h3', name: 'Notification Handler', eventsHandled: 0, eventsDropped: 0, status: 'active', lastEvent: 0 },
    { id: 'h4', name: 'Audit Handler', eventsHandled: 0, eventsDropped: 0, status: 'active', lastEvent: 0 }
  ]);

  const [events, setEvents] = useState<EventBusEvent[]>([]);
  const [isSimulating, setIsSimulating] = useState(false);
  const [eventId, setEventId] = useState(0);
  const [crashRate, setCrashRate] = useState(0.1);

  useEffect(() => {
    if (!isSimulating) return;

    const interval = setInterval(() => {
      // Publish new event
      const newEvent: EventBusEvent = {
        id: `EVT-${eventId}`,
        type: ['UserCreated', 'OrderPlaced', 'PaymentProcessed', 'ItemShipped'][Math.floor(Math.random() * 4)],
        timestamp: Date.now(),
        handlersCompleted: 0,
        handlersTotal: handlers.filter(h => h.status === 'active').length
      };

      setEventId(prev => prev + 1);
      setEvents(prev => [newEvent, ...prev].slice(0, 20));

      // Simulate handlers processing
      handlers.forEach(handler => {
        if (handler.status === 'crashed') {
          // Handler is crashed, event is dropped for this handler
          return;
        }

        // Random crash
        if (Math.random() < crashRate) {
          setHandlers(prev => prev.map(h => {
            if (h.id === handler.id) {
              return { ...h, status: 'crashed', lastEvent: Date.now() };
            }
            return h;
          }));

          // Auto-recover after 3 seconds
          setTimeout(() => {
            setHandlers(prev => prev.map(h => {
              if (h.id === handler.id && h.status === 'crashed') {
                return { ...h, status: 'active' };
              }
              return h;
            }));
          }, 3000);

          return;
        }

        // Handler processes event successfully
        setHandlers(prev => prev.map(h => {
          if (h.id === handler.id && h.status === 'active') {
            return { ...h, eventsHandled: h.eventsHandled + 1, lastEvent: Date.now() };
          }
          return h;
        }));

        setEvents(prev => prev.map(e => {
          if (e.id === newEvent.id) {
            return { ...e, handlersCompleted: e.handlersCompleted + 1 };
          }
          return e;
        }));
      });
    }, 1500);

    return () => clearInterval(interval);
  }, [isSimulating, handlers, crashRate, eventId]);

  const triggerCrash = (handlerId: string) => {
    setHandlers(prev => prev.map(h => {
      if (h.id === handlerId) {
        return { ...h, status: 'crashed', lastEvent: Date.now() };
      }
      return h;
    }));
  };

  const reset = () => {
    setHandlers([
      { id: 'h1', name: 'Logger Handler', eventsHandled: 0, eventsDropped: 0, status: 'active', lastEvent: 0 },
      { id: 'h2', name: 'Metrics Handler', eventsHandled: 0, eventsDropped: 0, status: 'active', lastEvent: 0 },
      { id: 'h3', name: 'Notification Handler', eventsHandled: 0, eventsDropped: 0, status: 'active', lastEvent: 0 },
      { id: 'h4', name: 'Audit Handler', eventsHandled: 0, eventsDropped: 0, status: 'active', lastEvent: 0 }
    ]);
    setEvents([]);
    setEventId(0);
  };

  const activeHandlers = handlers.filter(h => h.status === 'active').length;
  const crashedHandlers = handlers.filter(h => h.status === 'crashed').length;
  const totalEventsHandled = handlers.reduce((sum, h) => sum + h.eventsHandled, 0);

  const codeExample = `// Event Bus Pattern with Handler Isolation
public final class EventBus<E> {
    private final Map<String, List<Consumer<E>>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public void publish(E event) {
        List<Consumer<E>> eventHandlers = handlers.get(event.getClass().getName());

        // Dispatch to all handlers in parallel
        eventHandlers.forEach(handler -> {
            executor.submit(() -> {
                try {
                    handler.accept(event);
                } catch (Throwable t) {
                    // Handler crash doesn't affect bus or other handlers
                    log.error("Handler crashed", t);
                }
            });
        });
    }

    public void subscribe(String eventType, Consumer<E> handler) {
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(handler);
    }
}

// JOTP Event Manager (typed)
EventManager<UserEvent> eventManager = EventManager.create();

eventManager.addHandler(event -> {
    // This handler crashing won't affect others
    processEvent(event);
});

eventManager.notify(new UserCreated("user123"));`;

  return (
    <div className="space-y-6">
      {/* Controls */}
      <Card>
        <CardHeader>
          <CardTitle>Event Bus Pattern</CardTitle>
          <CardDescription>
            Decoupled communication where handler crashes don't affect the bus or other handlers
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
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Events Published</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-blue-600 dark:text-blue-400">{events.length}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Events Handled</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-green-600 dark:text-green-400">{totalEventsHandled}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Active Handlers</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-green-600 dark:text-green-400">{activeHandlers}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium text-slate-600 dark:text-slate-400">Crashed Handlers</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-red-600 dark:text-red-400">{crashedHandlers}</div>
          </CardContent>
        </Card>
      </div>

      {/* Event Bus Visualization */}
      <Card>
        <CardHeader>
          <CardTitle>Event Bus Architecture</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="bg-slate-50 dark:bg-slate-900 rounded-lg p-6">
            <div className="flex items-center gap-8">
              {/* Event Publisher */}
              <div className="flex-1">
                <div className="bg-blue-100 dark:bg-blue-900/20 border-2 border-blue-500 rounded-lg p-4">
                  <div className="text-sm font-semibold text-blue-700 dark:text-blue-400 text-center">Event Publisher</div>
                  <div className="text-xs text-center text-slate-600 dark:text-slate-400 mt-1">
                    {isSimulating ? `Publishing ${events.length} events` : 'Waiting...'}
                  </div>
                </div>
              </div>

              {/* Event Bus */}
              <div className="flex-1">
                <div className="bg-purple-100 dark:bg-purple-900/20 border-2 border-purple-500 rounded-lg p-4">
                  <div className="text-sm font-semibold text-purple-700 dark:text-purple-400 text-center">Event Bus</div>
                  <div className="text-xs text-center text-slate-600 dark:text-slate-400 mt-1">
                    Async dispatch
                  </div>
                  {isSimulating && (
                    <div className="mt-2 flex justify-center gap-1">
                      {[1, 2, 3].map(i => (
                        <div key={i} className="w-2 h-2 bg-purple-500 rounded-full animate-pulse" style={{ animationDelay: `${i * 0.2}s` }} />
                      ))}
                    </div>
                  )}
                </div>
              </div>

              {/* Handlers */}
              <div className="flex-1">
                <div className="text-sm font-semibold text-center text-slate-600 dark:text-slate-400 mb-2">Event Handlers</div>
                <div className="grid grid-cols-2 gap-2">
                  {handlers.map(handler => (
                    <div
                      key={handler.id}
                      className={`p-2 rounded border-2 text-center cursor-pointer transition-all ${
                        handler.status === 'active'
                          ? 'bg-green-100 dark:bg-green-900/20 border-green-500'
                          : 'bg-red-100 dark:bg-red-900/20 border-red-500 animate-pulse'
                      }`}
                      onClick={() => triggerCrash(handler.id)}
                      title="Click to crash"
                    >
                      <div className="text-xs font-semibold">
                        {handler.name.split(' ')[0]}
                      </div>
                      <div className="text-xs text-slate-600 dark:text-slate-400">
                        {handler.eventsHandled} events
                      </div>
                    </div>
                  ))}
                </div>
                <div className="text-xs text-center text-slate-500 mt-2">
                  Click a handler to simulate crash
                </div>
              </div>
            </div>

            {/* Flow Animation */}
            {isSimulating && (
              <div className="mt-6 relative h-12">
                <svg className="w-full h-full" viewBox="0 0 600 50">
                  <defs>
                    <marker id="arrow-purple" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
                      <polygon points="0 0, 10 3.5, 0 7" fill="#a855f7" />
                    </marker>
                  </defs>
                  <line
                    x1="100"
                    y1="25"
                    x2="200"
                    y2="25"
                    stroke="#a855f7"
                    strokeWidth="3"
                    markerEnd="url(#arrow-purple)"
                    className="animate-pulse"
                  />
                  <line
                    x1="400"
                    y1="25"
                    x2="500"
                    y2="25"
                    stroke="#a855f7"
                    strokeWidth="3"
                    markerEnd="url(#arrow-purple)"
                    className="animate-pulse"
                  />
                </svg>
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Handler Status Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {handlers.map(handler => (
          <Card
            key={handler.id}
            className={`cursor-pointer transition-all hover:shadow-lg ${
              handler.status === 'crashed' ? 'border-red-500 animate-pulse' : 'border-green-500'
            }`}
            onClick={() => triggerCrash(handler.id)}
          >
            <CardHeader className="pb-3">
              <div className="flex items-center justify-between">
                <CardTitle className="text-sm">{handler.name}</CardTitle>
                <Badge
                  variant={handler.status === 'active' ? 'default' : 'destructive'}
                  className={handler.status === 'crashed' ? 'animate-pulse' : ''}
                >
                  {handler.status}
                </Badge>
              </div>
            </CardHeader>
            <CardContent className="space-y-2">
              <div className="flex justify-between text-sm">
                <span className="text-slate-600 dark:text-slate-400">Events Handled</span>
                <span className="font-mono">{handler.eventsHandled}</span>
              </div>

              {handler.status === 'crashed' && (
                <div className="p-2 bg-red-50 dark:bg-red-900/20 rounded text-xs">
                  <div className="font-semibold text-red-600 dark:text-red-400">Handler Crashed</div>
                  <div className="text-slate-600 dark:text-slate-400">
                    Auto-recovering in {Math.max(0, 3000 - (Date.now() - handler.lastEvent)) / 1000}s
                  </div>
                </div>
              )}

              <div className="text-xs text-slate-500 mt-2">
                Click to {handler.status === 'active' ? 'crash' : 'recover'} handler
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {/* Recent Events */}
      <Card>
        <CardHeader>
          <CardTitle>Recent Events</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-2 max-h-64 overflow-y-auto">
            {events.slice(0, 10).map(event => (
              <div key={event.id} className="flex items-center justify-between p-3 border rounded-lg">
                <div className="flex items-center gap-3">
                  <Badge variant="outline">{event.type}</Badge>
                  <span className="font-mono text-sm">{event.id}</span>
                </div>
                <div className="flex items-center gap-4">
                  <div className="text-sm text-slate-600 dark:text-slate-400">
                    {event.handlersCompleted}/{event.handlersTotal} handlers
                  </div>
                  <div className="w-24 bg-slate-200 dark:bg-slate-700 rounded-full h-2">
                    <div
                      className="bg-green-500 h-2 rounded-full"
                      style={{ width: `${(event.handlersCompleted / event.handlersTotal) * 100}%` }}
                    />
                  </div>
                </div>
              </div>
            ))}
            {events.length === 0 && (
              <div className="text-center text-slate-400 dark:text-slate-600 py-8">
                No events yet. Start simulation to see events.
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Key Insight */}
      {crashedHandlers > 0 && (
        <Card className="border-blue-500">
          <CardHeader>
            <CardTitle className="text-blue-600 dark:text-blue-400">Key Pattern Benefit</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Notice that crashed handlers don't affect:
              </p>
              <ul className="list-disc pl-6 text-sm text-slate-600 dark:text-slate-400 space-y-1">
                <li>The event bus itself (it continues dispatching)</li>
                <li>Other handlers (they keep processing events)</li>
                <li>New events being published</li>
              </ul>
              <p className="text-sm text-slate-600 dark:text-slate-400 mt-2">
                This isolation prevents cascading failures across handlers.
              </p>
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
                  <li><strong>Decoupled:</strong> Publishers don't know about handlers, handlers don't know about publishers</li>
                  <li><strong>Async Dispatch:</strong> Each handler runs in its own execution context</li>
                  <li><strong>Error Isolation:</strong> Handler crashes don't affect the bus or other handlers</li>
                  <li><strong>Supervision:</strong> Crashed handlers can be auto-restarted by supervisors</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Key Properties</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li><strong>Typed Events:</strong> Type-safe event handling</li>
                  <li><strong>Multiple Subscribers:</strong> Many handlers per event type</li>
                  <li><strong>Ordered Delivery:</strong> Per-handler ordering guarantees</li>
                  <li><strong>Crash Recovery:</strong> Supervisors restart failed handlers</li>
                </ul>

                <h4 className="text-lg font-semibold mb-2 mt-4">Benefits</h4>
                <ul className="list-disc pl-6 space-y-1">
                  <li>Loose coupling between components</li>
                  <li>Easy to add new handlers without changing publishers</li>
                  <li>Fault isolation prevents cascading failures</li>
                  <li>Natural fit for event-driven architectures</li>
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
                  <h5 className="font-semibold mb-2">📊 Analytics Pipeline</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Multiple analytics processors without blocking main flow
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🔔 Notification Systems</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Send notifications across channels (email, SMS, push) independently
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">📝 Audit Logging</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Log events without affecting request handling
                  </p>
                </div>
                <div className="p-4 border rounded-lg">
                  <h5 className="font-semibold mb-2">🔄 State Synchronization</h5>
                  <p className="text-sm text-slate-600 dark:text-slate-400">
                    Keep read models in sync via event handlers
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
              <h5 className="font-semibold mb-2">Supervisor</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Auto-restart crashed handlers
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">Bulkhead</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Isolate handler resources
              </p>
            </div>
            <div className="p-4 border rounded-lg hover:border-blue-500 cursor-pointer transition-colors">
              <h5 className="font-semibold mb-2">CQRS</h5>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                Event-driven read model updates
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
