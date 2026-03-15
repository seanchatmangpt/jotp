/**
 * Data Loading State Machine
 *
 * Manages asynchronous data loading with retry logic and error handling.
 */

import { setup, assign, fromPromise } from 'xstate';

/**
 * Data types that can be loaded
 */
export type DataType = 'benchmarks' | 'results' | 'config' | 'history';

/**
 * Machine context
 */
export interface DataLoadingContext {
  data: Map<DataType, unknown>;
  error: string | null;
  timestamp: Date | null;
  loadingType: DataType | null;
  retryCount: number;
  maxRetries: number;
}

/**
 * Machine events
 */
export type DataLoadingEvent =
  | { type: 'LOAD'; dataType: DataType }
  | { type: 'RETRY' }
  | { type: 'DISMISS' }
  | { type: 'CLEAR'; dataType: DataType };

/**
 * Service to fetch data
 */
const fetchData = fromPromise(async ({
  input,
}: {
  input: { dataType: DataType; retryCount: number };
}) => {
  // Simulate API call
  const response = await fetch(`/api/data/${input.dataType}`);

  if (!response.ok) {
    throw new Error(`Failed to load ${input.dataType}: ${response.statusText}`);
  }

  return await response.json();
});

/**
 * Data loading state machine
 */
export const dataLoadingMachine = setup({
  types: {
    context: {} as DataLoadingContext,
    events: {} as DataLoadingEvent,
  },
  actors: {
    fetchData,
  },
  guards: {
    canRetry: ({ context }) => context.retryCount < context.maxRetries,
    hasData: ({ context }, params: { dataType: DataType }) =>
      context.data.has(params.dataType),
  },
  actions: {
    setLoadingType: assign({
      loadingType: ({ event }) => {
        if (event.type === 'LOAD') return event.dataType;
        return null;
      },
    }),
    setData: assign({
      data: ({ context, event }) => {
        if (event.type === 'done.invoke.fetcher') {
          const newData = new Map(context.data);
          newData.set(context.loadingType!, event.output);
          return newData;
        }
        return context.data;
      },
      timestamp: () => new Date(),
      error: null,
      retryCount: 0,
    }),
    setError: assign({
      error: ({ event }) => {
        if (event.type === 'error.platform.fetcher') {
          return event.error?.message || 'Unknown error';
        }
        return null;
      },
      retryCount: ({ context }) => context.retryCount + 1,
    }),
    clearError: assign({
      error: null,
    }),
    clearData: assign({
      data: ({ context, event }) => {
        if (event.type === 'CLEAR') {
          const newData = new Map(context.data);
          newData.delete(event.dataType);
          return newData;
        }
        return context.data;
      },
    }),
  },
}).createMachine({
  id: 'dataLoading',
  initial: 'idle',
  context: {
    data: new Map(),
    error: null,
    timestamp: null,
    loadingType: null,
    retryCount: 0,
    maxRetries: 3,
  },
  states: {
    idle: {
      on: {
        LOAD: {
          target: 'loading',
          actions: 'setLoadingType',
        },
        CLEAR: {
          actions: 'clearData',
        },
      },
    },
    loading: {
      invoke: {
        src: 'fetcher',
        input: ({ context }) => ({
          dataType: context.loadingType,
          retryCount: context.retryCount,
        }),
        onDone: {
          target: 'success',
          actions: 'setData',
        },
        onError: {
          target: 'error',
          actions: 'setError',
        },
      },
    },
    success: {
      on: {
        LOAD: {
          target: 'loading',
          actions: 'setLoadingType',
        },
        CLEAR: {
          actions: 'clearData',
        },
      },
    },
    error: {
      on: {
        RETRY: {
          target: 'loading',
          guard: 'canRetry',
        },
        DISMISS: {
          target: 'idle',
          actions: 'clearError',
        },
      },
    },
  },
});
