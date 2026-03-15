/**
 * React hook for benchmark state machine
 */

import { useMachine } from '@xstate/react';
import { benchmarkMachine, Benchmark, BenchmarkResult } from '@/lib/benchmark-machine';

export interface UseBenchmarkMachineOptions {
  benchmarks?: Benchmark[];
  onError?: (error: Error) => void;
  onComplete?: (results: BenchmarkResult[]) => void;
}

export function useBenchmarkMachine(options: UseBenchmarkMachineOptions = {}) {
  const [state, send] = useMachine(benchmarkMachine, {
    input: {
      benchmarks: options.benchmarks || [],
      results: [],
      progress: { current: 0, total: 0, percentage: 0 },
      errors: [],
    },
  });

  // Subscribe to state changes
  if (String(state.value) === 'completed' && options.onComplete) {
    options.onComplete(state.context.results);
  }

  if (String(state.value) === 'error' && state.context.errors.length > 0 && options.onError) {
    const error = new Error(state.context.errors[0].error);
    options.onError(error);
  }

  return {
    // State
    state: state.value,
    isIdle: String(state.value) === 'idle',
    isRunning: String(state.value) === 'running',
    isPaused: String(state.value) === 'paused',
    isCompleted: String(state.value) === 'completed',
    isCanceled: String(state.value) === 'canceled',
    isError: String(state.value) === 'error',

    // Context
    results: state.context.results,
    progress: state.context.progress,
    errors: state.context.errors,
    currentBenchmark: state.context.currentBenchmark,
    startTime: state.context.startTime,
    endTime: state.context.endTime,

    // Actions
    start: (benchmarks?: Benchmark[]) =>
      send({ type: 'START', benchmarks }),
    pause: () => send({ type: 'PAUSE' }),
    resume: () => send({ type: 'RESUME' }),
    reset: () => send({ type: 'RESET' }),
    cancel: () => send({ type: 'CANCEL' }),

    // Raw state and send for advanced usage
    rawState: state,
    rawSend: send,
  };
}
