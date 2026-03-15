/**
 * Complete JOTP Architecture Data
 * Defines all primitives, patterns, and their relationships
 */

export type ComponentCategory = 'core' | 'lifecycle' | 'enterprise' | 'messaging' | 'observability';

export interface JOTPComponent {
  id: string;
  name: string;
  category: ComponentCategory;
  layer: number;
  description: string;
  otpEquivalent?: string;
  features: string[];
  dependencies: string[];
  color: string;
  documentation?: string;
}

export interface ArchitectureLayer {
  id: string;
  name: string;
  description: string;
  yPosition: number;
  components: string[];
}

/**
 * All JOTP Primitives and Patterns
 */
export const JOTP_COMPONENTS: JOTPComponent[] = [
  // === CORE PRIMITIVES (Layer 1) ===
  {
    id: 'proc',
    name: 'Proc<S,M>',
    category: 'core',
    layer: 1,
    description: 'Lightweight process with virtual threads and mailbox-based message passing (OTP: spawn/3)',
    otpEquivalent: 'spawn/3, gen_server',
    features: [
      'Virtual thread-based execution',
      'Mailbox message queue',
      'Pure state handler functions',
      'Synchronous ask() with timeout',
      'Link and trap_exit support',
      'Message pattern matching'
    ],
    dependencies: [],
    color: '#3b82f6'
  },
  {
    id: 'state-machine',
    name: 'StateMachine<S,E,D>',
    category: 'core',
    layer: 1,
    description: 'Full gen_statem implementation with state transitions, timeouts, and event postponement',
    otpEquivalent: 'gen_statem',
    features: [
      'State enter/exit callbacks',
      'Event postponement and replay',
      'State/Event/Generic timeouts',
      'Internal events',
      'Reply actions',
      'Transition functions'
    ],
    dependencies: ['proc'],
    color: '#3b82f6'
  },
  {
    id: 'parallel',
    name: 'Parallel',
    category: 'core',
    layer: 1,
    description: 'Structured fan-out with fail-fast semantics using StructuredTaskScope (OTP: pmap)',
    otpEquivalent: 'pmap',
    features: [
      'Structured concurrency',
      'Virtual thread pooling',
      'Fail-fast error propagation',
      'Result aggregation',
      'Exception handling'
    ],
    dependencies: [],
    color: '#3b82f6'
  },
  {
    id: 'proc-link',
    name: 'ProcLink',
    category: 'core',
    layer: 1,
    description: 'Bilateral crash propagation between processes (OTP: link/1, spawn_link/3)',
    otpEquivalent: 'link/1, spawn_link/3',
    features: [
      'Bidirectional monitoring',
      'Automatic exit signal propagation',
      'Exit signal trapping',
      'Linked process trees'
    ],
    dependencies: ['proc'],
    color: '#3b82f6'
  },

  // === LIFECYCLE MANAGEMENT (Layer 2) ===
  {
    id: 'supervisor',
    name: 'Supervisor',
    category: 'lifecycle',
    layer: 2,
    description: 'Fault tolerance tree with multiple restart strategies (OTP: supervisor)',
    otpEquivalent: 'supervisor',
    features: [
      'ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE',
      'SIMPLE_ONE_FOR_ONE dynamic pools',
      'ChildSpec with restart intensity',
      'Dynamic child management',
      'Graceful shutdown handling',
      'AutoShutdown on significant exit'
    ],
    dependencies: ['proc', 'proc-link'],
    color: '#8b5cf6'
  },
  {
    id: 'proc-monitor',
    name: 'ProcMonitor',
    category: 'lifecycle',
    layer: 2,
    description: 'Unilateral process monitoring with DOWN notifications (OTP: monitor/2)',
    otpEquivalent: 'monitor/2',
    features: [
      'Unilateral monitoring',
      'DOWN message delivery',
      'No crash propagation',
      'Multiple monitors per process',
      'Automatic demonitoring'
    ],
    dependencies: ['proc'],
    color: '#8b5cf6'
  },
  {
    id: 'proc-registry',
    name: 'ProcRegistry',
    category: 'lifecycle',
    layer: 2,
    description: 'Global process name registry with auto-cleanup (OTP: global:register_name/2)',
    otpEquivalent: 'global:register_name/2',
    features: [
      'Global name registration',
      'PID lookup by name',
      'Auto-deregistration on exit',
      'Name collision detection',
      'Distributed registry support'
    ],
    dependencies: ['proc', 'proc-monitor'],
    color: '#8b5cf6'
  },
  {
    id: 'proc-timer',
    name: 'ProcTimer',
    category: 'lifecycle',
    layer: 2,
    description: 'Timed message delivery for timeouts and periodic operations (OTP: timer module)',
    otpEquivalent: 'timer:send_after/3, timer:send_interval/3',
    features: [
      'One-shot timed messages',
      'Periodic intervals',
      'Timer cancellation',
      'High-precision scheduling'
    ],
    dependencies: ['proc'],
    color: '#8b5cf6'
  },
  {
    id: 'proc-lib',
    name: 'ProcLib',
    category: 'lifecycle',
    layer: 2,
    description: 'Process library with startup handshake (OTP: proc_lib)',
    otpEquivalent: 'proc_lib',
    features: [
      'Synchronous startup handshake',
      'initAck() blocking',
      'Start result validation',
      'Parent-child synchronization'
    ],
    dependencies: ['proc'],
    color: '#8b5cf6'
  },
  {
    id: 'proc-sys',
    name: 'ProcSys',
    category: 'lifecycle',
    layer: 2,
    description: 'Process introspection and diagnostics (OTP: sys module)',
    otpEquivalent: 'sys',
    features: [
      'Get process state',
      'Suspend/resume processes',
      'Process statistics',
      'Runtime debugging'
    ],
    dependencies: ['proc'],
    color: '#8b5cf6'
  },
  {
    id: 'event-manager',
    name: 'EventManager<E>',
    category: 'lifecycle',
    layer: 2,
    description: 'Typed event manager with handler isolation (OTP: gen_event)',
    otpEquivalent: 'gen_event',
    features: [
      'Typed event handlers',
      'Synchronous/async notify',
      'Independent handler crashes',
      'Handler lifecycle management',
      'Event filtering'
    ],
    dependencies: ['proc'],
    color: '#8b5cf6'
  },

  // === ENTERPRISE PATTERNS (Layer 3) ===
  {
    id: 'circuit-breaker',
    name: 'CircuitBreaker',
    category: 'enterprise',
    layer: 3,
    description: 'Fault tolerance pattern for preventing cascading failures',
    features: [
      'Closed / Open / Half-Open states',
      'Failure threshold configuration',
      'Automatic recovery detection',
      'Fallback execution',
      'Metrics collection'
    ],
    dependencies: ['state-machine'],
    color: '#22c55e'
  },
  {
    id: 'saga',
    name: 'DistributedSagaCoordinator',
    category: 'enterprise',
    layer: 3,
    description: 'Saga pattern for distributed transaction coordination',
    features: [
      'Choreography/orchestration',
      'Compensating transactions',
      'Saga state persistence',
      'Timeout handling',
      'Rollback coordination'
    ],
    dependencies: ['state-machine', 'proc-registry'],
    color: '#22c55e'
  },
  {
    id: 'bulkhead',
    name: 'BulkheadIsolation',
    category: 'enterprise',
    layer: 3,
    description: 'Resource isolation pattern for preventing resource exhaustion',
    features: [
      'Concurrency limits',
      'Semaphore-based throttling',
      'Queue management',
      'Rejection handling',
      'Resource monitoring'
    ],
    dependencies: ['proc'],
    color: '#22c55e'
  },
  {
    id: 'health-checker',
    name: 'HealthChecker',
    category: 'enterprise',
    layer: 3,
    description: 'Health check management for service monitoring',
    features: [
      'Liveness / readiness probes',
      'Periodic health checks',
      'Status aggregation',
      'Failure detection',
      'Alerting integration'
    ],
    dependencies: ['proc', 'proc-timer'],
    color: '#22c55e'
  },
  {
    id: 'service-registry',
    name: 'ServiceRegistry',
    category: 'enterprise',
    layer: 3,
    description: 'Service discovery and registration',
    features: [
      'Service registration',
      'Health-based filtering',
      'Load balancing support',
      'Service metadata',
      'Dynamic updates'
    ],
    dependencies: ['proc-registry', 'health-checker'],
    color: '#22c55e'
  },
  {
    id: 'multi-tenant-supervisor',
    name: 'MultiTenantSupervisor',
    category: 'enterprise',
    layer: 3,
    description: 'Multi-tenant isolation with per-tenant supervision trees',
    features: [
      'Tenant isolation',
      'Per-tenant resource limits',
      'Independent restart policies',
      'Tenant lifecycle management',
      'Resource quotas'
    ],
    dependencies: ['supervisor'],
    color: '#22c55e'
  },
  {
    id: 'pool-supervisor',
    name: 'PoolSupervisor',
    category: 'enterprise',
    layer: 3,
    description: 'Dynamic worker pool management',
    features: [
      'Dynamic pool sizing',
      'Worker lifecycle management',
      'Load-based scaling',
      'Graceful shutdown',
      'Pool statistics'
    ],
    dependencies: ['supervisor'],
    color: '#22c55e'
  },
  {
    id: 'idempotent-proc',
    name: 'IdempotentProc',
    category: 'enterprise',
    layer: 3,
    description: 'Idempotent process wrapper for safe retries',
    features: [
      'Message deduplication',
      'Idempotency key tracking',
      'Safe retry semantics',
      'Message filtering'
    ],
    dependencies: ['proc'],
    color: '#22c55e'
  },

  // === OBSERVABILITY (Layer 4) ===
  {
    id: 'process-metrics',
    name: 'ProcessMetrics',
    category: 'observability',
    layer: 4,
    description: 'OpenTelemetry integration for process observability',
    features: [
      'Throughput tracking',
      'Latency histograms',
      'Error rate monitoring',
      'Process lifecycle events',
      'Custom metrics'
    ],
    dependencies: ['proc'],
    color: '#f59e0b'
  },
  {
    id: 'metrics-collector',
    name: 'MetricsCollector',
    category: 'observability',
    layer: 4,
    description: 'Framework-wide metrics collection and aggregation',
    features: [
      'Counter/Gauge/Histogram',
      'Dimensional metrics',
      'Aggregation windows',
      'Export to OTLP',
      'Real-time dashboards'
    ],
    dependencies: [],
    color: '#f59e0b'
  },

  // === LEGACY MESSAGING PATTERNS (Layer 5 - Deprecated but shown for reference) ===
  {
    id: 'message-channels',
    name: 'Message Channels',
    category: 'messaging',
    layer: 5,
    description: 'Enterprise Integration Patterns: Message Channels (deprecated in reactive)',
    features: [
      'Point-to-Point channels',
      'Publish-Subscribe channels',
      'Data type channels',
      'Dead letter channels'
    ],
    dependencies: [],
    color: '#f97316'
  },
  {
    id: 'message-routing',
    name: 'Message Routing',
    category: 'messaging',
    layer: 5,
    description: 'Enterprise Integration Patterns: Message Routing (deprecated in reactive)',
    features: [
      'Content-based router',
      'Recipient list',
      'Splitter',
      'Aggregator',
      'Resequencer',
      'Scatter-Gather',
      'Routing slip'
    ],
    dependencies: ['message-channels'],
    color: '#f97316'
  },
  {
    id: 'message-transformation',
    name: 'Message Transformation',
    category: 'messaging',
    layer: 5,
    description: 'Enterprise Integration Patterns: Message Transformation (deprecated in reactive)',
    features: [
      'Content enricher',
      'Translator',
      'Normalizer',
      'Claim check'
    ],
    dependencies: ['message-channels'],
    color: '#f97316'
  },
  {
    id: 'messaging-endpoints',
    name: 'Messaging Endpoints',
    category: 'messaging',
    layer: 5,
    description: 'Enterprise Integration Patterns: Messaging Endpoints (deprecated in reactive)',
    features: [
      'Competing consumers',
      'Message dispatcher',
      'Polling consumer',
      'Selective consumer'
    ],
    dependencies: ['message-channels'],
    color: '#f97316'
  },
  {
    id: 'system-management',
    name: 'System Management',
    category: 'messaging',
    layer: 5,
    description: 'Enterprise Integration Patterns: System Management (deprecated in reactive)',
    features: [
      'Correlation ID',
      'Dead letter channel',
      'Guaranteed delivery',
      'Idempotent receiver',
      'Message bridge',
      'Message expiration',
      'Process manager',
      'Wire tap'
    ],
    dependencies: ['message-channels'],
    color: '#f97316'
  }
];

