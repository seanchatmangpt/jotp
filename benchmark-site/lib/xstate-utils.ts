/**
 * XState Utilities
 *
 * Common guards, actions, services, and utilities for XState machines.
 */

import { setup, fromPromise } from 'xstate';

/**
 * Common guards
 */
export const guards = {
  /**
   * Check if context has data
   */
  hasData: () => ({
    pred: ({ context }: any) =>
      context.data !== null && context.data !== undefined,
  }),

  /**
   * Check if context has errors
   */
  hasErrors: () => ({
    pred: ({ context }: any) =>
      context.errors && context.errors.length > 0,
  }),

  /**
   * Check if retry count is below max
   */
  canRetry: (maxRetries: number = 3) => ({
    pred: ({ context }: any) => context.retryCount < maxRetries,
  }),

  /**
   * Check if loading type matches
   */
  isLoadingType: (type: string) => ({
    pred: ({ context }: any) => context.loadingType === type,
  }),

  /**
   * Check if array has items
   */
  hasItems: (arrayPath: string) => ({
    pred: ({ context }: any) => {
      const array = arrayPath.split('.').reduce((obj, key) => obj?.[key], context);
      return array && array.length > 0;
    },
  }),
};

/**
 * Common actions
 */
export const actions = {
  /**
   * Log state transition (development only)
   */
  logTransition: () => ({
    exec: ({ self }, event: any) => {
      if (process.env.NODE_ENV === 'development') {
        console.log('[XState]', self.id, 'transition:', event.type);
      }
    },
  }),

  /**
   * Log error (development only)
   */
  logError: () => ({
    exec: ({ context }: any, event: any) => {
      if (process.env.NODE_ENV === 'development') {
        console.error('[XState Error]', event.error);
      }
    },
  }),

  /**
   * Set loading state
   */
  setLoading: (loadingType?: string) => ({
    assign: ({ context }: any) => ({
      ...context,
      isLoading: true,
      loadingType,
      error: null,
    }),
  }),

  /**
   * Set success state
   */
  setSuccess: (data: any) => ({
    assign: ({ context }: any) => ({
      ...context,
      isLoading: false,
      data,
      error: null,
      timestamp: new Date(),
    }),
  }),

  /**
   * Set error state
   */
  setError: (error: Error) => ({
    assign: ({ context }: any) => ({
      ...context,
      isLoading: false,
      error: error.message,
      retryCount: (context.retryCount || 0) + 1,
    }),
  }),

  /**
   * Reset state to initial
   */
  reset: () => ({
    assign: () => ({
      data: null,
      error: null,
      isLoading: false,
      timestamp: null,
      retryCount: 0,
    }),
  }),

  /**
   * Merge data into context
   */
  mergeData: (data: any) => ({
    assign: ({ context }: any) => ({
      ...context,
      ...data,
    }),
  }),

  /**
   * Push to array in context
   */
  pushToArray: (key: string, item: any) => ({
    assign: ({ context }: any) => ({
      ...context,
      [key]: [...(context[key] || []), item],
    }),
  }),

  /**
   * Remove from array in context
   */
  removeFromArray: (key: string, predicate: (item: any) => boolean) => ({
    assign: ({ context }: any) => ({
      ...context,
      [key]: (context[key] || []).filter(predicate),
    }),
  }),

  /**
   * Update item in array
   */
  updateInArray: (
    key: string,
    predicate: (item: any) => boolean,
    updates: Partial<any>
  ) => ({
    assign: ({ context }: any) => ({
      ...context,
      [key]: (context[key] || []).map((item: any) =>
        predicate(item) ? { ...item, ...updates } : item
      ),
    }),
  }),
};

/**
 * Common services
 */
export const services = {
  /**
   * Fetch JSON from API
   */
  fetchJSON: fromPromise(async ({ input }: { input: { url: string } }) => {
    const response = await fetch(input.url);
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }
    return response.json();
  }),

  /**
   * Execute async function with timeout
   */
  withTimeout: fromPromise(
    async ({
      input,
    }: {
      input: { fn: () => Promise<any>; timeout: number };
    }) => {
      const { fn, timeout } = input;
      const timeoutPromise = new Promise((_, reject) =>
        setTimeout(() => reject(new Error('Timeout')), timeout)
      );
      return Promise.race([fn(), timeoutPromise]);
    }
  ),

  /**
   * Retry function with exponential backoff
   */
  retryWithBackoff: fromPromise(
    async ({
      input,
    }: {
      input: {
        fn: () => Promise<any>;
        maxRetries: number;
        baseDelay: number;
      };
    }) => {
      const { fn, maxRetries, baseDelay } = input;
      let lastError;

      for (let i = 0; i < maxRetries; i++) {
        try {
          return await fn();
        } catch (error) {
          lastError = error;
          const delay = baseDelay * Math.pow(2, i);
          await new Promise((resolve) => setTimeout(resolve, delay));
        }
      }

      throw lastError;
    }
  ),

  /**
   * Batch operations
   */
  batch: fromPromise(
    async ({
      input,
    }: {
      input: { operations: Array<() => Promise<any>>; batchSize: number };
    }) => {
      const { operations, batchSize } = input;
      const results = [];

      for (let i = 0; i < operations.length; i += batchSize) {
        const batch = operations.slice(i, i + batchSize);
        const batchResults = await Promise.all(batch.map((fn) => fn()));
        results.push(...batchResults);
      }

      return results;
    }
  ),
};

/**
 * Utility functions
 */
export const utils = {
  /**
   * Create a state machine with standard configuration
   */
  createMachine(config: any) {
    return setup(config);
  },

  /**
   * Create a type-safe event sender
   */
  createEventSender<T extends Record<string, any>>(machine: any) {
    return (type: keyof T & string, payload?: Partial<T[keyof T]>) => ({
      type,
      ...payload,
    });
  },

  /**
   * Extract machine context type
   */
  extractContextType: <T>() => {} as T,

  /**
   * Extract machine event type
   */
  extractEventType: <T>() => {} as T,

  /**
   * Create a machine selector
   */
  createSelector: <TContext, TResult>(
    selector: (context: TContext) => TResult
  ) => selector,

  /**
   * Compose multiple selectors
   */
  composeSelectors: <TContext, TSelectors extends Record<string, any>>(
    selectors: TSelectors
  ) => (context: TContext) => {
    return Object.fromEntries(
      Object.entries(selectors).map(([key, selector]) => [
        key,
        selector(context),
      ])
    ) as {
      [K in keyof TSelectors]: TSelectors[K] extends (
        context: TContext
      ) => infer R
        ? R
        : never;
    };
  },
};

/**
 * Type helpers
 */
export type ContextType<T> = T extends { types: { context: infer C } }
  ? C
  : never;

export type EventType<T> = T extends { types: { events: infer E } } ? E : never;

export type StateType<T> = T extends { initial: infer S } ? S : never;
