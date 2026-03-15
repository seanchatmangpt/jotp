/**
 * Benchmark State Machine
 *
 * Manages the lifecycle of benchmark execution with support for
 * running, pausing, resuming, and canceling benchmarks.
 */

import { setup, assign, fromPromise } from 'xstate';

/**
 * Benchmark result interface
 */
export interface BenchmarkResult {
  name: string;
  iterations: number;
  totalTime: number;
  avgTime: number;
  minTime: number;
  maxTime: number;
  opsPerSecond: number;
  timestamp: Date;
}

/**
 * Benchmark entry interface
 */
export interface Benchmark {
  id: string;
  name: string;
  description?: string;
  fn: () => Promise<void> | void;
  iterations?: number;
}

/**
 * Progress tracking
 */
export interface Progress {
  current: number;
  total: number;
  percentage: number;
}

/**
 * Machine context
 */
export interface BenchmarkMachineContext {
  benchmarks: Benchmark[];
  results: BenchmarkResult[];
  progress: Progress;
  errors: Array<{ benchmark: string; error: string }>;
  currentBenchmark?: string;
  startTime?: Date;
  endTime?: Date;
}

/**
 * Machine events
 */
export type BenchmarkMachineEvent =
  | { type: 'START'; benchmarks?: Benchmark[] }
  | { type: 'PAUSE' }
  | { type: 'RESUME' }
  | { type: 'RESET' }
  | { type: 'CANCEL' }
  | { type: 'PROGRESS'; current: number; total: number };

/**
 * Service to execute a single benchmark
 */
const executeBenchmark = fromPromise(async ({ input }: { input: { benchmark: Benchmark } }) => {
  const { benchmark } = input;
  const iterations = benchmark.iterations || 100;
  const times: number[] = [];

  for (let i = 0; i < iterations; i++) {
    const start = performance.now();
    await benchmark.fn();
    const end = performance.now();
    times.push(end - start);
  }

  const totalTime = times.reduce((a, b) => a + b, 0);
  const avgTime = totalTime / iterations;
  const minTime = Math.min(...times);
  const maxTime = Math.max(...times);

  return {
    name: benchmark.name,
    iterations,
    totalTime,
    avgTime,
    minTime,
    maxTime,
    opsPerSecond: 1000 / avgTime,
    timestamp: new Date(),
  } as BenchmarkResult;
});

/**
 * Benchmark state machine
 */
export const benchmarkMachine = setup({
  types: {
    context: {} as BenchmarkMachineContext,
    events: {} as BenchmarkMachineEvent,
  },
  actors: {
    executeBenchmark,
  },
  guards: {
    hasBenchmarks: ({ context }) => context.benchmarks.length > 0,
    hasErrors: ({ context }) => context.errors.length > 0,
  },
  actions: {
    setCurrentBenchmark: assign({
      currentBenchmark: ({ context }) => {
        const currentIndex = context.progress.current;
        return context.benchmarks[currentIndex]?.name;
      },
    }),
    updateProgress: assign({
      progress: ({ context, event }) => {
        if (event.type === 'PROGRESS') {
          return {
            current: event.current,
            total: event.total,
            percentage: (event.current / event.total) * 100,
          };
        }
        return context.progress;
      },
    }),
    addResult: assign({
      results: ({ context, event }) => {
        if (event.type === 'done.invoke.executor') {
          return [...context.results, event.output];
        }
        return context.results;
      },
    }),
    addError: assign({
      errors: ({ context, event }) => {
        if (event.type === 'error.platform.executor') {
          return [
            ...context.errors,
            {
              benchmark: context.currentBenchmark || 'unknown',
              error: event.error?.message || 'Unknown error',
            },
          ];
        }
        return context.errors;
      },
    }),
    setStartTime: assign({
      startTime: () => new Date(),
    }),
    setEndTime: assign({
      endTime: () => new Date(),
    }),
    reset: assign(() => ({
      benchmarks: [],
      results: [],
      progress: { current: 0, total: 0, percentage: 0 },
      errors: [],
      currentBenchmark: undefined,
      startTime: undefined,
      endTime: undefined,
    })),
  },
}).createMachine({
  id: 'benchmark',
  initial: 'idle',
  context: {
    benchmarks: [],
    results: [],
    progress: { current: 0, total: 0, percentage: 0 },
    errors: [],
  },
  states: {
    idle: {
      on: {
        START: {
          target: 'running',
          guard: 'hasBenchmarks',
          actions: 'setStartTime',
        },
      },
    },
    running: {
      initial: 'executing',
      states: {
        executing: {
          entry: 'setCurrentBenchmark',
          always: [
            {
              target: 'done',
              guard: ({ context }) =>
                context.progress.current >= context.benchmarks.length,
            },
            {
              target: 'running',
              actions: ({ context, self }) => {
                const nextIndex = context.progress.current + 1;
                self.send({ type: 'PROGRESS', current: nextIndex, total: context.benchmarks.length });
              },
            },
          ],
        },
        done: {
          type: 'final',
        },
      },
      onDone: {
        target: 'completed',
        actions: 'setEndTime',
      },
      on: {
        PAUSE: 'paused',
        CANCEL: 'canceled',
      },
    },
    paused: {
      on: {
        RESUME: 'running',
        CANCEL: 'canceled',
      },
    },
    completed: {
      on: {
        RESET: {
          target: 'idle',
          actions: 'reset',
        },
      },
    },
    canceled: {
      on: {
        RESET: {
          target: 'idle',
          actions: 'reset',
        },
      },
    },
    error: {
      on: {
        RESET: {
          target: 'idle',
          actions: 'reset',
        },
      },
    },
  },
});
