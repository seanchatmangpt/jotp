/**
 * Filter State Machine
 *
 * Manages filter application and data filtering for benchmark results.
 */

import { setup, assign } from 'xstate';

/**
 * Filter types
 */
export type FilterOperator = 'equals' | 'contains' | 'greaterThan' | 'lessThan' | 'between';

/**
 * Single filter definition
 */
export interface Filter {
  id: string;
  field: string;
  operator: FilterOperator;
  value: unknown;
  enabled: boolean;
}

/**
 * Filter category
 */
export type FilterCategory = 'name' | 'performance' | 'date' | 'custom';

/**
 * Machine context
 */
export interface FilterMachineContext {
  filters: Filter[];
  results: unknown[];
  matchCount: number;
  totalCount: number;
  activeCategory: FilterCategory | null;
}

/**
 * Machine events
 */
export type FilterMachineEvent =
  | { type: 'SET_FILTER'; filter: Filter }
  | { type: 'UPDATE_FILTER'; id: string; updates: Partial<Filter> }
  | { type: 'REMOVE_FILTER'; id: string }
  | { type: 'CLEAR_ALL' }
  | { type: 'APPLY' }
  | { type: 'RESET' }
  | { type: 'SET_CATEGORY'; category: FilterCategory };

/**
 * Filter function type
 */
type FilterFn = (item: unknown, filter: Filter) => boolean;

/**
 * Filter implementations
 */
const filterImplementations: Record<FilterOperator, FilterFn> = {
  equals: (item: unknown, filter: Filter) => {
    const value = (item as any)[filter.field];
    return value === filter.value;
  },
  contains: (item: unknown, filter: Filter) => {
    const value = (item as any)[filter.field];
    return String(value).toLowerCase().includes(String(filter.value).toLowerCase());
  },
  greaterThan: (item: unknown, filter: Filter) => {
    const value = (item as any)[filter.field];
    return value > filter.value;
  },
  lessThan: (item: unknown, filter: Filter) => {
    const value = (item as any)[filter.field];
    return value < filter.value;
  },
  between: (item: unknown, filter: Filter) => {
    const value = (item as any)[filter.field];
    const [min, max] = filter.value as [number, number];
    return value >= min && value <= max;
  },
};

/**
 * Apply filters to data
 */
const applyFilters = (data: unknown[], filters: Filter[]): unknown[] => {
  const enabledFilters = filters.filter((f) => f.enabled);

  if (enabledFilters.length === 0) {
    return data;
  }

  return data.filter((item) => {
    return enabledFilters.every((filter) => {
      const filterFn = filterImplementations[filter.operator];
      return filterFn(item, filter);
    });
  });
};

/**
 * Filter state machine
 */
export const filterMachine = setup({
  types: {
    context: {} as FilterMachineContext,
    events: {} as FilterMachineEvent,
  },
  actions: {
    setFilter: assign({
      filters: ({ context, event }) => {
        if (event.type === 'SET_FILTER') {
          return [...context.filters, event.filter];
        }
        return context.filters;
      },
    }),
    updateFilter: assign({
      filters: ({ context, event }) => {
        if (event.type === 'UPDATE_FILTER') {
          return context.filters.map((f) =>
            f.id === event.id ? { ...f, ...event.updates } : f
          );
        }
        return context.filters;
      },
    }),
    removeFilter: assign({
      filters: ({ context, event }) => {
        if (event.type === 'REMOVE_FILTER') {
          return context.filters.filter((f) => f.id !== event.id);
        }
        return context.filters;
      },
    }),
    clearAll: assign({
      filters: [],
      results: ({ context }) => context.results, // Keep original data
      matchCount: ({ context }) => context.totalCount,
    }),
    applyFilters: assign({
      results: ({ context }) => {
        const filtered = applyFilters(context.results, context.filters);
        return filtered;
      },
      matchCount: ({ context }) => {
        const filtered = applyFilters(context.results, context.filters);
        return filtered.length;
      },
    }),
    setCategory: assign({
      activeCategory: ({ event }) => {
        if (event.type === 'SET_CATEGORY') return event.category;
        return null;
      },
    }),
    setData: assign({
      results: (_, event: any) => event.data || [],
      totalCount: (_, event: any) => event.data?.length || 0,
      matchCount: (_, event: any) => event.data?.length || 0,
    }),
    reset: assign({
      filters: [],
      activeCategory: null,
      matchCount: ({ context }) => context.totalCount,
    }),
  },
  guards: {
    hasFilters: ({ context }) => context.filters.length > 0,
    hasActiveFilters: ({ context }) => context.filters.some((f) => f.enabled),
  },
}).createMachine({
  id: 'filter',
  initial: 'idle',
  context: {
    filters: [],
    results: [],
    matchCount: 0,
    totalCount: 0,
    activeCategory: null,
  },
  states: {
    idle: {
      on: {
        SET_FILTER: {
          target: 'filtering',
          actions: 'setFilter',
        },
        SET_CATEGORY: {
          actions: 'setCategory',
        },
      },
    },
    filtering: {
      on: {
        SET_FILTER: {
          actions: 'setFilter',
        },
        UPDATE_FILTER: {
          actions: 'updateFilter',
        },
        REMOVE_FILTER: {
          target: 'idle',
          actions: 'removeFilter',
          guard: 'hasFilters',
        },
        CLEAR_ALL: {
          target: 'idle',
          actions: 'clearAll',
        },
        APPLY: {
          target: 'applied',
          actions: 'applyFilters',
        },
        RESET: {
          target: 'idle',
          actions: 'reset',
        },
      },
    },
    applied: {
      on: {
        SET_FILTER: {
          target: 'filtering',
          actions: 'setFilter',
        },
        UPDATE_FILTER: {
          target: 'filtering',
          actions: 'updateFilter',
        },
        REMOVE_FILTER: {
          target: 'filtering',
          actions: 'removeFilter',
        },
        CLEAR_ALL: {
          target: 'idle',
          actions: 'clearAll',
        },
        SET_CATEGORY: {
          actions: 'setCategory',
        },
      },
    },
  },
});
