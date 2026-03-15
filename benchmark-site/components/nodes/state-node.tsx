'use client';

import React, { memo } from 'react';
import { Handle, Position, NodeProps } from '@xyflow/react';
import { StateMachineData } from '../flows/state-transitions';

interface StateNodeData {
  label: string;
  stateType: 'initial' | 'intermediate' | 'terminal' | 'active';
  data: Record<string, unknown>;
  entryCount: number;
  exitCount: number;
  activeTimeouts: Array<{
    type: 'StateTimeout' | 'EventTimeout' | 'GenericTimeout';
    remaining: number;
  }>;
}

const StateNode: React.FC<NodeProps> = ({ data, selected }) => {
  const typedData = data as unknown as StateNodeData;

  const getBorderColor = () => {
    switch (typedData.stateType) {
      case 'initial':
        return 'border-green-500';
      case 'terminal':
        return 'border-red-500';
      case 'active':
        return 'border-blue-500';
      default:
        return 'border-gray-400';
    }
  };

  const getBackgroundColor = () => {
    switch (typedData.stateType) {
      case 'initial':
        return 'bg-green-50 dark:bg-green-950';
      case 'terminal':
        return 'bg-red-50 dark:bg-red-950';
      case 'active':
        return 'bg-blue-50 dark:bg-blue-950';
      default:
        return 'bg-white dark:bg-gray-800';
    }
  };

  return (
    <div
      className={`
        px-4 py-3 rounded-lg border-2 shadow-lg min-w-[200px]
        ${getBorderColor()} ${getBackgroundColor()}
        ${selected ? 'ring-2 ring-purple-500 ring-offset-2' : ''}
        transition-all duration-300
      `}
    >
      {/* Input Handle */}
      <Handle
        type="target"
        position={Position.Top}
        className="w-3 h-3 !bg-gray-400"
      />

      {/* State Header */}
      <div className="flex items-center justify-between mb-2">
        <h3 className="font-bold text-gray-800 dark:text-gray-100">
          {typedData.label}
        </h3>
        <span
          className={`
            text-xs px-2 py-1 rounded
            ${
              typedData.stateType === 'initial'
                ? 'bg-green-200 text-green-800 dark:bg-green-900 dark:text-green-100'
                : typedData.stateType === 'terminal'
                  ? 'bg-red-200 text-red-800 dark:bg-red-900 dark:text-red-100'
                  : typedData.stateType === 'active'
                    ? 'bg-blue-200 text-blue-800 dark:bg-blue-900 dark:text-blue-100'
                    : 'bg-gray-200 text-gray-800 dark:bg-gray-700 dark:text-gray-100'
            }
          `}
        >
          {typedData.stateType}
        </span>
      </div>

      {/* State Data */}
      {Object.keys(typedData.data).length > 0 && (
        <div className="mb-2 p-2 bg-gray-50 dark:bg-gray-900 rounded text-xs">
          <div className="font-semibold text-gray-600 dark:text-gray-300 mb-1">
            Data:
          </div>
          {Object.entries(typedData.data).map(([key, value]) => (
            <div
              key={key}
              className="text-gray-700 dark:text-gray-300 font-mono"
            >
              {key}: {JSON.stringify(value)}
            </div>
          ))}
        </div>
      )}

      {/* Entry/Exit Counts */}
      <div className="flex gap-4 mb-2 text-xs">
        <div className="flex items-center gap-1">
          <span className="text-gray-500 dark:text-gray-400">Entries:</span>
          <span className="font-mono font-bold text-green-600 dark:text-green-400">
            {typedData.entryCount}
          </span>
        </div>
        <div className="flex items-center gap-1">
          <span className="text-gray-500 dark:text-gray-400">Exits:</span>
          <span className="font-mono font-bold text-red-600 dark:text-red-400">
            {typedData.exitCount}
          </span>
        </div>
      </div>

      {/* Active Timeouts */}
      {typedData.activeTimeouts.length > 0 && (
        <div className="border-t border-gray-200 dark:border-gray-700 pt-2">
          <div className="text-xs font-semibold text-gray-600 dark:text-gray-300 mb-1">
            Active Timeouts:
          </div>
          {typedData.activeTimeouts.map((timeout, index) => (
            <div
              key={index}
              className={`
                text-xs mb-1 p-1 rounded flex justify-between items-center
                ${
                  timeout.type === 'StateTimeout'
                    ? 'bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-100'
                    : timeout.type === 'EventTimeout'
                      ? 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-100'
                      : 'bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-100'
                }
              `}
            >
              <span className="font-medium">{timeout.type}</span>
              <span className="font-mono">{timeout.remaining}ms</span>
            </div>
          ))}
        </div>
      )}

      {/* Output Handle */}
      <Handle
        type="source"
        position={Position.Bottom}
        className="w-3 h-3 !bg-gray-400"
      />
    </div>
  );
};

export default memo(StateNode);
