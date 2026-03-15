/**
 * TypeScript data structures for JOTP primitives visualization.
 * Complete data for all 15 core primitives with methods, relationships, and diagram metadata.
 */

export interface Primitive {
  id: string
  name: string
  description: string
  category: 'core' | 'lifecycle' | 'messaging'
  methods: Method[]
  relationships: Relationship[]
  diagramData: DiagramData
}

export interface Method {
  name: string
  signature: string
  description: string
  returnType: string
  parameters: Parameter[]
  isStatic?: boolean
}

export interface Parameter {
  name: string
  type: string
  description?: string
}

export interface Relationship {
  targetId: string
  type: RelationshipType
  label?: string
  bidirectional?: boolean
}

export type RelationshipType =
  | 'manages'
  | 'monitors'
  | 'links-to'
  | 'registers-with'
  | 'uses'
  | 'extends'
  | 'supervises'
  | 'spawned-by'

export interface DiagramData {
  color: string
  icon: string
  defaultPosition: { x: number; y: number }
  size?: { width: number; height: number }
}

// Color schemes by category
const COLORS = {
  core: '#3B82F6',      // Blue
  lifecycle: '#8B5CF6', // Purple
  messaging: '#F59E0B', // Orange
} as const

// All 15 JOTP primitives with complete data
export const PRIMITIVES: Primitive[] = [
  // CORE PRIMITIVES
  {
    id: 'proc',
    name: 'Proc',
    description: 'Lightweight process with virtual-thread mailbox — Java 26 equivalent of Erlang process',
    category: 'core',
    methods: [
      {
        name: 'spawn',
        signature: 'spawn(initial: S, handler: BiFunction<S, M, S>): Proc<S, M>',
        description: 'Static factory: create and start a process (mirrors spawn/3)',
        returnType: 'Proc<S, M>',
        parameters: [
          { name: 'initial', type: 'S', description: 'Initial state' },
          { name: 'handler', type: 'BiFunction<S, M, S>', description: 'Pure state handler' }
        ],
        isStatic: true
      },
      {
        name: 'tell',
        signature: 'tell(msg: M): void',
        description: 'Fire-and-forget message send (non-blocking)',
        returnType: 'void',
        parameters: [
          { name: 'msg', type: 'M', description: 'Message to send' }
        ]
      },
      {
        name: 'ask',
        signature: 'ask(msg: M): CompletableFuture<S>',
        description: 'Request-reply with default timeout',
        returnType: 'CompletableFuture<S>',
        parameters: [
          { name: 'msg', type: 'M', description: 'Message to send' }
        ]
      },
      {
        name: 'ask',
        signature: 'ask(msg: M, timeout: Duration): CompletableFuture<S>',
        description: 'Request-reply with custom timeout (gen_server:call)',
        returnType: 'CompletableFuture<S>',
        parameters: [
          { name: 'msg', type: 'M', description: 'Message to send' },
          { name: 'timeout', type: 'Duration', description: 'Timeout duration' }
        ]
      },
      {
        name: 'trapExits',
        signature: 'trapExits(trap: boolean): void',
        description: 'Enable/disable exit signal trapping (process_flag(trap_exit, true))',
        returnType: 'void',
        parameters: [
          { name: 'trap', type: 'boolean', description: 'True to trap exits as messages' }
        ]
      },
      {
        name: 'stop',
        signature: 'stop(): void',
        description: 'Gracefully stop the process',
        returnType: 'void',
        parameters: []
      },
      {
        name: 'addCrashCallback',
        signature: 'addCrashCallback(cb: Runnable): void',
        description: 'Register callback for abnormal termination',
        returnType: 'void',
        parameters: [
          { name: 'cb', type: 'Runnable', description: 'Crash callback' }
        ]
      }
    ],
    relationships: [
      { targetId: 'procref', type: 'uses', label: 'wrapped by' },
      { targetId: 'supervisor', type: 'managed-by', label: 'supervised by' },
      { targetId: 'proclink', type: 'links-to', label: 'bidirectional crash' },
      { targetId: 'procmonitor', type: 'monitors', label: 'monitored by' }
    ],
    diagramData: {
      color: COLORS.core,
      icon: '⚡',
      defaultPosition: { x: 400, y: 300 }
    }
  },

  {
    id: 'procref',
    name: 'ProcRef',
    description: 'Stable opaque handle to a supervised process — survives supervisor restarts',
    category: 'core',
    methods: [
      {
        name: 'tell',
        signature: 'tell(msg: M): void',
        description: 'Fire-and-forget message send',
        returnType: 'void',
        parameters: [
          { name: 'msg', type: 'M', description: 'Message to send' }
        ]
      },
      {
        name: 'ask',
        signature: 'ask(msg: M): CompletableFuture<S>',
        description: 'Request-reply',
        returnType: 'CompletableFuture<S>',
        parameters: [
          { name: 'msg', type: 'M', description: 'Message to send' }
        ]
      },
      {
        name: 'stop',
        signature: 'stop(): void',
        description: 'Stop the process',
        returnType: 'void',
        parameters: []
      },
      {
        name: 'proc',
        signature: 'proc(): Proc<S, M>',
        description: 'Get underlying process (with crash snapshot)',
        returnType: 'Proc<S, M>',
        parameters: []
      }
    ],
    relationships: [
      { targetId: 'proc', type: 'extends', label: 'wraps' },
      { targetId: 'supervisor', type: 'used-by', label: 'returned by' }
    ],
    diagramData: {
      color: COLORS.core,
      icon: '🔗',
      defaultPosition: { x: 600, y: 300 }
    }
  },

  {
    id: 'supervisor',
    name: 'Supervisor',
    description: 'OTP supervision tree node — hierarchical fault tolerance with restart strategies',
    category: 'lifecycle',
    methods: [
      {
        name: 'create',
        signature: 'create(strategy: Strategy, maxRestarts: int, window: Duration): Supervisor',
        description: 'Create supervisor with restart limits',
        returnType: 'Supervisor',
        parameters: [
          { name: 'strategy', type: 'Strategy', description: 'ONE_FOR_ONE | ONE_FOR_ALL | REST_FOR_ONE' },
          { name: 'maxRestarts', type: 'int', description: 'Max restarts in window' },
          { name: 'window', type: 'Duration', description: 'Time window' }
        ],
        isStatic: true
      },
      {
        name: 'createSimple',
        signature: 'createSimple(template: ChildSpec<S,M>, maxRestarts: int, window: Duration): Supervisor',
        description: 'Create SIMPLE_ONE_FOR_ONE supervisor for dynamic pools',
        returnType: 'Supervisor',
        parameters: [
          { name: 'template', type: 'ChildSpec<S,M>', description: 'Child spec template' },
          { name: 'maxRestarts', type: 'int', description: 'Max restarts in window' },
          { name: 'window', type: 'Duration', description: 'Time window' }
        ],
        isStatic: true
      },
      {
        name: 'supervise',
        signature: 'supervise(id: String, initialState: S, handler: BiFunction<S,M,S>): ProcRef<S,M>',
        description: 'Add a permanent worker child (backward-compatible)',
        returnType: 'ProcRef<S,M>',
        parameters: [
          { name: 'id', type: 'String', description: 'Child identifier' },
          { name: 'initialState', type: 'S', description: 'Initial state' },
          { name: 'handler', type: 'BiFunction<S,M,S>', description: 'Message handler' }
        ]
      },
      {
        name: 'startChild',
        signature: 'startChild(spec: ChildSpec<S,M>): ProcRef<S,M>',
        description: 'Add child with explicit ChildSpec',
        returnType: 'ProcRef<S,M>',
        parameters: [
          { name: 'spec', type: 'ChildSpec<S,M>', description: 'Child specification' }
        ]
      },
      {
        name: 'terminateChild',
        signature: 'terminateChild(id: String): void',
        description: 'Stop a child and retain its spec',
        returnType: 'void',
        parameters: [
          { name: 'id', type: 'String', description: 'Child ID to stop' }
        ]
      },
      {
        name: 'deleteChild',
        signature: 'deleteChild(id: String): void',
        description: 'Remove a stopped child\'s spec from tree',
        returnType: 'void',
        parameters: [
          { name: 'id', type: 'String', description: 'Child ID to delete' }
        ]
      },
      {
        name: 'whichChildren',
        signature: 'whichChildren(): List<ChildInfo>',
        description: 'Snapshot of current child tree state',
        returnType: 'List<ChildInfo>',
        parameters: []
      },
      {
        name: 'shutdown',
        signature: 'shutdown(): void',
        description: 'Gracefully shut down supervisor and all children',
        returnType: 'void',
        parameters: []
      }
    ],
    relationships: [
      { targetId: 'proc', type: 'supervises', label: 'manages' },
      { targetId: 'procref', type: 'uses', label: 'returns' }
    ],
    diagramData: {
      color: COLORS.lifecycle,
      icon: '🌳',
      defaultPosition: { x: 200, y: 200 }
    }
  },

  {
    id: 'statemachine',
    name: 'StateMachine',
    description: 'Java 26 gen_statem — full OTP parity with state/event/data separation',
    category: 'core',
    methods: [
      {
        name: 'of',
        signature: 'of(initialState: S, initialData: D, fn: TransitionFn<S,E,D>): StateMachine<S,E,D>',
        description: 'Create and start state machine (shortcut)',
        returnType: 'StateMachine<S,E,D>',
        parameters: [
          { name: 'initialState', type: 'S', description: 'Initial state' },
          { name: 'initialData', type: 'D', description: 'Initial data' },
          { name: 'fn', type: 'TransitionFn<S,E,D>', description: 'Transition function' }
        ],
        isStatic: true
      },
      {
        name: 'create',
        signature: 'create(initialState: S, initialData: D, fn: TransitionFn<S,E,D>): Builder<S,E,D>',
        description: 'Create builder with options (e.g., withStateEnter)',
        returnType: 'Builder<S,E,D>',
        parameters: [
          { name: 'initialState', type: 'S', description: 'Initial state' },
          { name: 'initialData', type: 'D', description: 'Initial data' },
          { name: 'fn', type: 'TransitionFn<S,E,D>', description: 'Transition function' }
        ],
        isStatic: true
      },
      {
        name: 'send',
        signature: 'send(event: E): void',
        description: 'Fire-and-forget event delivery (gen_statem:cast)',
        returnType: 'void',
        parameters: [
          { name: 'event', type: 'E', description: 'Event to send' }
        ]
      },
      {
        name: 'call',
        signature: 'call(event: E): CompletableFuture<D>',
        description: 'Request-reply event delivery (gen_statem:call)',
        returnType: 'CompletableFuture<D>',
        parameters: [
          { name: 'event', type: 'E', description: 'Event to send' }
        ]
      },
      {
        name: 'state',
        signature: 'state(): S',
        description: 'Get current state (thread-safe)',
        returnType: 'S',
        parameters: []
      },
      {
        name: 'data',
        signature: 'data(): D',
        description: 'Get current data (thread-safe)',
        returnType: 'D',
        parameters: []
      },
      {
        name: 'stop',
        signature: 'stop(): void',
        description: 'Graceful shutdown',
        returnType: 'void',
        parameters: []
      }
    ],
    relationships: [
      { targetId: 'proc', type: 'extends', label: 'built on' }
    ],
    diagramData: {
      color: COLORS.core,
      icon: '🔄',
      defaultPosition: { x: 400, y: 500 }
    }
  },

  {
    id: 'parallel',
    name: 'Parallel',
    description: 'Structured fan-out with fail-fast semantics (erlang:pmap)',
    category: 'core',
    methods: [
      {
        name: 'all',
        signature: 'all(tasks: List<Supplier<T>>): Result<List<T>, Exception>',
        description: 'Run all tasks concurrently with fail-fast',
        returnType: 'Result<List<T>, Exception>',
        parameters: [
          { name: 'tasks', type: 'List<Supplier<T>>', description: 'Tasks to run in parallel' }
        ],
        isStatic: true
      }
    ],
    relationships: [
      { targetId: 'result', type: 'uses', label: 'returns' }
    ],
    diagramData: {
      color: COLORS.core,
      icon: '⚡',
      defaultPosition: { x: 600, y: 500 }
    }
  },

  // LIFECYCLE PRIMITIVES
  {
    id: 'proclink',
    name: 'ProcLink',
    description: 'Bilateral crash propagation between two processes',
    category: 'lifecycle',
    methods: [
      {
        name: 'link',
        signature: 'link(a: Proc<?,?>, b: Proc<?,?>): void',
        description: 'Establish bidirectional link (link/1)',
        returnType: 'void',
        parameters: [
          { name: 'a', type: 'Proc<?,?>', description: 'First process' },
          { name: 'b', type: 'Proc<?,?>', description: 'Second process' }
        ],
        isStatic: true
      },
      {
        name: 'spawnLink',
        signature: 'spawnLink(parent: Proc<?,?>, initial: S, handler: BiFunction<S,M,S>): Proc<S,M>',
        description: 'Atomic spawn + link (spawn_link/3)',
        returnType: 'Proc<S,M>',
        parameters: [
          { name: 'parent', type: 'Proc<?,?>', description: 'Parent process' },
          { name: 'initial', type: 'S', description: 'Child initial state' },
          { name: 'handler', type: 'BiFunction<S,M,S>', description: 'Child handler' }
        ],
        isStatic: true
      }
    ],
    relationships: [
      { targetId: 'proc', type: 'uses', label: 'links' },
      { targetId: 'exitsignal', type: 'uses', label: 'delivers' }
    ],
    diagramData: {
      color: COLORS.lifecycle,
      icon: '🔗',
      defaultPosition: { x: 200, y: 400 }
    }
  },

  {
    id: 'procmonitor',
    name: 'ProcMonitor',
    description: 'Unilateral DOWN notifications when target process terminates',
    category: 'lifecycle',
    methods: [
      {
        name: 'monitor',
        signature: 'monitor(target: Proc<S,M>, downHandler: Consumer<Throwable>): MonitorRef<S,M>',
        description: 'Start monitoring a process (monitor/2)',
        returnType: 'MonitorRef<S,M>',
        parameters: [
          { name: 'target', type: 'Proc<S,M>', description: 'Process to monitor' },
          { name: 'downHandler', type: 'Consumer<Throwable>', description: 'DOWN callback' }
        ],
        isStatic: true
      },
      {
        name: 'demonitor',
        signature: 'demonitor(ref: MonitorRef<?,?>): void',
        description: 'Cancel a monitor (demonitor/1)',
        returnType: 'void',
        parameters: [
          { name: 'ref', type: 'MonitorRef<?,?>', description: 'Monitor reference' }
        ],
        isStatic: true
      }
    ],
    relationships: [
      { targetId: 'proc', type: 'monitors', label: 'watches' }
    ],
    diagramData: {
      color: COLORS.lifecycle,
      icon: '👁️',
      defaultPosition: { x: 400, y: 400 }
    }
  },

  {
    id: 'procregistry',
    name: 'ProcRegistry',
    description: 'Global process name registry — register/2, whereis/1',
    category: 'lifecycle',
    methods: [
      {
        name: 'register',
        signature: 'register(name: String, proc: Proc<?,?>): void',
        description: 'Register process under name',
        returnType: 'void',
        parameters: [
          { name: 'name', type: 'String', description: 'Registration name' },
          { name: 'proc', type: 'Proc<?,?>', description: 'Process to register' }
        ],
        isStatic: true
      },
      {
        name: 'whereis',
        signature: 'whereis(name: String): Optional<Proc<S,M>>',
        description: 'Look up process by name',
        returnType: 'Optional<Proc<S,M>>',
        parameters: [
          { name: 'name', type: 'String', description: 'Name to look up' }
        ],
        isStatic: true
      },
      {
        name: 'unregister',
        signature: 'unregister(name: String): void',
        description: 'Remove name from registry',
        returnType: 'void',
        parameters: [
          { name: 'name', type: 'String', description: 'Name to unregister' }
        ],
        isStatic: true
      },
      {
        name: 'registered',
        signature: 'registered(): Set<String>',
        description: 'Get all registered names',
        returnType: 'Set<String>',
        parameters: [],
        isStatic: true
      }
    ],
    relationships: [
      { targetId: 'proc', type: 'manages', label: 'tracks' }
    ],
    diagramData: {
      color: COLORS.lifecycle,
      icon: '📋',
      defaultPosition: { x: 600, y: 400 }
    }
  },

  {
    id: 'proctimer',
    name: 'ProcTimer',
    description: 'OTP-style process timers — timer:send_after/3, timer:send_interval/3',
    category: 'lifecycle',
    methods: [
      {
        name: 'sendAfter',
        signature: 'sendAfter(delayMs: long, target: Proc<?,M>, msg: M): TimerRef',
        description: 'Send message after delay (one-shot)',
        returnType: 'TimerRef',
        parameters: [
          { name: 'delayMs', type: 'long', description: 'Delay in milliseconds' },
          { name: 'target', type: 'Proc<?,M>', description: 'Target process' },
          { name: 'msg', type: 'M', description: 'Message to send' }
        ],
        isStatic: true
      },
      {
        name: 'sendInterval',
        signature: 'sendInterval(periodMs: long, target: Proc<?,M>, msg: M): TimerRef',
        description: 'Send message periodically (repeating)',
        returnType: 'TimerRef',
        parameters: [
          { name: 'periodMs', type: 'long', description: 'Period in milliseconds' },
          { name: 'target', type: 'Proc<?,M>', description: 'Target process' },
          { name: 'msg', type: 'M', description: 'Message to send' }
        ],
        isStatic: true
      },
      {
        name: 'cancel',
        signature: 'cancel(ref: TimerRef): boolean',
        description: 'Cancel a timer',
        returnType: 'boolean',
        parameters: [
          { name: 'ref', type: 'TimerRef', description: 'Timer to cancel' }
        ],
        isStatic: true
      }
    ],
    relationships: [
      { targetId: 'proc', type: 'uses', label: 'sends to' }
    ],
    diagramData: {
      color: COLORS.lifecycle,
      icon: '⏱️',
      defaultPosition: { x: 200, y: 600 }
    }
  },

  {
    id: 'procsys',
    name: 'ProcSys',
    description: 'OTP sys module — process introspection, debug tracing, hot code change',
    category: 'lifecycle',
    methods: [
      {
        name: 'getState',
        signature: 'getState(proc: Proc<S,M>): CompletableFuture<S>',
        description: 'Fetch current process state (sys:get_state)',
        returnType: 'CompletableFuture<S>',
        parameters: [
          { name: 'proc', type: 'Proc<S,M>', description: 'Target process' }
        ],
        isStatic: true
      },
      {
        name: 'suspend',
        signature: 'suspend(proc: Proc<?,?>): void',
        description: 'Pause message processing (sys:suspend)',
        returnType: 'void',
        parameters: [
          { name: 'proc', type: 'Proc<?,?>', description: 'Process to suspend' }
        ],
        isStatic: true
      },
      {
        name: 'resume',
        signature: 'resume(proc: Proc<?,?>): void',
        description: 'Resume message processing (sys:resume)',
        returnType: 'void',
        parameters: [
          { name: 'proc', type: 'Proc<?,?>', description: 'Process to resume' }
        ],
        isStatic: true
      },
      {
        name: 'statistics',
        signature: 'statistics(proc: Proc<?,?>): Stats',
        description: 'Get statistics snapshot (sys:statistics)',
        returnType: 'Stats',
        parameters: [
          { name: 'proc', type: 'Proc<?,?>', description: 'Target process' }
        ],
        isStatic: true
      },
      {
        name: 'trace',
        signature: 'trace(proc: Proc<S,M>, enable: boolean): void',
        description: 'Enable/disable live event tracing (sys:trace)',
        returnType: 'void',
        parameters: [
          { name: 'proc', type: 'Proc<S,M>', description: 'Process to trace' },
          { name: 'enable', type: 'boolean', description: 'True to enable' }
        ],
        isStatic: true
      },
      {
        name: 'codeChange',
        signature: 'codeChange(proc: Proc<S,M>, transformer: Function<S,S>): S',
        description: 'Hot state transformation (system_code_change/4)',
        returnType: 'S',
        parameters: [
          { name: 'proc', type: 'Proc<S,M>', description: 'Target process' },
          { name: 'transformer', type: 'Function<S,S>', description: 'State transformer' }
        ],
        isStatic: true
      }
    ],
    relationships: [
      { targetId: 'proc', type: 'uses', label: 'inspects' }
    ],
    diagramData: {
      color: COLORS.lifecycle,
      icon: '🔬',
      defaultPosition: { x: 400, y: 600 }
    }
  },

  {
    id: 'proclib',
    name: 'ProcLib',
    description: 'OTP proc_lib — synchronous startup with initialization handshake',
    category: 'lifecycle',
    methods: [
      {
        name: 'startLink',
        signature: 'startLink(initial: S, initHandler: Function<S,S>, loopHandler: BiFunction<S,M,S>): StartResult<S,M>',
        description: 'Spawn and block until ready (proc_lib:start_link/3)',
        returnType: 'StartResult<S,M>',
        parameters: [
          { name: 'initial', type: 'S', description: 'Initial state' },
          { name: 'initHandler', type: 'Function<S,S>', description: 'Init handler (must call initAck)' },
          { name: 'loopHandler', type: 'BiFunction<S,M,S>', description: 'Main loop handler' }
        ],
        isStatic: true
      },
      {
        name: 'initAck',
        signature: 'initAck(): void',
        description: 'Signal successful initialization (proc_lib:init_ack)',
        returnType: 'void',
        parameters: [],
        isStatic: true
      }
    ],
    relationships: [
      { targetId: 'proc', type: 'spawned-by', label: 'creates' }
    ],
    diagramData: {
      color: COLORS.lifecycle,
      icon: '🤝',
      defaultPosition: { x: 600, y: 600 }
    }
  },

  // MESSAGING PRIMITIVES
  {
    id: 'eventmanager',
    name: 'EventManager',
    description: 'OTP gen_event — decouples event producers from consumers',
    category: 'messaging',
    methods: [
      {
        name: 'start',
        signature: 'start(): EventManager<E>',
        description: 'Start new event manager with default timeout',
        returnType: 'EventManager<E>',
        parameters: [],
        isStatic: true
      },
      {
        name: 'start',
        signature: 'start(timeout: Duration): EventManager<E>',
        description: 'Start event manager with custom timeout',
        returnType: 'EventManager<E>',
        parameters: [
          { name: 'timeout', type: 'Duration', description: 'Timeout for sync operations' }
        ],
        isStatic: true
      },
      {
        name: 'start',
        signature: 'start(name: String): EventManager<E>',
        description: 'Start named event manager and register it',
        returnType: 'EventManager<E>',
        parameters: [
          { name: 'name', type: 'String', description: 'Registration name' }
        ],
        isStatic: true
      },
      {
        name: 'addHandler',
        signature: 'addHandler(handler: Handler<E>): void',
        description: 'Register event handler (gen_event:add_handler/3)',
        returnType: 'void',
        parameters: [
          { name: 'handler', type: 'Handler<E>', description: 'Handler to register' }
        ]
      },
      {
        name: 'notify',
        signature: 'notify(event: E): void',
        description: 'Broadcast event asynchronously (gen_event:notify/2)',
        returnType: 'void',
        parameters: [
          { name: 'event', type: 'E', description: 'Event to broadcast' }
        ]
      },
      {
        name: 'syncNotify',
        signature: 'syncNotify(event: E): void',
        description: 'Broadcast and wait for all handlers (gen_event:sync_notify/2)',
        returnType: 'void',
        parameters: [
          { name: 'event', type: 'E', description: 'Event to broadcast' }
        ]
      },
      {
        name: 'deleteHandler',
        signature: 'deleteHandler(handler: Handler<E>): boolean',
        description: 'Remove handler (gen_event:delete_handler/3)',
        returnType: 'boolean',
        parameters: [
          { name: 'handler', type: 'Handler<E>', description: 'Handler to remove' }
        ]
      },
      {
        name: 'call',
        signature: 'call(handler: Handler<E>, event: E): void',
        description: 'Call specific handler (gen_event:call/4)',
        returnType: 'void',
        parameters: [
          { name: 'handler', type: 'Handler<E>', description: 'Specific handler' },
          { name: 'event', type: 'E', description: 'Event to send' }
        ]
      },
      {
        name: 'stop',
        signature: 'stop(): void',
        description: 'Shut down event manager',
        returnType: 'void',
        parameters: []
      }
    ],
    relationships: [
      { targetId: 'proc', type: 'extends', label: 'built on' },
      { targetId: 'procregistry', type: 'uses', label: 'can register with' }
    ],
    diagramData: {
      color: COLORS.messaging,
      icon: '📢',
      defaultPosition: { x: 200, y: 800 }
    }
  },

  {
    id: 'exitsignal',
    name: 'ExitSignal',
    description: 'OTP exit signal delivered as mailbox message when trap_exit is enabled',
    category: 'messaging',
    methods: [],
    relationships: [
      { targetId: 'proc', type: 'used-by', label: 'delivered to' },
      { targetId: 'proclink', type: 'used-by', label: 'sent by' }
    ],
    diagramData: {
      color: COLORS.messaging,
      icon: '🚨',
      defaultPosition: { x: 400, y: 800 }
    }
  },

  {
    id: 'result',
    name: 'Result',
    description: 'Sealed success/failure type — railway-oriented programming (Erlang {ok, V} | {error, R})',
    category: 'messaging',
    methods: [
      {
        name: 'ok',
        signature: 'ok(value: S): Result<S, F>',
        description: 'Create successful result ({ok, Value})',
        returnType: 'Result<S, F>',
        parameters: [
          { name: 'value', type: 'S', description: 'Success value' }
        ],
        isStatic: true
      },
      {
        name: 'err',
        signature: 'err(error: F): Result<S, F>',
        description: 'Create failed result ({error, Reason})',
        returnType: 'Result<S, F>',
        parameters: [
          { name: 'error', type: 'F', description: 'Error value' }
        ],
        isStatic: true
      },
      {
        name: 'of',
        signature: 'of(supplier: Supplier<S>): Result<S, F>',
        description: 'Wrap throwing supplier (converts exceptions to Err)',
        returnType: 'Result<S, F>',
        parameters: [
          { name: 'supplier', type: 'Supplier<S>', description: 'Operation to wrap' }
        ],
        isStatic: true
      },
      {
        name: 'map',
        signature: 'map(mapper: Function<S, T>): Result<T, F>',
        description: 'Transform success value (railway map)',
        returnType: 'Result<T, F>',
        parameters: [
          { name: 'mapper', type: 'Function<S, T>', description: 'Transformation function' }
        ]
      },
      {
        name: 'flatMap',
        signature: 'flatMap(mapper: Function<S, Result<T, F>>): Result<T, F>',
        description: 'Chain operations returning Results (railway flatMap)',
        returnType: 'Result<T, F>',
        parameters: [
          { name: 'mapper', type: 'Function<S, Result<T, F>>', description: 'Result-returning function' }
        ]
      },
      {
        name: 'fold',
        signature: 'fold(onSuccess: Function<S, T>, onError: Function<F, T>): T',
        description: 'Eliminate Result to single value',
        returnType: 'T',
        parameters: [
          { name: 'onSuccess', type: 'Function<S, T>', description: 'Success handler' },
          { name: 'onError', type: 'Function<F, T>', description: 'Error handler' }
        ]
      },
      {
        name: 'recover',
        signature: 'recover(handler: Function<F, Result<S, F>>): Result<S, F>',
        description: 'Recover from failure with handler',
        returnType: 'Result<S, F>',
        parameters: [
          { name: 'handler', type: 'Function<F, Result<S, F>>', description: 'Recovery function' }
        ]
      },
      {
        name: 'orElse',
        signature: 'orElse(defaultValue: S): S',
        description: 'Get success value or default',
        returnType: 'S',
        parameters: [
          { name: 'defaultValue', type: 'S', description: 'Default value' }
        ]
      },
      {
        name: 'orElseThrow',
        signature: 'orElseThrow(): S',
        description: 'Get success value or throw',
        returnType: 'S',
        parameters: []
      }
    ],
    relationships: [
      { targetId: 'parallel', type: 'used-by', label: 'returned by' }
    ],
    diagramData: {
      color: COLORS.messaging,
      icon: '🎯',
      defaultPosition: { x: 600, y: 800 }
    }
  },

  {
    id: 'application',
    name: 'Application',
    description: 'High-level lifecycle orchestrator for coordinating processes and supervisors',
    category: 'lifecycle',
    methods: [
      {
        name: 'builder',
        signature: 'builder(appName: String): Builder<S>',
        description: 'Create fluent builder for application',
        returnType: 'Builder<S>',
        parameters: [
          { name: 'appName', type: 'String', description: 'Application name' }
        ],
        isStatic: true
      },
      {
        name: 'start',
        signature: 'start(): CompletableFuture<S>',
        description: 'Start application: init → start → running',
        returnType: 'CompletableFuture<S>',
        parameters: []
      },
      {
        name: 'stop',
        signature: 'stop(): CompletableFuture<Void>',
        description: 'Stop application: services → supervisors → cleanup',
        returnType: 'CompletableFuture<Void>',
        parameters: []
      },
      {
        name: 'getState',
        signature: 'getState(): S',
        description: 'Get current application state',
        returnType: 'S',
        parameters: []
      },
      {
        name: 'getPhase',
        signature: 'getPhase(): ApplicationPhase',
        description: 'Get current application phase',
        returnType: 'ApplicationPhase',
        parameters: []
      },
      {
        name: 'getService',
        signature: 'getService(serviceName: String): Optional<ProcRef<?,?>>',
        description: 'Lookup named service',
        returnType: 'Optional<ProcRef<?,?>>',
        parameters: [
          { name: 'serviceName', type: 'String', description: 'Service name' }
        ]
      }
    ],
    relationships: [
      { targetId: 'supervisor', type: 'manages', label: 'coordinates' },
      { targetId: 'proc', type: 'manages', label: 'coordinates' },
      { targetId: 'procref', type: 'uses', label: 'tracks' }
    ],
    diagramData: {
      color: COLORS.lifecycle,
      icon: '🏗️',
      defaultPosition: { x: 800, y: 200 }
    }
  }
]

// Helper functions
export function getPrimitiveById(id: string): Primitive | undefined {
  return PRIMITIVES.find(p => p.id === id)
}

export function getPrimitivesByCategory(category: 'core' | 'lifecycle' | 'messaging'): Primitive[] {
  return PRIMITIVES.filter(p => p.category === category)
}

export function getPrimitiveMethods(id: string): Method[] {
  return getPrimitiveById(id)?.methods ?? []
}

export function getPrimitiveRelationships(id: string): Relationship[] {
  return getPrimitiveById(id)?.relationships ?? []
}
