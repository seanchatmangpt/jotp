import { setup, assign } from 'xstate';

export interface SelectionItem {
  id: string
  type: 'primitive' | 'pattern' | 'system'
  label: string
  metadata?: Record<string, unknown>
}

export interface SelectionHistory {
  item: SelectionItem
  timestamp: number
  action: 'selected' | 'deselected'
}

export const selectionMachine = setup({
  types: {
    context: {} as {
      selectedItems: Map<string, SelectionItem>
      selectionHistory: SelectionHistory[]
      maxHistoryLength: number
      lastSelected: SelectionItem | null
      multiSelectEnabled: boolean
    },
    events: {} as
      | { type: 'SELECT'; item: SelectionItem }
      | { type: 'DESELECT'; itemId: string }
      | { type: 'TOGGLE'; item: SelectionItem }
      | { type: 'CLEAR_ALL' }
      | { type: 'SET_MULTI_SELECT'; enabled: boolean }
      | { type: 'UNDO_LAST' }
      | { type: 'REDO'; item: SelectionItem }
  },
  actions: {
    selectItem: assign({
      selectedItems: ({ context, event }) => {
        if (event.type !== 'SELECT' && event.type !== 'TOGGLE') return context.selectedItems;

        const newMap = new Map(context.selectedItems);

        if (event.type === 'SELECT') {
          if (!context.multiSelectEnabled) {
            newMap.clear();
          }
          newMap.set(event.item.id, event.item);
        } else if (event.type === 'TOGGLE') {
          if (newMap.has(event.item.id)) {
            newMap.delete(event.item.id);
          } else {
            if (!context.multiSelectEnabled) {
              newMap.clear();
            }
            newMap.set(event.item.id, event.item);
          }
        }

        return newMap;
      },
      lastSelected: ({ event }) => {
        if (event.type === 'SELECT' || event.type === 'TOGGLE') {
          return event.item;
        }
        return null;
      },
      selectionHistory: ({ context, event }) => {
        if (event.type !== 'SELECT' && event.type !== 'TOGGLE') return context.selectionHistory;

        const historyEntry: SelectionHistory = {
          item: event.item,
          timestamp: Date.now(),
          action: 'selected'
        };

        return [...context.selectionHistory, historyEntry].slice(-context.maxHistoryLength);
      }
    }),
    deselectItem: assign({
      selectedItems: ({ context, event }) => {
        if (event.type !== 'DESELECT') return context.selectedItems;

        const newMap = new Map(context.selectedItems);
        const item = newMap.get(event.itemId);

        if (item) {
          newMap.delete(event.itemId);
        }

        return newMap;
      },
      lastSelected: ({ context, event }) => {
        if (event.type === 'DESELECT') {
          const item = context.selectedItems.get(event.itemId);
          return item || null;
        }
        return context.lastSelected;
      },
      selectionHistory: ({ context, event }) => {
        if (event.type !== 'DESELECT') return context.selectionHistory;

        const item = context.selectedItems.get(event.itemId);
        if (!item) return context.selectionHistory;

        const historyEntry: SelectionHistory = {
          item,
          timestamp: Date.now(),
          action: 'deselected'
        };

        return [...context.selectionHistory, historyEntry].slice(-context.maxHistoryLength);
      }
    }),
    clearAll: assign({
      selectedItems: () => new Map(),
      lastSelected: () => null,
      selectionHistory: ({ context }) => {
        const historyEntry: SelectionHistory = {
          item: { id: 'all', type: 'system', label: 'Clear All' },
          timestamp: Date.now(),
          action: 'deselected'
        };
        return [...context.selectionHistory, historyEntry].slice(-context.maxHistoryLength);
      }
    }),
    setMultiSelect: assign({
      multiSelectEnabled: ({ event }) => {
        if (event.type === 'SET_MULTI_SELECT') {
          return event.enabled;
        }
        return false;
      }
    }),
    undoLast: assign({
      selectedItems: ({ context }) => {
        const lastAction = context.selectionHistory[context.selectionHistory.length - 1];
        if (!lastAction) return context.selectedItems;

        const newMap = new Map(context.selectedItems);

        if (lastAction.action === 'selected') {
          newMap.delete(lastAction.item.id);
        } else if (lastAction.action === 'deselected') {
          newMap.set(lastAction.item.id, lastAction.item);
        }

        return newMap;
      },
      selectionHistory: ({ context }) => {
        return context.selectionHistory.slice(0, -1);
      }
    })
  },
  guards: {
    hasSelections: ({ context }) => context.selectedItems.size > 0,
    hasHistory: ({ context }) => context.selectionHistory.length > 0,
    isSelected: ({ context, event }) => {
      if (event.type === 'TOGGLE') {
        return context.selectedItems.has(event.item.id);
      }
      return false;
    }
  }
}).createMachine({
  id: 'selection',
  initial: 'active',
  context: {
    selectedItems: new Map(),
    selectionHistory: [],
    maxHistoryLength: 50,
    lastSelected: null,
    multiSelectEnabled: false
  },
  states: {
    active: {
      on: {
        SELECT: {
          actions: 'selectItem'
        },
        TOGGLE: {
          actions: 'selectItem'
        },
        DESELECT: {
          guard: 'hasSelections',
          actions: 'deselectItem'
        },
        CLEAR_ALL: {
          guard: 'hasSelections',
          actions: 'clearAll'
        },
        SET_MULTI_SELECT: {
          actions: 'setMultiSelect'
        },
        UNDO_LAST: {
          guard: 'hasHistory',
          actions: 'undoLast'
        }
      }
    }
  }
});

export type SelectionMachine = typeof selectionMachine;

// Helper functions for React components
export const getSelectedIds = (context: SelectionMachine['context']): string[] => {
  return Array.from(context.selectedItems.keys());
};

export const getSelectedItems = (context: SelectionMachine['context']): SelectionItem[] => {
  return Array.from(context.selectedItems.values());
};

export const isSelected = (context: SelectionMachine['context'], itemId: string): boolean => {
  return context.selectedItems.has(itemId);
};

export const getSelectionByType = (
  context: SelectionMachine['context'],
  type: 'primitive' | 'pattern' | 'system'
): SelectionItem[] => {
  return Array.from(context.selectedItems.values()).filter(item => item.type === type);
};
