/**
 * React hook for filter state machine
 */

import { useMachine } from '@xstate/react';
import { filterMachine, Filter, FilterCategory } from '@/lib/filter-machine';

export interface UseFiltersOptions {
  data?: unknown[];
  onFilterChange?: (filters: Filter[], results: unknown[]) => void;
}

export function useFilters(options: UseFiltersOptions = {}) {
  const [state, send] = useMachine(filterMachine, {
    input: {
      filters: [],
      results: options.data || [],
      matchCount: options.data?.length || 0,
      totalCount: options.data?.length || 0,
      activeCategory: null,
    },
  });

  // Subscribe to state changes
  if (
    String(state.value) === 'applied' &&
    options.onFilterChange
  ) {
    options.onFilterChange(state.context.filters, state.context.results);
  }

  return {
    // State
    state: state.value,
    isIdle: String(state.value) === 'idle',
    isFiltering: String(state.value) === 'filtering',
    isApplied: String(state.value) === 'applied',

    // Context
    filters: state.context.filters,
    results: state.context.results,
    matchCount: state.context.matchCount,
    totalCount: state.context.totalCount,
    activeCategory: state.context.activeCategory,

    // Actions
    setFilter: (filter: Filter) => send({ type: 'SET_FILTER', filter }),
    updateFilter: (id: string, updates: Partial<Filter>) =>
      send({ type: 'UPDATE_FILTER', id, updates }),
    removeFilter: (id: string) => send({ type: 'REMOVE_FILTER', id }),
    clearAll: () => send({ type: 'CLEAR_ALL' }),
    apply: () => send({ type: 'APPLY' }),
    reset: () => send({ type: 'RESET' }),
    setCategory: (category: FilterCategory) =>
      send({ type: 'SET_CATEGORY', category }),

    // Helpers
    hasFilters: state.context.filters.length > 0,
    hasActiveFilters: state.context.filters.some((f) => f.enabled),
    isFiltered: state.context.matchCount < state.context.totalCount,

    // Raw state and send for advanced usage
    rawState: state,
    rawSend: send,
  };
}