/**
 * Architecture layer definitions
 */
export const ARCHITECTURE_LAYERS: ArchitectureLayer[] = [
  {
    id: 'core',
    name: 'Core Primitives',
    description: 'Fundamental OTP primitives implementing Erlang/OTP core functionality',
    yPosition: 0,
    components: ['proc', 'state-machine', 'parallel', 'proc-link']
  },
  {
    id: 'lifecycle',
    name: 'Lifecycle Management',
    description: 'Process supervision, monitoring, and registry services',
    yPosition: 200,
    components: ['supervisor', 'proc-monitor', 'proc-registry', 'proc-timer', 'proc-lib', 'proc-sys', 'event-manager']
  },
  {
    id: 'enterprise',
    name: 'Enterprise Patterns',
    description: 'Enterprise integration patterns for production systems',
    yPosition: 400,
    components: ['circuit-breaker', 'saga', 'bulkhead', 'health-checker', 'service-registry', 'multi-tenant-supervisor', 'pool-supervisor', 'idempotent-proc']
  },
  {
    id: 'observability',
    name: 'Observability',
    description: 'Monitoring, metrics, and distributed tracing',
    yPosition: 600,
    components: ['process-metrics', 'metrics-collector']
  },
  {
    id: 'messaging',
    name: 'Messaging Patterns',
    description: 'Enterprise Integration Patterns (deprecated in favor of reactive)',
    yPosition: 800,
    components: ['message-channels', 'message-routing', 'message-transformation', 'messaging-endpoints', 'system-management']
  }
];

