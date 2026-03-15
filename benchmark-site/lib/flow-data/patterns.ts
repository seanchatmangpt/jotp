/**
 * JOTP Pattern Visualization Data
 *
 * Comprehensive type definitions and pattern data for enterprise
 * and messaging pattern visualizations in the benchmark dashboard.
 */

// ============================================================================
// Type Definitions
// ============================================================================

export type PatternCategory =
  | 'enterprise'
  | 'messaging-channels'
  | 'messaging-construction'
  | 'messaging-routing'
  | 'messaging-transformation'
  | 'messaging-endpoints'
  | 'messaging-management'

export interface PatternComponent {
  id: string
  type: string
  role: string
  position: { x: number; y: number }
  config?: Record<string, unknown>
}

export interface FlowStep {
  from: string
  to: string
  label?: string
  condition?: string
  type?: 'sync' | 'async' | 'conditional' | 'broadcast'
}

export interface PatternDiagramData {
  color: string
  icon: string
  layout: 'horizontal' | 'vertical' | 'circular' | 'tree'
}

export interface Pattern {
  id: string
  name: string
  category: PatternCategory
  description: string
  components: PatternComponent[]
  flow: FlowStep[]
  codeExample?: string
  diagramData: PatternDiagramData
  references?: string[]
}

// ============================================================================
// Pattern Data Registry
// ============================================================================

