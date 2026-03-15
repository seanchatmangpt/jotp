/**
 * React hook for data loading state machine
 */

import { useMachine } from '@xstate/react';
import { dataLoadingMachine, DataType } from '@/lib/data-loading-machine';

export interface UseDataLoadingOptions {
  onSuccess?: (dataType: DataType, data: unknown) => void;
  onError?: (error: Error) => void;
}

export function useDataLoading(options: UseDataLoadingOptions = {}) {
  const [state, send] = useMachine(dataLoadingMachine);

  // Subscribe to state changes
  if (String(state.value) === 'success' && options.onSuccess && state.context.loadingType) {
    const data = state.context.data.get(state.context.loadingType);
    options.onSuccess(state.context.loadingType, data);
  }

  if (String(state.value) === 'error' && options.onError && state.context.error) {
    options.onError(new Error(state.context.error));
  }

  return {
    // State
    state: state.value,
    isIdle: String(state.value) === 'idle',
    isLoading: String(state.value) === 'loading',
    isSuccess: String(state.value) === 'success',
    isError: String(state.value) === 'error',

    // Context
    data: state.context.data,
    error: state.context.error,
    timestamp: state.context.timestamp,
    loadingType: state.context.loadingType,
    retryCount: state.context.retryCount,
    canRetry: state.context.retryCount < state.context.maxRetries,

    // Actions
    load: (dataType: DataType) => send({ type: 'LOAD', dataType }),
    retry: () => send({ type: 'RETRY' }),
    dismiss: () => send({ type: 'DISMISS' }),
    clear: (dataType: DataType) => send({ type: 'CLEAR', dataType }),

    // Helpers
    getData: (dataType: DataType) => state.context.data.get(dataType),
    hasData: (dataType: DataType) => state.context.data.has(dataType),

    // Raw state and send for advanced usage
    rawState: state,
    rawSend: send,
  };
}
