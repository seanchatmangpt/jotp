'use client';

import React, { useCallback, useEffect, useState, useMemo } from 'react';
import {
  ReactFlow as ReactFlowComponent,
  Background,
  Controls,
  MiniMap,
  Node,
  Edge,
  addEdge,
  Connection,
  useNodesState,
  useEdgesState,
  MarkerType,
  BackgroundVariant,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import StateNode from '../nodes/state-node';
import EventNode from '../nodes/event-node';

// Types
export type TransitionType =
  | 'NextState'
  | 'KeepState'
  | 'RepeatState'
  | 'Stop'
  | 'StopAndReply';

export type SMEventType =
  | 'User'
  | 'StateTimeout'
  | 'EventTimeout'
  | 'GenericTimeout'
  | 'Internal'
  | 'Enter';

export interface Action {
  type:
    | 'Postpone'
    | 'NextEvent'
    | 'SetStateTimeout'
    | 'CancelStateTimeout'
    | 'SetEventTimeout'
    | 'CancelEventTimeout'
    | 'SetGenericTimeout'
    | 'CancelGenericTimeout'
    | 'Reply';
  payload?: unknown;
}

export interface Transition {
  type: TransitionType;
  nextState?: string;
  actions?: Action[];
  duration?: number;
}

export interface StateMachineData {
  states: Record<
    string,
    {
      data: Record<string, unknown>;
      entryCount: number;
      exitCount: number;
      activeTimeouts: Array<{
        type: 'StateTimeout' | 'EventTimeout' | 'GenericTimeout';
        remaining: number;
      }>;
    }
  >;
  postponedEvents: Array<{ eventType: SMEventType; payload?: Record<string, unknown> }>;
  currentState: string;
  transitionHistory: Array<{
    from: string;
    to: string;
    event: SMEventType;
    transition: TransitionType;
    timestamp: number;
  }>;
}

interface StateTransitionsFlowProps {
  initialState?: string;
  states?: string[];
  transitions?: Record<
    string,
    Record<string, { type: TransitionType; nextState?: string }>
  >;
}

const nodeTypes: any = {
  stateNode: StateNode,
  eventNode: EventNode,
};

const StateTransitionsFlow: React.FC<StateTransitionsFlowProps> = ({
  initialState = 'idle',
  states = ['idle', 'processing', 'completed', 'failed'],
  transitions = {},
}) => {
  const [machineData, setMachineData] = useState<StateMachineData>({
    states: Object.fromEntries(
      states.map((s) => [
        s,
        {
          data: {},
          entryCount: 0,
          exitCount: 0,
          activeTimeouts: [],
        },
      ])
    ),
    postponedEvents: [],
    currentState: initialState,
    transitionHistory: [],
  });

  const [isAnimating, setIsAnimating] = useState(false);
  const [currentTransition, setCurrentTransition] = useState<{
    from: string;
    to: string;
  } | null>(null);

  // Generate nodes
  const initialNodes: Node[] = useMemo(() => {
    const nodes: Node[] = [];

    // Event nodes (left side)
    const eventTypes: SMEventType[] = [
      'User',
      'StateTimeout',
      'EventTimeout',
      'GenericTimeout',
      'Internal',
      'Enter',
    ];

    eventTypes.forEach((eventType, index) => {
      nodes.push({
        id: `event-${eventType}`,
        type: 'eventNode',
        position: { x: 50, y: 100 + index * 120 },
        data: {
          eventType,
          payload:
            eventType === 'User'
              ? { action: 'start' }
              : eventType === 'EventTimeout'
                ? { timeout: 5000 }
                : undefined,
          onEventTrigger: handleEventTrigger,
        },
      });
    });

    // State nodes (center and right)
    states.forEach((state, index) => {
      const angle = (index * 2 * Math.PI) / states.length;
      const radius = 250;
      const x = 400 + radius * Math.cos(angle);
      const y = 350 + radius * Math.sin(angle);

      nodes.push({
        id: state,
        type: 'stateNode',
        position: { x, y },
        data: {
          label: state,
          stateType:
            state === initialState
              ? 'initial'
              : state === 'completed' || state === 'failed'
                ? 'terminal'
                : state === machineData.currentState
                  ? 'active'
                  : 'intermediate',
          data: machineData.states[state]?.data || {},
          entryCount: machineData.states[state]?.entryCount || 0,
          exitCount: machineData.states[state]?.exitCount || 0,
          activeTimeouts: machineData.states[state]?.activeTimeouts || [],
        },
      });
    });

    return nodes;
  }, [states, initialState, machineData]);

  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState([] as any[]);

  // Event handler
  const handleEventTrigger = useCallback(
    (eventType: SMEventType, payload?: Record<string, unknown>) => {
      if (isAnimating) return;

      setIsAnimating(true);

      // Find transition for current state and event
      const stateTransitions = transitions[machineData.currentState] || {};
      const transition = stateTransitions[eventType];

      if (!transition) {
        console.log(`No transition for ${eventType} in ${machineData.currentState}`);
        setIsAnimating(false);
        return;
      }

      const fromState = machineData.currentState;
      let toState = fromState;

      // Handle transition types
      switch (transition.type) {
        case 'NextState':
          toState = transition.nextState || fromState;
          break;
        case 'KeepState':
          toState = fromState;
          break;
        case 'RepeatState':
          // Re-enter current state (triggers enter callback)
          toState = fromState;
          break;
        case 'Stop':
        case 'StopAndReply':
          // Transition to terminal state
          toState = 'completed';
          break;
      }

      setCurrentTransition({ from: fromState, to: toState });

      // Update machine data
      setMachineData((prev) => {
        const newData = { ...prev };

        // Update exit count for from state
        if (newData.states[fromState]) {
          newData.states[fromState] = {
            ...newData.states[fromState],
            exitCount: newData.states[fromState].exitCount + 1,
          };
        }

        // Update entry count for to state
        if (newData.states[toState]) {
          newData.states[toState] = {
            ...newData.states[toState],
            entryCount: newData.states[toState].entryCount + 1,
            data: {
              ...newData.states[toState].data,
              lastEvent: eventType,
              lastPayload: payload,
            },
          };
        }

        newData.currentState = toState;
        newData.transitionHistory = [
          ...newData.transitionHistory,
          {
            from: fromState,
            to: toState,
            event: eventType,
            transition: transition.type,
            timestamp: Date.now(),
          },
        ];

        return newData;
      });

      // Animate transition
      setTimeout(() => {
        // Add edge for this transition
        const edgeId = `e-${fromState}-${toState}-${Date.now()}`;
        setEdges((eds) => [
          ...eds,
          {
            id: edgeId,
            source: `event-${eventType}`,
            target: toState,
            animated: true,
            label: transition.type,
            labelStyle: { fontWeight: 'bold', fill: '#374151' },
            labelBgStyle: { fill: '#f3f4f6', fillOpacity: 0.8 },
            style: {
              stroke:
                transition.type === 'Stop'
                  ? '#ef4444'
                  : transition.type === 'NextState'
                    ? '#3b82f6'
                    : '#10b981',
              strokeWidth: 2,
            },
            markerEnd: {
              type: MarkerType.ArrowClosed,
              color:
                transition.type === 'Stop'
                  ? '#ef4444'
                  : transition.type === 'NextState'
                    ? '#3b82f6'
                    : '#10b981',
            },
          },
        ]);

        setCurrentTransition(null);
        setIsAnimating(false);
      }, 500);
    },
    [machineData.currentState, transitions, isAnimating, setEdges]
  );

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  // Update nodes when machine data changes
  useEffect(() => {
    setNodes((nds) =>
      nds.map((node) => {
        if (node.type === 'stateNode') {
          const state = node.id as string;
          const stateData = machineData.states[state];
          return {
            ...node,
            data: {
              ...node.data,
              stateType:
                state === initialState
                  ? 'initial'
                  : state === 'completed' || state === 'failed'
                    ? 'terminal'
                    : state === machineData.currentState
                      ? 'active'
                      : 'intermediate',
              data: stateData?.data || {},
              entryCount: stateData?.entryCount || 0,
              exitCount: stateData?.exitCount || 0,
              activeTimeouts: stateData?.activeTimeouts || [],
            },
          };
        }
        return node;
      })
    );
  }, [machineData, setNodes, initialState]);

  return (
    <div className="w-full h-full relative">
      <ReactFlowComponent
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        nodeTypes={nodeTypes}
        fitView
        attributionPosition="bottom-left"
      >
        <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
        <Controls />
        <MiniMap
          nodeColor={(node) => {
            if (node.type === 'eventNode') return '#60a5fa';
            switch (node.data.stateType) {
              case 'initial':
                return '#22c55e';
              case 'terminal':
                return '#ef4444';
              case 'active':
                return '#3b82f6';
              default:
                return '#9ca3af';
            }
          }}
          maskColor="rgba(0, 0, 0, 0.1)"
        />
      </ReactFlowComponent>

      {/* Info Panel */}
      <div className="absolute top-4 right-4 bg-white dark:bg-gray-800 p-4 rounded-lg shadow-lg max-w-sm">
        <h3 className="font-bold text-gray-800 dark:text-gray-100 mb-2">
          State Machine Info
        </h3>
        <div className="text-sm text-gray-600 dark:text-gray-300">
          <div>Current State: <span className="font-mono font-bold">{machineData.currentState}</span></div>
          <div className="mt-2">
            <div className="font-semibold">Transition History:</div>
            <div className="max-h-40 overflow-y-auto">
              {machineData.transitionHistory.length === 0 ? (
                <div className="text-gray-400">No transitions yet</div>
              ) : (
                machineData.transitionHistory.slice(-10).map((entry, index) => (
                  <div key={index} className="text-xs font-mono mb-1">
                    {entry.from} → {entry.to} ({entry.transition})
                    <br />
                    <span className="text-gray-400">
                      via {entry.event}
                    </span>
                  </div>
                ))
              )}
            </div>
          </div>
          {machineData.postponedEvents.length > 0 && (
            <div className="mt-2">
              <div className="font-semibold">Postponed Events:</div>
              {machineData.postponedEvents.map((event, index) => (
                <div key={index} className="text-xs font-mono">
                  {event.eventType}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Transition Animation Overlay */}
      {isAnimating && currentTransition && (
        <div className="absolute inset-0 pointer-events-none flex items-center justify-center bg-black/5">
          <div className="bg-white dark:bg-gray-800 p-4 rounded-lg shadow-lg animate-pulse">
            <div className="text-lg font-bold text-gray-800 dark:text-gray-100">
              {currentTransition.from} → {currentTransition.to}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default StateTransitionsFlow;