export const PATTERNS: Record<string, Pattern> = {
  // ==========================================================================
  // ENTERPRISE PATTERNS (8)
  // ==========================================================================

  CircuitBreaker: {
    id: 'circuit-breaker',
    name: 'Circuit Breaker',
    category: 'enterprise',
    description: 'Prevents cascading failures by detecting failures and wrapping calls that can fail. Transitions between CLOSED, OPEN, and HALF_OPEN states based on failure thresholds and timeout.',
    components: [
      { id: 'client', type: 'service', role: 'Caller', position: { x: 100, y: 150 } },
      { id: 'circuit-breaker', type: 'circuit-breaker', role: 'Protection', position: { x: 300, y: 150 } },
      { id: 'service', type: 'service', role: 'Protected Service', position: { x: 500, y: 150 } },
      { id: 'state-monitor', type: 'monitor', role: 'State Tracker', position: { x: 300, y: 50 } },
      { id: 'fallback', type: 'handler', role: 'Fallback Handler', position: { x: 300, y: 250 } }
    ],
    flow: [
      { from: 'client', to: 'circuit-breaker', label: 'Request', type: 'sync' },
      { from: 'circuit-breaker', to: 'service', label: 'Delegate', condition: 'CLOSED', type: 'sync' },
      { from: 'service', to: 'circuit-breaker', label: 'Response', type: 'sync' },
      { from: 'circuit-breaker', to: 'state-monitor', label: 'Track Result', type: 'async' },
      { from: 'circuit-breaker', to: 'fallback', label: 'Fallback', condition: 'OPEN/HALF_OPEN', type: 'sync' }
    ],
    diagramData: {
      color: '#e74c3c',
      icon: '🔌',
      layout: 'horizontal'
    },
    codeExample: `CircuitBreaker breaker = CircuitBreaker.ofDefaults()
  .withFailureThreshold(5)
  .withTimeout(Duration.ofSeconds(30));

breaker.execute(() -> remoteService.call())
  .recover(throwable -> fallback.execute());`
  },

  Bulkhead: {
    id: 'bulkhead',
    name: 'Bulkhead',
    category: 'enterprise',
    description: 'Isolates limited resources to prevent resource exhaustion. Uses semaphores or thread pools to limit concurrent access to critical components.',
    components: [
      { id: 'requests', type: 'queue', role: 'Incoming Requests', position: { x: 100, y: 150 } },
      { id: 'bulkhead', type: 'bulkhead', role: 'Concurrency Limiter', position: { x: 300, y: 150 } },
      { id: 'worker-pool', type: 'pool', role: 'Resource Pool', position: { x: 500, y: 150 }, config: { maxConcurrent: 10 } },
      { id: 'resource', type: 'resource', role: 'Protected Resource', position: { x: 700, y: 150 } },
      { id: 'queue', type: 'queue', role: 'Waiting Queue', position: { x: 300, y: 250 } }
    ],
    flow: [
      { from: 'requests', to: 'bulkhead', label: 'Arrive', type: 'async' },
      { from: 'bulkhead', to: 'worker-pool', label: 'Acquire', condition: 'Available', type: 'sync' },
      { from: 'bulkhead', to: 'queue', label: 'Wait', condition: 'At Capacity', type: 'async' },
      { from: 'worker-pool', to: 'resource', label: 'Access', type: 'sync' },
      { from: 'resource', to: 'worker-pool', label: 'Release', type: 'sync' },
      { from: 'worker-pool', to: 'bulkhead', label: 'Complete', type: 'async' }
    ],
    diagramData: {
      color: '#3498db',
      icon: '🚢',
      layout: 'horizontal'
    },
    codeExample: `Bulkhead bulkhead = Bulkhead.of("resource",
  BulkheadConfig.custom()
    .maxConcurrentCalls(10)
    .maxWaitDuration(Duration.ofSeconds(5))
    .build());

bulkhead.execute(() -> protectedResource.use());`
  },

  Backpressure: {
    id: 'backpressure',
    name: 'Backpressure',
    category: 'enterprise',
    description: 'Manages flow control between fast producers and slow consumers. Uses strategies like buffering, dropping, or applying resistance to prevent overload.',
    components: [
      { id: 'producer', type: 'producer', role: 'Fast Producer', position: { x: 100, y: 150 } },
      { id: 'buffer', type: 'buffer', role: 'Flow Control Buffer', position: { x: 300, y: 150 }, config: { capacity: 1000 } },
      { id: 'controller', type: 'controller', role: 'Backpressure Controller', position: { x: 500, y: 150 } },
      { id: 'consumer', type: 'consumer', role: 'Slow Consumer', position: { x: 700, y: 150 } },
      { id: 'metrics', type: 'monitor', role: 'Flow Monitor', position: { x: 500, y: 50 } }
    ],
    flow: [
      { from: 'producer', to: 'buffer', label: 'Emit', type: 'async' },
      { from: 'buffer', to: 'controller', label: 'Request', condition: 'Available', type: 'async' },
      { from: 'controller', to: 'consumer', label: 'Deliver', type: 'async' },
      { from: 'consumer', to: 'controller', label: 'Ack/Request', type: 'async' },
      { from: 'controller', to: 'metrics', label: 'Track Flow', type: 'async' },
      { from: 'controller', to: 'buffer', label: 'Apply Resistance', condition: 'High Pressure', type: 'async' }
    ],
    diagramData: {
      color: '#9b59b6',
      icon: '⏮️',
      layout: 'horizontal'
    },
    codeExample: `// Reactive Streams backpressure
Flux.range(0, 1_000_000)
  .onBackpressureBuffer(1000, BufferOverflowStrategy.DROP_OLDEST)
  .subscribe(
    value -> process(value),
    error -> log.error(error),
    () -> log.info("Complete")
  );`
  },

  HealthCheck: {
    id: 'health-check',
    name: 'Health Check',
    category: 'enterprise',
    description: 'Monitors system health by checking dependencies, resources, and metrics. Provides liveness (is running) and readiness (can serve traffic) endpoints.',
    components: [
      { id: 'monitor', type: 'monitor', role: 'Health Monitor', position: { x: 400, y: 150 } },
      { id: 'db-check', type: 'check', role: 'Database Check', position: { x: 200, y: 50 } },
      { id: 'cache-check', type: 'check', role: 'Cache Check', position: { x: 400, y: 50 } },
      { id: 'api-check', type: 'check', role: 'API Check', position: { x: 600, y: 50 } },
      { id: 'aggregator', type: 'aggregator', role: 'Health Aggregator', position: { x: 400, y: 250 } },
      { id: 'endpoint', type: 'endpoint', role: 'HTTP Endpoint', position: { x: 400, y: 350 } }
    ],
    flow: [
      { from: 'monitor', to: 'db-check', label: 'Check', type: 'sync' },
      { from: 'monitor', to: 'cache-check', label: 'Check', type: 'sync' },
      { from: 'monitor', to: 'api-check', label: 'Check', type: 'sync' },
      { from: 'db-check', to: 'aggregator', label: 'Report Status', type: 'sync' },
      { from: 'cache-check', to: 'aggregator', label: 'Report Status', type: 'sync' },
      { from: 'api-check', to: 'aggregator', label: 'Report Status', type: 'sync' },
      { from: 'aggregator', to: 'endpoint', label: 'Publish', type: 'sync' }
    ],
    diagramData: {
      color: '#27ae60',
      icon: '🏥',
      layout: 'vertical'
    },
    codeExample: `HealthCheckRegistry registry = HealthCheckRegistry.of();
registry.register("database", () ->
  database.ping() ? Status.UP : Status.DOWN);
registry.register("cache", () ->
  cache.get("health") != null ? Status.UP : Status.DOWN);`
  },

  Saga: {
    id: 'saga',
    name: 'Saga',
    category: 'enterprise',
    description: 'Manages distributed transactions through a sequence of local transactions with compensating actions. Ensures data consistency across microservices without 2PC.',
    components: [
      { id: 'orchestrator', type: 'orchestrator', role: 'Saga Orchestrator', position: { x: 400, y: 150 } },
      { id: 'tx1', type: 'transaction', role: 'Service A Transaction', position: { x: 200, y: 50 } },
      { id: 'tx2', type: 'transaction', role: 'Service B Transaction', position: { x: 400, y: 50 } },
      { id: 'tx3', type: 'transaction', role: 'Service C Transaction', position: { x: 600, y: 50 } },
      { id: 'compensate-1', type: 'compensator', role: 'Compensate A', position: { x: 200, y: 250 } },
      { id: 'compensate-2', type: 'compensator', role: 'Compensate B', position: { x: 400, y: 250 } },
      { id: 'state', type: 'log', role: 'Saga State Log', position: { x: 400, y: 350 } }
    ],
    flow: [
      { from: 'orchestrator', to: 'tx1', label: 'Execute', type: 'sync' },
      { from: 'tx1', to: 'orchestrator', label: 'Complete', type: 'sync' },
      { from: 'orchestrator', to: 'tx2', label: 'Execute', condition: 'tx1 Success', type: 'sync' },
      { from: 'tx2', to: 'orchestrator', label: 'Complete', type: 'sync' },
      { from: 'orchestrator', to: 'tx3', label: 'Execute', condition: 'tx2 Success', type: 'sync' },
      { from: 'tx3', to: 'orchestrator', label: 'Complete', type: 'sync' },
      { from: 'orchestrator', to: 'compensate-2', label: 'Compensate', condition: 'tx3 Failure', type: 'sync' },
      { from: 'compensate-2', to: 'compensate-1', label: 'Then Compensate', type: 'sync' },
      { from: 'orchestrator', to: 'state', label: 'Log State', type: 'async' }
    ],
    diagramData: {
      color: '#e67e22',
      icon: '📜',
      layout: 'vertical'
    },
    codeExample: `Saga saga = Saga.of(
  Step.withAction(serviceA::reserve)
      .compensate(serviceA::cancel),
  Step.withAction(serviceB::confirm)
      .compensate(serviceB::refund),
  Step.withAction(serviceC::complete)
      .compensate(serviceC::undo)
).execute();`
  },

  EventBus: {
    id: 'event-bus',
    name: 'Event Bus',
    category: 'enterprise',
    description: 'Decentralized event communication enabling pub/sub messaging between components. Supports synchronous and async dispatch with type-safe events.',
    components: [
      { id: 'bus', type: 'bus', role: 'Event Bus', position: { x: 400, y: 150 } },
      { id: 'publisher-1', type: 'publisher', role: 'Publisher A', position: { x: 100, y: 50 } },
      { id: 'publisher-2', type: 'publisher', role: 'Publisher B', position: { x: 100, y: 250 } },
      { id: 'subscriber-1', type: 'subscriber', role: 'Subscriber 1', position: { x: 700, y: 50 } },
      { id: 'subscriber-2', type: 'subscriber', role: 'Subscriber 2', position: { x: 700, y: 150 } },
      { id: 'subscriber-3', type: 'subscriber', role: 'Subscriber 3', position: { x: 700, y: 250 } },
      { id: 'router', type: 'router', role: 'Event Router', position: { x: 400, y: 250 } }
    ],
    flow: [
      { from: 'publisher-1', to: 'bus', label: 'Publish', type: 'async' },
      { from: 'publisher-2', to: 'bus', label: 'Publish', type: 'async' },
      { from: 'bus', to: 'router', label: 'Route', type: 'async' },
      { from: 'router', to: 'subscriber-1', label: 'Deliver', type: 'async' },
      { from: 'router', to: 'subscriber-2', label: 'Deliver', type: 'async' },
      { from: 'router', to: 'subscriber-3', label: 'Deliver', type: 'async' }
    ],
    diagramData: {
      color: '#16a085',
      icon: '🚌',
      layout: 'horizontal'
    },
    codeExample: `EventManager<UserEvent> eventBus = EventManager.create();
eventBus.subscribe(UserCreated.class, event ->
  sendWelcomeEmail(event.getUserId()));
eventBus.subscribe(UserCreated.class, event ->
  updateAnalytics(event.getUserId()));
eventBus.publish(new UserCreated(userId));`
  },

  MultiTenantSupervisor: {
    id: 'multi-tenant-supervisor',
    name: 'Multi-Tenant Supervisor',
    category: 'enterprise',
    description: 'Isolates tenant processes in separate supervisor trees with independent lifecycle management. Prevents noisy neighbor problems and enables per-tenant SLA guarantees.',
    components: [
      { id: 'root-supervisor', type: 'supervisor', role: 'Root Supervisor', position: { x: 400, y: 50 } },
      { id: 'tenant-1', type: 'supervisor', role: 'Tenant A Supervisor', position: { x: 200, y: 150 }, config: { tenantId: 'tenant-a' } },
      { id: 'tenant-2', type: 'supervisor', role: 'Tenant B Supervisor', position: { x: 400, y: 150 }, config: { tenantId: 'tenant-b' } },
      { id: 'tenant-3', type: 'supervisor', role: 'Tenant C Supervisor', position: { x: 600, y: 150 }, config: { tenantId: 'tenant-c' } },
      { id: 'worker-1a', type: 'worker', role: 'Tenant A Worker 1', position: { x: 150, y: 250 } },
      { id: 'worker-1b', type: 'worker', role: 'Tenant A Worker 2', position: { x: 250, y: 250 } },
      { id: 'worker-2', type: 'worker', role: 'Tenant B Worker', position: { x: 400, y: 250 } },
      { id: 'worker-3', type: 'worker', role: 'Tenant C Worker', position: { x: 600, y: 250 } }
    ],
    flow: [
      { from: 'root-supervisor', to: 'tenant-1', label: 'Spawn', type: 'async' },
      { from: 'root-supervisor', to: 'tenant-2', label: 'Spawn', type: 'async' },
      { from: 'root-supervisor', to: 'tenant-3', label: 'Spawn', type: 'async' },
      { from: 'tenant-1', to: 'worker-1a', label: 'Supervise', type: 'async' },
      { from: 'tenant-1', to: 'worker-1b', label: 'Supervise', type: 'async' },
      { from: 'tenant-2', to: 'worker-2', label: 'Supervise', type: 'async' },
      { from: 'tenant-3', to: 'worker-3', label: 'Supervise', type: 'async' }
    ],
    diagramData: {
      color: '#8e44ad',
      icon: '🏢',
      layout: 'tree'
    },
    codeExample: `Supervisor root = Supervisor.create(
  ChildSpec.of(tenantA, Supervisor::create),
  ChildSpec.of(tenantB, Supervisor::create),
  ChildSpec.of(tenantC, Supervisor::create)
);
// Each tenant supervisor manages isolated worker processes
// and restarts are contained within tenant boundaries`
  },

  Recovery: {
    id: 'recovery',
    name: 'Crash Recovery',
    category: 'enterprise',
    description: 'OTP "let it crash" philosophy combined with supervised restart strategies. Processes crash and are automatically restarted with fresh state, preventing error accumulation.',
    components: [
      { id: 'supervisor', type: 'supervisor', role: 'Supervisor', position: { x: 400, y: 150 } },
      { id: 'worker', type: 'worker', role: 'Worker Process', position: { x: 400, y: 250 } },
      { id: 'state', type: 'state', role: 'Process State', position: { x: 300, y: 350 } },
      { id: 'monitor', type: 'monitor', role: 'Crash Monitor', position: { x: 500, y: 50 } },
      { id: 'strategy', type: 'strategy', role: 'Restart Strategy', position: { x: 400, y: 50 } },
      { id: 'new-state', type: 'state', role: 'Fresh State', position: { x: 400, y: 350 } }
    ],
    flow: [
      { from: 'supervisor', to: 'worker', label: 'Spawn', type: 'async' },
      { from: 'worker', to: 'state', label: 'Load State', type: 'sync' },
      { from: 'worker', to: 'monitor', label: 'Heartbeat', type: 'async' },
      { from: 'worker', to: 'supervisor', label: 'CRASH', type: 'async' },
      { from: 'monitor', to: 'supervisor', label: 'Detect Crash', type: 'async' },
      { from: 'supervisor', to: 'strategy', label: 'Apply Strategy', type: 'sync' },
      { from: 'strategy', to: 'supervisor', label: 'Restart Command', type: 'sync' },
      { from: 'supervisor', to: 'worker', label: 'Restart', type: 'async' },
      { from: 'worker', to: 'new-state', label: 'Initialize Fresh', type: 'sync' }
    ],
    diagramData: {
      color: '#c0392b',
      icon: '🔄',
      layout: 'circular'
    },
    codeExample: `Supervisor supervisor = Supervisor.create(
  RestartStrategy.ONE_FOR_ONE,
  ChildSpec.of(worker, () -> Proc.spawn(initialState, handler))
);
// Worker crashes are caught by supervisor
// Process is restarted with clean state
// No error accumulation, no need for try/catch everywhere`
  },

  // ==========================================================================
  // MESSAGING CHANNELS (8)
  // ==========================================================================

  PointToPoint: {
    id: 'point-to-point',
    name: 'Point-to-Point Channel',
    category: 'messaging-channels',
    description: 'A channel where each message is consumed by exactly one receiver. Implementations include queues with competing consumers.',
    components: [
      { id: 'sender', type: 'sender', role: 'Message Sender', position: { x: 100, y: 150 } },
      { id: 'channel', type: 'channel', role: 'Point-to-Point Channel', position: { x: 300, y: 150 } },
      { id: 'receiver-1', type: 'receiver', role: 'Receiver 1', position: { x: 550, y: 100 } },
      { id: 'receiver-2', type: 'receiver', role: 'Receiver 2', position: { x: 550, y: 200 } },
      { id: 'receiver-3', type: 'receiver', role: 'Receiver 3', position: { x: 550, y: 300 } }
    ],
    flow: [
      { from: 'sender', to: 'channel', label: 'Send Message', type: 'async' },
      { from: 'channel', to: 'receiver-1', label: 'Deliver (One consumer)', type: 'async' },
      { from: 'channel', to: 'receiver-2', label: 'Or Deliver (Exclusive)', type: 'async' },
      { from: 'channel', to: 'receiver-3', label: 'Or Deliver (Exclusive)', type: 'async' }
    ],
    diagramData: {
      color: '#2980b9',
      icon: '➡️',
      layout: 'horizontal'
    },
    codeExample: `MessageChannel<Order> channel = MessageChannel.pointToPoint();
channel.send(new Order(orderId));
// Only ONE receiver processes each order
Order received = channel.receive();`
  },

  PublishSubscribe: {
    id: 'publish-subscribe',
    name: 'Publish-Subscribe Channel',
    category: 'messaging-channels',
    description: 'A channel where each message is broadcast to ALL subscribers. Multiple consumers receive the same message independently.',
    components: [
      { id: 'publisher', type: 'publisher', role: 'Message Publisher', position: { x: 100, y: 150 } },
      { id: 'channel', type: 'channel', role: 'Pub-Sub Channel', position: { x: 300, y: 150 } },
      { id: 'subscriber-1', type: 'subscriber', role: 'Subscriber A', position: { x: 550, y: 80 } },
      { id: 'subscriber-2', type: 'subscriber', role: 'Subscriber B', position: { x: 550, y: 160 } },
      { id: 'subscriber-3', type: 'subscriber', role: 'Subscriber C', position: { x: 550, y: 240 } },
      { id: 'subscriber-4', type: 'subscriber', role: 'Subscriber D', position: { x: 550, y: 320 } }
    ],
    flow: [
      { from: 'publisher', to: 'channel', label: 'Publish Event', type: 'async' },
      { from: 'channel', to: 'subscriber-1', label: 'Broadcast (All receive)', type: 'async' },
      { from: 'channel', to: 'subscriber-2', label: 'Broadcast (All receive)', type: 'async' },
      { from: 'channel', to: 'subscriber-3', label: 'Broadcast (All receive)', type: 'async' },
      { from: 'channel', to: 'subscriber-4', label: 'Broadcast (All receive)', type: 'async' }
    ],
    diagramData: {
      color: '#8e44ad',
      icon: '📢',
      layout: 'horizontal'
    },
    codeExample: `PublishSubscribeChannel<Event> channel =
  MessageChannel.publishSubscribe();
channel.subscribe(event -> log.info("A: {}", event));
channel.subscribe(event -> log.info("B: {}", event));
channel.publish(new UserCreatedEvent(userId));
// ALL subscribers receive the same event`
  },

  DatatypeChannel: {
    id: 'datatype-channel',
    name: 'Datatype Channel',
    category: 'messaging-channels',
    description: 'Separate channels for different message types. Each channel carries only one datatype, enabling type-safe message routing.',
    components: [
      { id: 'sender', type: 'sender', role: 'Message Sender', position: { x: 100, y: 150 } },
      { id: 'router', type: 'router', role: 'Type Router', position: { x: 250, y: 150 } },
      { id: 'order-channel', type: 'channel', role: 'Order Channel', position: { x: 450, y: 80 } },
      { id: 'payment-channel', type: 'channel', role: 'Payment Channel', position: { x: 450, y: 160 } },
      { id: 'shipment-channel', type: 'channel', role: 'Shipment Channel', position: { x: 450, y: 240 } },
      { id: 'order-consumer', type: 'consumer', role: 'Order Processor', position: { x: 650, y: 80 } },
      { id: 'payment-consumer', type: 'consumer', role: 'Payment Processor', position: { x: 650, y: 160 } },
      { id: 'shipment-consumer', type: 'consumer', role: 'Shipment Processor', position: { x: 650, y: 240 } }
    ],
    flow: [
      { from: 'sender', to: 'router', label: 'Send Message', type: 'async' },
      { from: 'router', to: 'order-channel', label: 'Route Orders', type: 'async' },
      { from: 'router', to: 'payment-channel', label: 'Route Payments', type: 'async' },
      { from: 'router', to: 'shipment-channel', label: 'Route Shipments', type: 'async' },
      { from: 'order-channel', to: 'order-consumer', label: 'Consume', type: 'async' },
      { from: 'payment-channel', to: 'payment-consumer', label: 'Consume', type: 'async' },
      { from: 'shipment-channel', to: 'shipment-consumer', label: 'Consume', type: 'async' }
    ],
    diagramData: {
      color: '#16a085',
      icon: '📋',
      layout: 'horizontal'
    },
    codeExample: `MessageChannel<Order> orders = MessageChannel.create();
MessageChannel<Payment> payments = MessageChannel.create();
MessageChannel<Shipment> shipments = MessageChannel.create();
// Each channel carries only its specific type`
  },

  DeadLetter: {
    id: 'dead-letter',
    name: 'Dead Letter Channel',
    category: 'messaging-channels',
    description: 'Captures messages that cannot be delivered or processed. Enables monitoring, retry logic, and analysis of failed messages.',
    components: [
      { id: 'sender', type: 'sender', role: 'Message Sender', position: { x: 100, y: 150 } },
      { id: 'main-channel', type: 'channel', role: 'Main Processing Channel', position: { x: 300, y: 150 } },
      { id: 'processor', type: 'processor', role: 'Message Processor', position: { x: 500, y: 150 } },
      { id: 'error-handler', type: 'handler', role: 'Error Handler', position: { x: 500, y: 250 } },
      { id: 'dlq', type: 'channel', role: 'Dead Letter Queue', position: { x: 700, y: 250 } },
      { id: 'monitor', type: 'monitor', role: 'DLQ Monitor', position: { x: 900, y: 250 } }
    ],
    flow: [
      { from: 'sender', to: 'main-channel', label: 'Send', type: 'async' },
      { from: 'main-channel', to: 'processor', label: 'Deliver', type: 'async' },
      { from: 'processor', to: 'error-handler', label: 'Processing Failed', type: 'async' },
      { from: 'error-handler', to: 'dlq', label: 'Route to DLQ', type: 'async' },
      { from: 'dlq', to: 'monitor', label: 'Alert & Analyze', type: 'async' }
    ],
    diagramData: {
      color: '#c0392b',
      icon: '💀',
      layout: 'horizontal'
    },
    codeExample: `MessageChannel<Order> channel = MessageChannel.builder()
  .withDeadLetterChannel(MessageChannel.create())
  .build();

// Failed messages automatically routed to DLQ
// Can be inspected, retried, or analyzed`
  },

  GuaranteedDelivery: {
    id: 'guaranteed-delivery',
    name: 'Guaranteed Delivery',
    category: 'messaging-channels',
    description: 'Ensures messages are delivered exactly once even under system failures. Uses persistence, acknowledgments, and idempotent receivers.',
    components: [
      { id: 'sender', type: 'sender', role: 'Message Sender', position: { x: 100, y: 150 } },
      { id: 'persistent-store', type: 'store', role: 'Persistent Store', position: { x: 300, y: 50 } },
      { id: 'channel', type: 'channel', role: 'Reliable Channel', position: { x: 300, y: 150 } },
      { id: 'receiver', type: 'receiver', role: 'Message Receiver', position: { x: 500, y: 150 } },
      { id: 'ack', type: 'ack', role: 'Acknowledge Handler', position: { x: 500, y: 250 } },
      { id: 'retry-queue', type: 'queue', role: 'Retry Queue', position: { x: 300, y: 250 } }
    ],
    flow: [
      { from: 'sender', to: 'persistent-store', label: 'Persist', type: 'sync' },
      { from: 'persistent-store', to: 'channel', label: 'Load', type: 'sync' },
      { from: 'channel', to: 'receiver', label: 'Deliver', type: 'sync' },
      { from: 'receiver', to: 'ack', label: 'Process & Ack', type: 'sync' },
      { from: 'ack', to: 'persistent-store', label: 'Confirm', type: 'sync' },
      { from: 'ack', to: 'retry-queue', label: 'Retry', condition: 'Ack Timeout', type: 'async' }
    ],
    diagramData: {
      color: '#27ae60',
      icon: '✅',
      layout: 'horizontal'
    },
    codeExample: `GuaranteedDeliveryChannel<Order> channel =
  GuaranteedDeliveryChannel.builder()
    .persistenceLevel(PersistenceLevel.SYNC)
    .ackTimeout(Duration.ofSeconds(30))
    .build();
// Messages persisted before send
// Only removed after ACK`
  },

  InvalidMessage: {
    id: 'invalid-message',
    name: 'Invalid Message Channel',
    category: 'messaging-channels',
    description: 'Separates invalid messages from valid ones. Failed validation messages are routed to a special channel for inspection and correction.',
    components: [
      { id: 'sender', type: 'sender', role: 'Message Sender', position: { x: 100, y: 150 } },
      { id: 'validator', type: 'validator', role: 'Message Validator', position: { x: 300, y: 150 } },
      { id: 'valid-channel', type: 'channel', role: 'Valid Messages Channel', position: { x: 500, y: 100 } },
      { id: 'invalid-channel', type: 'channel', role: 'Invalid Messages Channel', position: { x: 500, y: 200 } },
      { id: 'processor', type: 'processor', role: 'Valid Message Processor', position: { x: 700, y: 100 } },
      { id: 'inspector', type: 'inspector', role: 'Invalid Message Inspector', position: { x: 700, y: 200 } }
    ],
    flow: [
      { from: 'sender', to: 'validator', label: 'Send Message', type: 'async' },
      { from: 'validator', to: 'valid-channel', label: 'Route Valid', type: 'async' },
      { from: 'validator', to: 'invalid-channel', label: 'Route Invalid', type: 'async' },
      { from: 'valid-channel', to: 'processor', label: 'Process', type: 'async' },
      { from: 'invalid-channel', to: 'inspector', label: 'Inspect & Log', type: 'async' }
    ],
    diagramData: {
      color: '#e74c3c',
      icon: '⚠️',
      layout: 'horizontal'
    },
    codeExample: `MessageChannel<Order> channel = MessageChannel.builder()
  .withValidator(order -> order.isValid())
  .withInvalidChannel(MessageChannel.create())
  .build();

// Invalid messages routed to separate channel
// Valid messages processed normally`
  },

  MessagingBridge: {
    id: 'messaging-bridge',
    name: 'Messaging Bridge',
    category: 'messaging-channels',
    description: 'Connects two messaging systems, translating messages between different protocols or formats. Enables system integration.',
    components: [
      { id: 'system-a', type: 'system', role: 'Messaging System A', position: { x: 100, y: 150 } },
      { id: 'channel-a', type: 'channel', role: 'Channel A', position: { x: 250, y: 150 } },
      { id: 'bridge', type: 'bridge', role: 'Messaging Bridge', position: { x: 400, y: 150 } },
      { id: 'translator', type: 'translator', role: 'Message Translator', position: { x: 400, y: 250 } },
      { id: 'channel-b', type: 'channel', role: 'Channel B', position: { x: 550, y: 150 } },
      { id: 'system-b', type: 'system', role: 'Messaging System B', position: { x: 700, y: 150 } }
    ],
    flow: [
      { from: 'system-a', to: 'channel-a', label: 'Send', type: 'async' },
      { from: 'channel-a', to: 'bridge', label: 'Receive', type: 'async' },
      { from: 'bridge', to: 'translator', label: 'Translate Format', type: 'sync' },
      { from: 'translator', to: 'bridge', label: 'Return', type: 'sync' },
      { from: 'bridge', to: 'channel-b', label: 'Send', type: 'async' },
      { from: 'channel-b', to: 'system-b', label: 'Receive', type: 'async' }
    ],
    diagramData: {
      color: '#34495e',
      icon: '🌉',
      layout: 'horizontal'
    },
    codeExample: `MessagingBridge bridge = MessagingBridge.builder()
  .sourceConnector(jmsConnector)
  .targetConnector(kafkaConnector)
  .messageTranslator(JmsToKafkaTranslator::translate)
  .build();

// Bridges JMS queue to Kafka topic
// Translates message format transparently`
  },

  MessageBus: {
    id: 'message-bus',
    name: 'Message Bus',
    category: 'messaging-channels',
    description: 'Central communication backbone connecting multiple systems. Uses a combination of pipes and filters for message transformation.',
    components: [
      { id: 'bus', type: 'bus', role: 'Message Bus', position: { x: 400, y: 200 } },
      { id: 'app-1', type: 'application', role: 'Application 1', position: { x: 100, y: 100 } },
      { id: 'app-2', type: 'application', role: 'Application 2', position: { x: 100, y: 300 } },
      { id: 'filter-1', type: 'filter', role: 'Transform Filter', position: { x: 400, y: 50 } },
      { id: 'filter-2', type: 'filter', role: 'Route Filter', position: { x: 400, y: 350 } },
      { id: 'app-3', type: 'application', role: 'Application 3', position: { x: 700, y: 100 } },
      { id: 'app-4', type: 'application', role: 'Application 4', position: { x: 700, y: 300 } }
    ],
    flow: [
      { from: 'app-1', to: 'bus', label: 'Publish', type: 'async' },
      { from: 'app-2', to: 'bus', label: 'Publish', type: 'async' },
      { from: 'bus', to: 'filter-1', label: 'Transform', type: 'async' },
      { from: 'bus', to: 'filter-2', label: 'Route', type: 'async' },
      { from: 'filter-1', to: 'bus', label: 'Return', type: 'async' },
      { from: 'filter-2', to: 'bus', label: 'Return', type: 'async' },
      { from: 'bus', to: 'app-3', label: 'Deliver', type: 'async' },
      { from: 'bus', to: 'app-4', label: 'Deliver', type: 'async' }
    ],
    diagramData: {
      color: '#7f8c8d',
      icon: '🚌',
      layout: 'horizontal'
    },
    codeExample: `MessageBus bus = MessageBus.builder()
  .addFilter(transformFilter)
  .addFilter(routeFilter)
  .build();

bus.connect(application1);
bus.connect(application2);
// Central communication backbone`
  },

  // ==========================================================================
  // MESSAGING CONSTRUCTION (9)
  // ==========================================================================

  CommandMessage: {
    id: 'command-message',
    name: 'Command Message',
    category: 'messaging-construction',
    description: 'A message that represents an imperative instruction. The sender expects the receiver to perform a specific action.',
    components: [
      { id: 'sender', type: 'sender', role: 'Command Sender', position: { x: 100, y: 150 } },
      { id: 'command', type: 'message', role: 'Command Message', position: { x: 300, y: 150 } },
      { id: 'channel', type: 'channel', role: 'Command Channel', position: { x: 500, y: 150 } },
      { id: 'receiver', type: 'receiver', role: 'Command Receiver', position: { x: 700, y: 150 } },
      { id: 'executor', type: 'executor', role: 'Command Executor', position: { x: 700, y: 250 } }
    ],
    flow: [
      { from: 'sender', to: 'command', label: 'Create Command', type: 'sync' },
      { from: 'command', to: 'channel', label: 'Send', type: 'async' },
      { from: 'channel', to: 'receiver', label: 'Deliver', type: 'async' },
      { from: 'receiver', to: 'executor', label: 'Execute', type: 'sync' }
    ],
    diagramData: {
      color: '#2980b9',
      icon: '📝',
      layout: 'horizontal'
    },
    codeExample: `CommandMessage<PlaceOrder> command =
  CommandMessage.of(new PlaceOrder(productId, quantity));
command.send();
// Receiver must execute the order placement`
  },

  EventMessage: {
    id: 'event-message',
    name: 'Event Message',
    category: 'messaging-construction',
    description: 'A message that communicates a fact that has occurred. The sender has no expectation about receiver actions.',
    components: [
      { id: 'source', type: 'source', role: 'Event Source', position: { x: 100, y: 150 } },
      { id: 'event', type: 'message', role: 'Event Message', position: { x: 300, y: 150 } },
      { id: 'channel', type: 'channel', role: 'Event Channel', position: { x: 500, y: 150 } },
      { id: 'subscriber-1', type: 'subscriber', role: 'Subscriber A', position: { x: 700, y: 80 } },
      { id: 'subscriber-2', type: 'subscriber', role: 'Subscriber B', position: { x: 700, y: 220 } }
    ],
    flow: [
      { from: 'source', to: 'event', label: 'Event Occurs', type: 'sync' },
      { from: 'event', to: 'channel', label: 'Publish', type: 'async' },
      { from: 'channel', to: 'subscriber-1', label: 'Notify', type: 'async' },
      { from: 'channel', to: 'subscriber-2', label: 'Notify', type: 'async' }
    ],
    diagramData: {
      color: '#27ae60',
      icon: '📢',
      layout: 'horizontal'
    },
    codeExample: `EventMessage<OrderPlaced> event =
  EventMessage.of(new OrderPlaced(orderId, timestamp));
event.publish();
// Subscribers react independently, no expectations`
  },

  DocumentMessage: {
    id: 'document-message',
    name: 'Document Message',
    category: 'messaging-construction',
    description: 'A message that passes a complete data payload. The receiver uses the data without requesting additional information.',
    components: [
      { id: 'sender', type: 'sender', role: 'Data Provider', position: { x: 100, y: 150 } },
      { id: 'document', type: 'message', role: 'Document Message', position: { x: 300, y: 150 } },
      { id: 'channel', type: 'channel', role: 'Document Channel', position: { x: 500, y: 150 } },
      { id: 'receiver', type: 'receiver', role: 'Data Consumer', position: { x: 700, y: 150 } },
      { id: 'processor', type: 'processor', role: 'Data Processor', position: { x: 700, y: 250 } }
    ],
    flow: [
      { from: 'sender', to: 'document', label: 'Create Document', type: 'sync' },
      { from: 'document', to: 'channel', label: 'Send Complete Data', type: 'async' },
      { from: 'channel', to: 'receiver', label: 'Deliver', type: 'async' },
      { from: 'receiver', to: 'processor', label: 'Process All Data', type: 'sync' }
    ],
    diagramData: {
      color: '#16a085',
      icon: '📄',
      layout: 'horizontal'
    },
    codeExample: `DocumentMessage<OrderDetails> document =
  DocumentMessage.of(order);
document.send();
// Receiver gets complete order details
// No additional fetches needed`
  },

  RequestReply: {
    id: 'request-reply',
    name: 'Request-Reply',
    category: 'messaging-construction',
    description: 'Synchronous communication pattern where sender sends a request and blocks waiting for a response.',
    components: [
      { id: 'requester', type: 'requester', role: 'Request Sender', position: { x: 100, y: 150 } },
      { id: 'request-channel', type: 'channel', role: 'Request Channel', position: { x: 300, y: 150 } },
      { id: 'receiver', type: 'receiver', role: 'Request Receiver', position: { x: 500, y: 150 } },
      { id: 'processor', type: 'processor', role: 'Request Processor', position: { x: 500, y: 50 } },
      { id: 'reply-channel', type: 'channel', role: 'Reply Channel', position: { x: 300, y: 250 } }
    ],
    flow: [
      { from: 'requester', to: 'request-channel', label: 'Send Request', type: 'sync' },
      { from: 'request-channel', to: 'receiver', label: 'Deliver', type: 'sync' },
      { from: 'receiver', to: 'processor', label: 'Process', type: 'sync' },
      { from: 'processor', to: 'reply-channel', label: 'Send Reply', type: 'sync' },
      { from: 'reply-channel', to: 'requester', label: 'Receive Response', type: 'sync' }
    ],
    diagramData: {
      color: '#e67e22',
      icon: '🔄',
      layout: 'horizontal'
    },
    codeExample: `RequestReplyChannel<String, Order> channel =
  RequestReplyChannel.create();
Order result = channel.sendRequest("ORDER-123");
// Blocking call, waits for response`
  },

  EnvelopeWrapper: {
    id: 'envelope-wrapper',
    name: 'Envelope Wrapper',
    category: 'messaging-construction',
    description: 'Wraps payload data with metadata (headers, properties). Enables system-wide message processing without parsing payload.',
    components: [
      { id: 'payload', type: 'data', role: 'Message Payload', position: { x: 100, y: 150 } },
      { id: 'wrapper', type: 'wrapper', role: 'Envelope Wrapper', position: { x: 300, y: 150 } },
      { id: 'envelope', type: 'message', role: 'Message Envelope', position: { x: 500, y: 150 }, config: { headers: {}, properties: {} } },
      { id: 'interceptor', type: 'interceptor', role: 'Metadata Interceptor', position: { x: 700, y: 150 } },
      { id: 'processor', type: 'processor', role: 'Message Processor', position: { x: 700, y: 250 } }
    ],
    flow: [
      { from: 'payload', to: 'wrapper', label: 'Wrap', type: 'sync' },
      { from: 'wrapper', to: 'envelope', label: 'Add Metadata', type: 'sync' },
      { from: 'envelope', to: 'interceptor', label: 'Route', type: 'async' },
      { from: 'interceptor', to: 'processor', label: 'Process Payload', type: 'async' }
    ],
    diagramData: {
      color: '#8e44ad',
      icon: '✉️',
      layout: 'horizontal'
    },
    codeExample: `MessageEnvelope envelope = MessageEnvelope.builder()
  .payload(orderData)
  .header("contentType", "application/json")
  .header("timestamp", Instant.now())
  .property("priority", "high")
  .build();
// Metadata accessible without parsing payload`
  },

  CorrelationIdentifier: {
    id: 'correlation-identifier',
    name: 'Correlation Identifier',
    category: 'messaging-construction',
    description: 'Unique identifier that links multiple messages. Tracks request-response pairs and message chains.',
    components: [
      { id: 'request', type: 'message', role: 'Original Request', position: { x: 100, y: 150 } },
      { id: 'correlation-id', type: 'metadata', role: 'Correlation ID', position: { x: 300, y: 150 }, config: { value: 'corr-123' } },
      { id: 'message-1', type: 'message', role: 'Message 1', position: { x: 500, y: 80 } },
      { id: 'message-2', type: 'message', role: 'Message 2', position: { x: 500, y: 160 } },
      { id: 'message-3', type: 'message', role: 'Message 3', position: { x: 500, y: 240 } },
      { id: 'response', type: 'message', role: 'Final Response', position: { x: 700, y: 160 } }
    ],
    flow: [
      { from: 'request', to: 'correlation-id', label: 'Generate ID', type: 'sync' },
      { from: 'correlation-id', to: 'message-1', label: 'Attach ID', type: 'sync' },
      { from: 'correlation-id', to: 'message-2', label: 'Attach ID', type: 'sync' },
      { from: 'correlation-id', to: 'message-3', label: 'Attach ID', type: 'sync' },
      { from: 'message-1', to: 'response', label: 'Chain', type: 'async' },
      { from: 'message-2', to: 'response', label: 'Chain', type: 'async' },
      { from: 'message-3', to: 'response', label: 'Chain', type: 'async' }
    ],
    diagramData: {
      color: '#c0392b',
      icon: '🔗',
      layout: 'horizontal'
    },
    codeExample: `MessageEnvelope envelope = MessageEnvelope.builder()
  .payload(request)
  .correlationId(UUID.randomUUID())
  .build();
// All related messages carry same correlation ID
// Enables tracking and message chain reconstruction`
  },

  ReturnAddress: {
    id: 'return-address',
    name: 'Return Address',
    category: 'messaging-construction',
    description: 'Specifies where to send the reply message. Enables asynchronous request-reply patterns.',
    components: [
      { id: 'requester', type: 'requester', role: 'Request Sender', position: { x: 100, y: 150 } },
      { id: 'reply-queue', type: 'queue', role: 'Reply Queue', position: { x: 300, y: 250 } },
      { id: 'request', type: 'message', role: 'Request Message', position: { x: 300, y: 150 }, config: { replyTo: 'reply-queue' } },
      { id: 'receiver', type: 'receiver', role: 'Request Receiver', position: { x: 500, y: 150 } },
      { id: 'reply', type: 'message', role: 'Reply Message', position: { x: 500, y: 250 } }
    ],
    flow: [
      { from: 'requester', to: 'reply-queue', label: 'Create', type: 'sync' },
      { from: 'requester', to: 'request', label: 'Set Return Address', type: 'sync' },
      { from: 'request', to: 'receiver', label: 'Send Request', type: 'async' },
      { from: 'receiver', to: 'reply', label: 'Create Reply', type: 'sync' },
      { from: 'reply', to: 'reply-queue', label: 'Route via Return Address', type: 'async' }
    ],
    diagramData: {
      color: '#d35400',
      icon: '↩️',
      layout: 'horizontal'
    },
    codeExample: `MessageEnvelope envelope = MessageEnvelope.builder()
  .payload(request)
  .replyTo("reply-queue-" + UUID.randomUUID())
  .build();
// Receiver sends responses to specified address
// Enables async request-reply`
  },

  MessageExpiration: {
    id: 'message-expiration',
    name: 'Message Expiration',
    category: 'messaging-construction',
    description: 'Messages have a TTL after which they should be discarded if not processed. Prevents stale data processing.',
    components: [
      { id: 'sender', type: 'sender', role: 'Message Sender', position: { x: 100, y: 150 } },
      { id: 'message', type: 'message', role: 'Message with Expiration', position: { x: 300, y: 150 }, config: { expiresAt: '2024-01-01T00:00:00Z' } },
      { id: 'channel', type: 'channel', role: 'Message Channel', position: { x: 500, y: 150 } },
      { id: 'checker', type: 'checker', role: 'Expiration Checker', position: { x: 500, y: 250 } },
      { id: 'consumer', type: 'consumer', role: 'Message Consumer', position: { x: 700, y: 150 } }
    ],
    flow: [
      { from: 'sender', to: 'message', label: 'Set TTL', type: 'sync' },
      { from: 'message', to: 'channel', label: 'Send', type: 'async' },
      { from: 'channel', to: 'checker', label: 'Check Expiration', type: 'async' },
      { from: 'checker', to: 'consumer', label: 'Deliver (Not Expired)', type: 'async' },
      { from: 'checker', to: 'channel', label: 'Discard (Expired)', type: 'async' }
    ],
    diagramData: {
      color: '#7f8c8d',
      icon: '⏰',
      layout: 'horizontal'
    },
    codeExample: `MessageEnvelope envelope = MessageEnvelope.builder()
  .payload(event)
  .expiration(Instant.now().plusSeconds(30))
  .build();
// Message discarded if not processed within 30 seconds`
  },

  ClaimCheck: {
    id: 'claim-check',
    name: 'Claim Check',
    category: 'messaging-construction',
    description: 'Stores large payload in external storage and passes only a claim check (ID) through messaging system.',
    components: [
      { id: 'sender', type: 'sender', role: 'Data Sender', position: { x: 100, y: 150 } },
      { id: 'payload', type: 'data', role: 'Large Payload', position: { x: 200, y: 50 } },
      { id: 'storage', type: 'storage', role: 'External Storage', position: { x: 400, y: 50 } },
      { id: 'claim-check', type: 'message', role: 'Claim Check Message', position: { x: 400, y: 150 }, config: { claimId: 'claim-123' } },
      { id: 'channel', type: 'channel', role: 'Message Channel', position: { x: 600, y: 150 } },
      { id: 'receiver', type: 'receiver', role: 'Claim Receiver', position: { x: 800, y: 150 } }
    ],
    flow: [
      { from: 'sender', to: 'storage', label: 'Store Payload', type: 'sync' },
      { from: 'storage', to: 'claim-check', label: 'Return Claim ID', type: 'sync' },
      { from: 'claim-check', to: 'channel', label: 'Send Claim Check', type: 'async' },
      { from: 'channel', to: 'receiver', label: 'Deliver Claim Check', type: 'async' },
      { from: 'receiver', to: 'storage', label: 'Fetch Payload by ID', type: 'sync' }
    ],
    diagramData: {
      color: '#34495e',
      icon: '🎫',
      layout: 'horizontal'
    },
    codeExample: `String claimId = storage.store(largePayload);
MessageEnvelope claimCheck = MessageEnvelope.builder()
  .claimCheck(claimId)
  .build();
// Receiver fetches payload when needed
// Reduces channel bandwidth`
  },

  // ==========================================================================
  // MESSAGING ROUTING (9)
  // ==========================================================================

  ContentBasedRouter: {
    id: 'content-based-router',
    name: 'Content-Based Router',
    category: 'messaging-routing',
    description: 'Routes messages to different channels based on message content. Enables conditional routing without code changes.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'router', type: 'router', role: 'Content-Based Router', position: { x: 300, y: 150 } },
      { id: 'channel-a', type: 'channel', role: 'Channel A (Gold)', position: { x: 550, y: 80 } },
      { id: 'channel-b', type: 'channel', role: 'Channel B (Silver)', position: { x: 550, y: 160 } },
      { id: 'channel-c', type: 'channel', role: 'Channel C (Bronze)', position: { x: 550, y: 240 } }
    ],
    flow: [
      { from: 'input', to: 'router', label: 'Receive Message', type: 'async' },
      { from: 'router', to: 'channel-a', label: 'Route (Gold Customer)', condition: 'customer.level == GOLD', type: 'conditional' },
      { from: 'router', to: 'channel-b', label: 'Route (Silver Customer)', condition: 'customer.level == SILVER', type: 'conditional' },
      { from: 'router', to: 'channel-c', label: 'Route (Bronze Customer)', condition: 'customer.level == BRONZE', type: 'conditional' }
    ],
    diagramData: {
      color: '#2980b9',
      icon: '🔀',
      layout: 'horizontal'
    },
    codeExample: `ContentBasedRouter<Order> router = ContentBasedRouter.builder()
  .when(order -> order.isGold(), goldChannel)
  .when(order -> order.isSilver(), silverChannel)
  .otherwise(bronzeChannel)
  .build();`
  },

  ScatterGather: {
    id: 'scatter-gather',
    name: 'Scatter-Gather',
    category: 'messaging-routing',
    description: 'Broadcasts a request to multiple recipients and aggregates their responses. Useful for parallel queries.',
    components: [
      { id: 'requester', type: 'requester', role: 'Request Sender', position: { x: 100, y: 150 } },
      { id: 'scatter', type: 'router', role: 'Scatter (Broadcast)', position: { x: 300, y: 150 } },
      { id: 'recipient-1', type: 'recipient', role: 'Recipient A', position: { x: 500, y: 80 } },
      { id: 'recipient-2', type: 'recipient', role: 'Recipient B', position: { x: 500, y: 160 } },
      { id: 'recipient-3', type: 'recipient', role: 'Recipient C', position: { x: 500, y: 240 } },
      { id: 'gather', type: 'aggregator', role: 'Gather (Aggregate)', position: { x: 700, y: 150 } }
    ],
    flow: [
      { from: 'requester', to: 'scatter', label: 'Send Request', type: 'async' },
      { from: 'scatter', to: 'recipient-1', label: 'Broadcast', type: 'async' },
      { from: 'scatter', to: 'recipient-2', label: 'Broadcast', type: 'async' },
      { from: 'scatter', to: 'recipient-3', label: 'Broadcast', type: 'async' },
      { from: 'recipient-1', to: 'gather', label: 'Return Result', type: 'async' },
      { from: 'recipient-2', to: 'gather', label: 'Return Result', type: 'async' },
      { from: 'recipient-3', to: 'gather', label: 'Return Result', type: 'async' }
    ],
    diagramData: {
      color: '#8e44ad',
      icon: '📡',
      layout: 'horizontal'
    },
    codeExample: `ScatterGather<Quote> scatterGather = ScatterGather.builder()
  .addRecipient(supplierA)
  .addRecipient(supplierB)
  .addRecipient(supplierC)
  .aggregator(quotes -> bestPrice(quotes))
  .build();

Quote best = scatterGather.execute(request);`
  },

  Aggregator: {
    id: 'aggregator',
    name: 'Aggregator',
    category: 'messaging-routing',
    description: 'Combines multiple related messages into a single message. Uses correlation IDs to group messages.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'message-1', type: 'message', role: 'Message 1', position: { x: 250, y: 100 } },
      { id: 'message-2', type: 'message', role: 'Message 2', position: { x: 250, y: 200 } },
      { id: 'message-3', type: 'message', role: 'Message 3', position: { x: 250, y: 300 } },
      { id: 'aggregator', type: 'aggregator', role: 'Message Aggregator', position: { x: 450, y: 200 } },
      { id: 'output', type: 'message', role: 'Aggregated Message', position: { x: 650, y: 200 } }
    ],
    flow: [
      { from: 'input', to: 'message-1', label: 'Route (CorrID=123)', type: 'async' },
      { from: 'input', to: 'message-2', label: 'Route (CorrID=123)', type: 'async' },
      { from: 'input', to: 'message-3', label: 'Route (CorrID=123)', type: 'async' },
      { from: 'message-1', to: 'aggregator', label: 'Buffer', type: 'async' },
      { from: 'message-2', to: 'aggregator', label: 'Buffer', type: 'async' },
      { from: 'message-3', to: 'aggregator', label: 'Buffer & Complete', type: 'async' },
      { from: 'aggregator', to: 'output', label: 'Aggregate & Release', type: 'async' }
    ],
    diagramData: {
      color: '#27ae60',
      icon: '📦',
      layout: 'horizontal'
    },
    codeExample: `MessageAggregator<OrderLineItem> aggregator =
  MessageAggregator.builder()
    .correlationStrategy(orderItem -> orderItem.getOrderId())
    .completionStrategy(items -> items.size() == expectedSize)
    .aggregationStrategy(Order::new)
    .build();`
  },

  Splitter: {
    id: 'splitter',
    name: 'Splitter',
    category: 'messaging-routing',
    description: 'Breaks a single message into multiple messages. Each part is processed independently.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'splitter', type: 'splitter', role: 'Message Splitter', position: { x: 300, y: 150 } },
      { id: 'output-1', type: 'channel', role: 'Output Channel 1', position: { x: 550, y: 80 } },
      { id: 'output-2', type: 'channel', role: 'Output Channel 2', position: { x: 550, y: 160 } },
      { id: 'output-3', type: 'channel', role: 'Output Channel 3', position: { x: 550, y: 240 } }
    ],
    flow: [
      { from: 'input', to: 'splitter', label: 'Receive Composite Message', type: 'async' },
      { from: 'splitter', to: 'output-1', label: 'Split Part 1', type: 'async' },
      { from: 'splitter', to: 'output-2', label: 'Split Part 2', type: 'async' },
      { from: 'splitter', to: 'output-3', label: 'Split Part 3', type: 'async' }
    ],
    diagramData: {
      color: '#e67e22',
      icon: '✂️',
      layout: 'horizontal'
    },
    codeExample: `MessageSplitter<Order, OrderLineItem> splitter =
  MessageSplitter.of(Order::getLineItems);

List<OrderLineItem> items = splitter.split(order);
// Each item processed independently`
  },

  RecipientList: {
    id: 'recipient-list',
    name: 'Recipient List',
    category: 'messaging-routing',
    description: 'Routes messages to a dynamic list of recipients. The list is computed from message content.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'resolver', type: 'resolver', role: 'Recipient Resolver', position: { x: 300, y: 150 } },
      { id: 'router', type: 'router', role: 'Dynamic Router', position: { x: 500, y: 150 } },
      { id: 'recipient-1', type: 'recipient', role: 'Recipient 1', position: { x: 700, y: 80 } },
      { id: 'recipient-2', type: 'recipient', role: 'Recipient 2', position: { x: 700, y: 160 } },
      { id: 'recipient-3', type: 'recipient', role: 'Recipient 3', position: { x: 700, y: 240 } }
    ],
    flow: [
      { from: 'input', to: 'resolver', label: 'Receive Message', type: 'async' },
      { from: 'resolver', to: 'router', label: 'Resolve Recipients', type: 'sync' },
      { from: 'router', to: 'recipient-1', label: 'Route (Dynamic)', type: 'async' },
      { from: 'router', to: 'recipient-2', label: 'Route (Dynamic)', type: 'async' },
      { from: 'router', to: 'recipient-3', label: 'Route (Dynamic)', type: 'async' }
    ],
    diagramData: {
      color: '#16a085',
      icon: '👥',
      layout: 'horizontal'
    },
    codeExample: `RecipientList<Notification> router = RecipientList.builder()
  .recipientResolver(notification ->
    notification.getSubscribers())
  .build();

// Recipients computed dynamically per message
router.route(notification);`
  },

  DynamicRouter: {
    id: 'dynamic-router',
    name: 'Dynamic Router',
    category: 'messaging-routing',
    description: 'Routes messages based on computed rules. The routing logic can be updated at runtime.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'rule-engine', type: 'engine', role: 'Rule Engine', position: { x: 300, y: 50 } },
      { id: 'router', type: 'router', role: 'Dynamic Router', position: { x: 300, y: 150 } },
      { id: 'channel-a', type: 'channel', role: 'Channel A', position: { x: 550, y: 80 } },
      { id: 'channel-b', type: 'channel', role: 'Channel B', position: { x: 550, y: 160 } },
      { id: 'channel-c', type: 'channel', role: 'Channel C', position: { x: 550, y: 240 } }
    ],
    flow: [
      { from: 'input', to: 'router', label: 'Receive Message', type: 'async' },
      { from: 'router', to: 'rule-engine', label: 'Fetch Rules', type: 'sync' },
      { from: 'rule-engine', to: 'router', label: 'Return Routing Rules', type: 'sync' },
      { from: 'router', to: 'channel-a', label: 'Route (Computed)', type: 'conditional' },
      { from: 'router', to: 'channel-b', label: 'Route (Computed)', type: 'conditional' },
      { from: 'router', to: 'channel-c', label: 'Route (Computed)', type: 'conditional' }
    ],
    diagramData: {
      color: '#9b59b6',
      icon: '🧠',
      layout: 'horizontal'
    },
    codeExample: `DynamicRouter<Message> router = DynamicRouter.builder()
  .ruleSupplier(this::loadRules)
  .ruleEvaluator((msg, rule) -> rule.test(msg))
  .build();

// Rules loaded at runtime
router.route(message);`
  },

  RoutingSlip: {
    id: 'routing-slip',
    name: 'Routing Slip',
    category: 'messaging-routing',
    description: 'Message carries a predefined itinerary of processing steps. Each step routes to the next endpoint.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'message', type: 'message', role: 'Message with Routing Slip', position: { x: 300, y: 150 }, config: { itinerary: ['step1', 'step2', 'step3'] } },
      { id: 'step-1', type: 'processor', role: 'Processing Step 1', position: { x: 500, y: 80 } },
      { id: 'step-2', type: 'processor', role: 'Processing Step 2', position: { x: 500, y: 160 } },
      { id: 'step-3', type: 'processor', role: 'Processing Step 3', position: { x: 500, y: 240 } },
      { id: 'output', type: 'channel', role: 'Output Channel', position: { x: 700, y: 160 } }
    ],
    flow: [
      { from: 'input', to: 'message', label: 'Attach Routing Slip', type: 'async' },
      { from: 'message', to: 'step-1', label: 'Execute Step 1', type: 'async' },
      { from: 'step-1', to: 'step-2', label: 'Route to Next (Step 2)', type: 'async' },
      { from: 'step-2', to: 'step-3', label: 'Route to Next (Step 3)', type: 'async' },
      { from: 'step-3', to: 'output', label: 'Complete Itinerary', type: 'async' }
    ],
    diagramData: {
      color: '#c0392b',
      icon: '📋',
      layout: 'horizontal'
    },
    codeExample: `RoutingSlip slip = RoutingSlip.builder()
  .addStep("validation", validator)
  .addStep("enrichment", enricher)
  .addStep("transformation", transformer)
  .build();

MessageEnvelope envelope = MessageEnvelope.builder()
  .payload(data)
  .routingSlip(slip)
  .build();
// Follows predefined itinerary`
  },

  ProcessManager: {
    id: 'process-manager',
    name: 'Process Manager',
    category: 'messaging-routing',
    description: 'Orchestrates a business process across multiple services. Maintains process state and coordinates message flows.',
    components: [
      { id: 'coordinator', type: 'coordinator', role: 'Process Manager', position: { x: 400, y: 150 } },
      { id: 'state', type: 'state', role: 'Process State', position: { x: 400, y: 50 } },
      { id: 'service-1', type: 'service', role: 'Service A', position: { x: 200, y: 250 } },
      { id: 'service-2', type: 'service', role: 'Service B', position: { x: 400, y: 250 } },
      { id: 'service-3', type: 'service', role: 'Service C', position: { x: 600, y: 250 } }
    ],
    flow: [
      { from: 'coordinator', to: 'state', label: 'Load State', type: 'sync' },
      { from: 'coordinator', to: 'service-1', label: 'Invoke Step 1', type: 'async' },
      { from: 'service-1', to: 'coordinator', label: 'Complete Step 1', type: 'async' },
      { from: 'coordinator', to: 'state', label: 'Update State', type: 'sync' },
      { from: 'coordinator', to: 'service-2', label: 'Invoke Step 2', type: 'async' },
      { from: 'service-2', to: 'coordinator', label: 'Complete Step 2', type: 'async' },
      { from: 'coordinator', to: 'state', label: 'Update State', type: 'sync' },
      { from: 'coordinator', to: 'service-3', label: 'Invoke Step 3', type: 'async' },
      { from: 'service-3', to: 'coordinator', label: 'Complete Process', type: 'async' }
    ],
    diagramData: {
      color: '#d35400',
      icon: '🎯',
      layout: 'vertical'
    },
    codeExample: `ProcessManager<OrderProcess> manager =
  ProcessManager.builder()
    .state(OrderProcess.initial())
    .step("validate", this::validate)
    .step("reserve", this::reserve)
    .step("confirm", this::confirm)
    .build();

manager.start();`
  },

  Resequencer: {
    id: 'resequencer',
    name: 'Resequencer',
    category: 'messaging-routing',
    description: 'Reorders messages that arrive out of sequence. Ensures messages are processed in the correct order.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'message-3', type: 'message', role: 'Message 3 (Out of Order)', position: { x: 250, y: 100 } },
      { id: 'message-1', type: 'message', role: 'Message 1 (Out of Order)', position: { x: 250, y: 200 } },
      { id: 'message-2', type: 'message', role: 'Message 2 (Out of Order)', position: { x: 250, y: 300 } },
      { id: 'resequencer', type: 'resequencer', role: 'Message Resequencer', position: { x: 450, y: 200 } },
      { id: 'output', type: 'channel', role: 'Output Channel', position: { x: 650, y: 200 } }
    ],
    flow: [
      { from: 'input', to: 'message-3', label: 'Receive (Seq=3)', type: 'async' },
      { from: 'input', to: 'message-1', label: 'Receive (Seq=1)', type: 'async' },
      { from: 'input', to: 'message-2', label: 'Receive (Seq=2)', type: 'async' },
      { from: 'message-1', to: 'resequencer', label: 'Buffer', type: 'async' },
      { from: 'message-2', to: 'resequencer', label: 'Buffer', type: 'async' },
      { from: 'message-3', to: 'resequencer', label: 'Buffer', type: 'async' },
      { from: 'resequencer', to: 'output', label: 'Release in Order (1,2,3)', type: 'async' }
    ],
    diagramData: {
      color: '#7f8c8d',
      icon: '🔢',
      layout: 'horizontal'
    },
    codeExample: `MessageResequencer<Order> resequencer =
  MessageResequencer.builder()
    .sequenceStrategy(order -> order.getSequenceNumber())
    .reorderTimeout(Duration.ofSeconds(5))
    .build();

// Outputs messages in sequence order
// Even if received out of order`
  },

  // ==========================================================================
  // MESSAGING TRANSFORMATION (3)
  // ==========================================================================

  ContentFilter: {
    id: 'content-filter',
    name: 'Content Filter',
    category: 'messaging-transformation',
    description: 'Removes unnecessary data from messages. Reduces payload size and hides sensitive information.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'full-message', type: 'message', role: 'Full Message', position: { x: 250, y: 150 }, config: { hasSensitiveData: true } },
      { id: 'filter', type: 'filter', role: 'Content Filter', position: { x: 450, y: 150 } },
      { id: 'filtered-message', type: 'message', role: 'Filtered Message', position: { x: 650, y: 150 }, config: { hasSensitiveData: false } },
      { id: 'output', type: 'channel', role: 'Output Channel', position: { x: 850, y: 150 } }
    ],
    flow: [
      { from: 'input', to: 'full-message', label: 'Receive', type: 'async' },
      { from: 'full-message', to: 'filter', label: 'Process', type: 'async' },
      { from: 'filter', to: 'filtered-message', label: 'Filter Fields', type: 'sync' },
      { from: 'filtered-message', to: 'output', label: 'Send Filtered', type: 'async' }
    ],
    diagramData: {
      color: '#e74c3c',
      icon: '🔍',
      layout: 'horizontal'
    },
    codeExample: `ContentFilter<Customer> filter = ContentFilter.builder()
  .removeField("ssn")
  .removeField("creditCard")
  .keepField("id", "name")
  .build();

Customer filtered = filter.apply(customer);`
  },

  ContentEnricher: {
    id: 'content-enricher',
    name: 'Content Enricher',
    category: 'messaging-transformation',
    description: 'Adds missing data to messages by calling external services. Enriches messages with additional context.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'base-message', type: 'message', role: 'Base Message', position: { x: 250, y: 150 } },
      { id: 'enricher', type: 'enricher', role: 'Content Enricher', position: { x: 450, y: 150 } },
      { id: 'external-service', type: 'service', role: 'External Data Service', position: { x: 450, y: 50 } },
      { id: 'enriched-message', type: 'message', role: 'Enriched Message', position: { x: 650, y: 150 } },
      { id: 'output', type: 'channel', role: 'Output Channel', position: { x: 850, y: 150 } }
    ],
    flow: [
      { from: 'input', to: 'base-message', label: 'Receive', type: 'async' },
      { from: 'base-message', to: 'enricher', label: 'Process', type: 'async' },
      { from: 'enricher', to: 'external-service', label: 'Fetch Enrichment Data', type: 'sync' },
      { from: 'external-service', to: 'enricher', label: 'Return Data', type: 'sync' },
      { from: 'enricher', to: 'enriched-message', label: 'Merge Data', type: 'sync' },
      { from: 'enriched-message', to: 'output', label: 'Send Enriched', type: 'async' }
    ],
    diagramData: {
      color: '#27ae60',
      icon: '➕',
      layout: 'horizontal'
    },
    codeExample: `ContentEnricher<Order> enricher = ContentEnricher.builder()
  .enricher(order -> customerService.getDetails(order.getCustomerId()))
  .merger((order, details) -> order.withCustomerDetails(details))
  .build();

Order enriched = enricher.enrich(order);`
  },

  MessageTranslator: {
    id: 'message-translator',
    name: 'Message Translator',
    category: 'messaging-transformation',
    description: 'Converts messages from one format to another. Enables systems with different data formats to communicate.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel (Format A)', position: { x: 100, y: 150 } },
      { id: 'message-a', type: 'message', role: 'Message (Format A)', position: { x: 300, y: 150 }, config: { format: 'JSON' } },
      { id: 'translator', type: 'translator', role: 'Message Translator', position: { x: 500, y: 150 } },
      { id: 'message-b', type: 'message', role: 'Message (Format B)', position: { x: 700, y: 150 }, config: { format: 'XML' } },
      { id: 'output', type: 'channel', role: 'Output Channel (Format B)', position: { x: 900, y: 150 } }
    ],
    flow: [
      { from: 'input', to: 'message-a', label: 'Receive Format A', type: 'async' },
      { from: 'message-a', to: 'translator', label: 'Translate', type: 'sync' },
      { from: 'translator', to: 'message-b', label: 'Convert to Format B', type: 'sync' },
      { from: 'message-b', to: 'output', label: 'Send Format B', type: 'async' }
    ],
    diagramData: {
      color: '#9b59b6',
      icon: '🔄',
      layout: 'horizontal'
    },
    codeExample: `MessageTranslator<JsonOrder, XmlOrder> translator =
  MessageTranslator.builder()
    .serializer(JsonOrder::toJson)
    .deserializer(XmlOrder::fromJson)
    .build();

XmlOrder xml = translator.translate(jsonOrder);`
  },

  // ==========================================================================
  // MESSAGING ENDPOINTS (4)
  // ==========================================================================

  PollingConsumer: {
    id: 'polling-consumer',
    name: 'Polling Consumer',
    category: 'messaging-endpoints',
    description: 'Consumer that periodically checks for new messages. Provides control over consumption rate and timing.',
    components: [
      { id: 'consumer', type: 'consumer', role: 'Polling Consumer', position: { x: 300, y: 150 } },
      { id: 'scheduler', type: 'scheduler', role: 'Polling Scheduler', position: { x: 300, y: 50 }, config: { interval: '5s' } },
      { id: 'channel', type: 'channel', role: 'Message Channel', position: { x: 500, y: 150 } },
      { id: 'processor', type: 'processor', role: 'Message Processor', position: { x: 700, y: 150 } }
    ],
    flow: [
      { from: 'scheduler', to: 'consumer', label: 'Trigger Poll', type: 'async' },
      { from: 'consumer', to: 'channel', label: 'Poll for Messages', type: 'sync' },
      { from: 'channel', to: 'consumer', label: 'Return Messages', type: 'sync' },
      { from: 'consumer', to: 'processor', label: 'Process Messages', type: 'sync' }
    ],
    diagramData: {
      color: '#3498db',
      icon: '🔄',
      layout: 'horizontal'
    },
    codeExample: `PollingConsumer<Order> consumer = PollingConsumer.builder()
  .channel(orderChannel)
  .pollInterval(Duration.ofSeconds(5))
  .messageProcessor(this::processOrder)
  .build();

consumer.start();`
  },

  CompetingConsumer: {
    id: 'competing-consumer',
    name: 'Competing Consumer',
    category: 'messaging-endpoints',
    description: 'Multiple consumers compete for messages on the same channel. Each message is processed by only one consumer.',
    components: [
      { id: 'channel', type: 'channel', role: 'Point-to-Point Channel', position: { x: 400, y: 150 } },
      { id: 'consumer-1', type: 'consumer', role: 'Consumer 1', position: { x: 200, y: 50 } },
      { id: 'consumer-2', type: 'consumer', role: 'Consumer 2', position: { x: 200, y: 150 } },
      { id: 'consumer-3', type: 'consumer', role: 'Consumer 3', position: { x: 200, y: 250 } },
      { id: 'consumer-4', type: 'consumer', role: 'Consumer 4', position: { x: 200, y: 350 } }
    ],
    flow: [
      { from: 'channel', to: 'consumer-1', label: 'Compete for Message (One wins)', type: 'async' },
      { from: 'channel', to: 'consumer-2', label: 'Compete for Message (One wins)', type: 'async' },
      { from: 'channel', to: 'consumer-3', label: 'Compete for Message (One wins)', type: 'async' },
      { from: 'channel', to: 'consumer-4', label: 'Compete for Message (One wins)', type: 'async' }
    ],
    diagramData: {
      color: '#e67e22',
      icon: '🏆',
      layout: 'horizontal'
    },
    codeExample: `// Multiple consumers on same channel
for (int i = 0; i < 4; i++) {
  CompetingConsumer<Order> consumer = CompetingConsumer.builder()
    .channel(orderChannel)
    .processor(this::processOrder)
    .build();
  consumer.start();
}
// Only ONE consumer processes each message`
  },

  IdempotentReceiver: {
    id: 'idempotent-receiver',
    name: 'Idempotent Receiver',
    category: 'messaging-endpoints',
    description: 'Receiver that safely handles duplicate messages. Uses message IDs to detect and filter duplicates.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'message', type: 'message', role: 'Incoming Message', position: { x: 250, y: 150 }, config: { id: 'msg-123' } },
      { id: 'receiver', type: 'receiver', role: 'Idempotent Receiver', position: { x: 450, y: 150 } },
      { id: 'deduplicator', type: 'deduplicator', role: 'Message Deduplicator', position: { x: 450, y: 250 } },
      { id: 'cache', type: 'cache', role: 'Processed ID Cache', position: { x: 650, y: 250 } },
      { id: 'processor', type: 'processor', role: 'Message Processor', position: { x: 650, y: 150 } }
    ],
    flow: [
      { from: 'input', to: 'message', label: 'Receive', type: 'async' },
      { from: 'message', to: 'receiver', label: 'Deliver', type: 'async' },
      { from: 'receiver', to: 'deduplicator', label: 'Check Duplicate', type: 'sync' },
      { from: 'deduplicator', to: 'cache', label: 'Lookup ID', type: 'sync' },
      { from: 'cache', to: 'deduplicator', label: 'Return Status', type: 'sync' },
      { from: 'deduplicator', to: 'processor', label: 'Process (Not Duplicate)', type: 'sync' }
    ],
    diagramData: {
      color: '#16a085',
      icon: '🔑',
      layout: 'horizontal'
    },
    codeExample: `IdempotentReceiver<Order> receiver = IdempotentReceiver.builder()
  .idExtractor(Order::getId)
  .processedIdCache(CaffeineCache.create())
  .processor(this::processOrder)
  .build();

// Duplicate messages automatically filtered
receiver.receive(order);`
  },

  SelectiveConsumer: {
    id: 'selective-consumer',
    name: 'Selective Consumer',
    category: 'messaging-endpoints',
    description: 'Consumer that only processes messages matching specific criteria. Filters messages before processing.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'message-1', type: 'message', role: 'Message 1 (Match)', position: { x: 250, y: 100 } },
      { id: 'message-2', type: 'message', role: 'Message 2 (No Match)', position: { x: 250, y: 200 } },
      { id: 'consumer', type: 'consumer', role: 'Selective Consumer', position: { x: 450, y: 150 }, config: { selector: 'priority == HIGH' } },
      { id: 'processor', type: 'processor', role: 'Message Processor', position: { x: 650, y: 150 } }
    ],
    flow: [
      { from: 'input', to: 'message-1', label: 'Route', type: 'async' },
      { from: 'input', to: 'message-2', label: 'Route', type: 'async' },
      { from: 'message-1', to: 'consumer', label: 'Check (Matches)', type: 'async' },
      { from: 'message-2', to: 'consumer', label: 'Check (No Match)', type: 'async' },
      { from: 'consumer', to: 'processor', label: 'Process (Match Only)', type: 'sync' }
    ],
    diagramData: {
      color: '#8e44ad',
      icon: '🎯',
      layout: 'horizontal'
    },
    codeExample: `SelectiveConsumer<Order> consumer = SelectiveConsumer.builder()
  .selector(order -> order.getPriority() == Priority.HIGH)
  .processor(this::processHighPriority)
  .build();

// Only processes high-priority orders
consumer.start();`
  },

  // ==========================================================================
  // MESSAGING MANAGEMENT (4)
  // ==========================================================================

  WireTap: {
    id: 'wire-tap',
    name: 'Wire Tap',
    category: 'messaging-management',
    description: 'Inspects messages on a channel without affecting the original flow. Used for monitoring, auditing, and testing.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'main-flow', type: 'channel', role: 'Main Message Flow', position: { x: 300, y: 150 } },
      { id: 'wiretap', type: 'tap', role: 'Wire Tap', position: { x: 500, y: 150 } },
      { id: 'tap-channel', type: 'channel', role: 'Tap Channel (Copy)', position: { x: 500, y: 250 } },
      { id: 'inspector', type: 'inspector', role: 'Message Inspector', position: { x: 700, y: 250 } },
      { id: 'output', type: 'channel', role: 'Output Channel (Original)', position: { x: 700, y: 150 } }
    ],
    flow: [
      { from: 'input', to: 'main-flow', label: 'Receive Message', type: 'async' },
      { from: 'main-flow', to: 'wiretap', label: 'Copy Message', type: 'async' },
      { from: 'wiretap', to: 'tap-channel', label: 'Send Copy', type: 'async' },
      { from: 'wiretap', to: 'output', label: 'Forward Original', type: 'async' },
      { from: 'tap-channel', to: 'inspector', label: 'Inspect Copy', type: 'async' }
    ],
    diagramData: {
      color: '#2980b9',
      icon: '🔌',
      layout: 'horizontal'
    },
    codeExample: `WireTap<Order> wireTap = WireTap.builder()
  .tapChannel(MessageChannel.create())
  .inspector(order -> log.info("Order: {}", order.getId()))
  .build();

// Original message unaffected
Order received = wireTap.tap(orderChannel);`
  },

  PipesAndFilters: {
    id: 'pipes-and-filters',
    name: 'Pipes and Filters',
    category: 'messaging-management',
    description: 'Chain of processing steps where output of one step feeds into the next. Enables composable message processing.',
    components: [
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'filter-1', type: 'filter', role: 'Filter 1: Validate', position: { x: 300, y: 150 } },
      { id: 'filter-2', type: 'filter', role: 'Filter 2: Transform', position: { x: 500, y: 150 } },
      { id: 'filter-3', type: 'filter', role: 'Filter 3: Enrich', position: { x: 700, y: 150 } },
      { id: 'output', type: 'channel', role: 'Output Channel', position: { x: 900, y: 150 } }
    ],
    flow: [
      { from: 'input', to: 'filter-1', label: 'Send to Pipeline', type: 'async' },
      { from: 'filter-1', to: 'filter-2', label: 'Pipe Output', type: 'async' },
      { from: 'filter-2', to: 'filter-3', label: 'Pipe Output', type: 'async' },
      { from: 'filter-3', to: 'output', label: 'Final Output', type: 'async' }
    ],
    diagramData: {
      color: '#27ae60',
      icon: '🔧',
      layout: 'horizontal'
    },
    codeExample: `MessagePipeline<Order> pipeline = MessagePipeline.builder()
  .filter(orderValidator)
  .filter(orderTransformer)
  .filter(orderEnricher)
  .build();

Order result = pipeline.execute(order);`
  },

  SmartProxy: {
    id: 'smart-proxy',
    name: 'Smart Proxy',
    category: 'messaging-management',
    description: 'Intercepts messages to add cross-cutting concerns: logging, security, routing, monitoring.',
    components: [
      { id: 'client', type: 'client', role: 'Service Client', position: { x: 100, y: 150 } },
      { id: 'proxy', type: 'proxy', role: 'Smart Proxy', position: { x: 300, y: 150 } },
      { id: 'interceptor-1', type: 'interceptor', role: 'Logging Interceptor', position: { x: 300, y: 50 } },
      { id: 'interceptor-2', type: 'interceptor', role: 'Security Interceptor', position: { x: 300, y: 250 } },
      { id: 'service', type: 'service', role: 'Target Service', position: { x: 500, y: 150 } }
    ],
    flow: [
      { from: 'client', to: 'proxy', label: 'Invoke Service', type: 'sync' },
      { from: 'proxy', to: 'interceptor-1', label: 'Pre-Process (Log)', type: 'sync' },
      { from: 'interceptor-1', to: 'proxy', label: 'Return', type: 'sync' },
      { from: 'proxy', to: 'interceptor-2', label: 'Pre-Process (Auth)', type: 'sync' },
      { from: 'interceptor-2', to: 'proxy', label: 'Return', type: 'sync' },
      { from: 'proxy', to: 'service', label: 'Forward Call', type: 'sync' },
      { from: 'service', to: 'proxy', label: 'Return Result', type: 'sync' },
      { from: 'proxy', to: 'interceptor-1', label: 'Post-Process (Log)', type: 'sync' },
      { from: 'proxy', to: 'client', label: 'Return Result', type: 'sync' }
    ],
    diagramData: {
      color: '#9b59b6',
      icon: '🛡️',
      layout: 'horizontal'
    },
    codeExample: `SmartProxy<OrderService> proxy = SmartProxy.builder()
  .target(new OrderServiceImpl())
  .addInterceptor(new LoggingInterceptor())
  .addInterceptor(new SecurityInterceptor())
  .addInterceptor(new MonitoringInterceptor())
  .build();

Order order = proxy.placeOrder(request);
// Interceptors transparently add behavior`
  },

  TransactionalActor: {
    id: 'transactional-actor',
    name: 'Transactional Actor',
    category: 'messaging-management',
    description: 'Actor/processor that participates in distributed transactions. Ensures atomicity across message processing.',
    components: [
      { id: 'coordinator', type: 'coordinator', role: 'Transaction Coordinator', position: { x: 400, y: 50 } },
      { id: 'input', type: 'channel', role: 'Input Channel', position: { x: 100, y: 150 } },
      { id: 'actor', type: 'actor', role: 'Transactional Actor', position: { x: 400, y: 150 } },
      { id: 'resource-1', type: 'resource', role: 'Transactional Resource 1', position: { x: 600, y: 80 } },
      { id: 'resource-2', type: 'resource', role: 'Transactional Resource 2', position: { x: 600, y: 220 } },
      { id: 'output', type: 'channel', role: 'Output Channel', position: { x: 800, y: 150 } }
    ],
    flow: [
      { from: 'input', to: 'actor', label: 'Receive Message', type: 'async' },
      { from: 'actor', to: 'coordinator', label: 'Begin Transaction', type: 'sync' },
      { from: 'coordinator', to: 'actor', label: 'TX Context', type: 'sync' },
      { from: 'actor', to: 'resource-1', label: 'Enlist in TX', type: 'sync' },
      { from: 'actor', to: 'resource-2', label: 'Enlist in TX', type: 'sync' },
      { from: 'actor', to: 'coordinator', label: 'Commit TX', type: 'sync' },
      { from: 'coordinator', to: 'resource-1', label: 'Commit', type: 'sync' },
      { from: 'coordinator', to: 'resource-2', label: 'Commit', type: 'sync' },
      { from: 'actor', to: 'output', label: 'Send Result', type: 'async' }
    ],
    diagramData: {
      color: '#c0392b',
      icon: '💾',
      layout: 'horizontal'
    },
    codeExample: `TransactionalActor<OrderProcessor> actor =
  TransactionalActor.builder()
    .processor(orderProcessor)
    .transactionManager(jtaTransactionManager)
    .resources(dataSource, jmsBroker)
    .build();

actor.process(order);
// All resources commit atomically or rollback together`
  }
};

// ============================================================================
// Helper Functions
// ============================================================================

export function getPatternById(id: string): Pattern | undefined {
  return PATTERNS[id];
}

export function getPatternsByCategory(category: PatternCategory): Pattern[] {
  return Object.values(PATTERNS).filter(p => p.category === category);
}

export function getEnterprisePatterns(): Pattern[] {
  return getPatternsByCategory('enterprise');
}

export function getMessagingPatterns(): Pattern[] {
  return Object.values(PATTERNS).filter(p => p.category.startsWith('messaging-'));
}

export function getAllPatternIds(): string[] {
  return Object.keys(PATTERNS);
}

export function getPatternCategories(): PatternCategory[] {
  return [
    'enterprise',
    'messaging-channels',
    'messaging-construction',
    'messaging-routing',
    'messaging-transformation',
    'messaging-endpoints',
    'messaging-management'
  ];
}

export function getPatternCount(): number {
  return Object.keys(PATTERNS).length;
}

export function getPatternCountByCategory(): Record<PatternCategory, number> {
  const counts: Record<string, number> = {};
  for (const category of getPatternCategories()) {
    counts[category] = getPatternsByCategory(category as PatternCategory).length;
  }
  return counts as Record<PatternCategory, number>;
}