/**
 * Helper functions
 */
export function getComponentById(id: string): JOTPComponent | undefined {
  return JOTP_COMPONENTS.find(c => c.id === id);
}

export function getComponentsByCategory(category: ComponentCategory): JOTPComponent[] {
  return JOTP_COMPONENTS.filter(c => c.category === category);
}

export function getComponentsByLayer(layer: number): JOTPComponent[] {
  return JOTP_COMPONENTS.filter(c => c.layer === layer);
}

export function getDependencies(componentId: string): JOTPComponent[] {
  const component = getComponentById(componentId);
  if (!component) return [];

  return component.dependencies
    .map(depId => getComponentById(depId))
    .filter((c): c is JOTPComponent => c !== undefined);
}

export function getDependents(componentId: string): JOTPComponent[] {
  return JOTP_COMPONENTS.filter(c =>
    c.dependencies.includes(componentId)
  );
}

export function getCategoryColor(category: ComponentCategory): string {
  const colors: Record<ComponentCategory, string> = {
    core: '#3b82f6',
    lifecycle: '#8b5cf6',
    enterprise: '#22c55e',
    messaging: '#f97316',
    observability: '#f59e0b'
  };
  return colors[category];
}

export function searchComponents(query: string): JOTPComponent[] {
  const lowerQuery = query.toLowerCase();
  return JOTP_COMPONENTS.filter(c =>
    c.name.toLowerCase().includes(lowerQuery) ||
    c.description.toLowerCase().includes(lowerQuery) ||
    c.otpEquivalent?.toLowerCase().includes(lowerQuery) ||
    c.features.some(f => f.toLowerCase().includes(lowerQuery))
  );
}
