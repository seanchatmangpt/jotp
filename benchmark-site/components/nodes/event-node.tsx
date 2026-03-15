'use client';

import React, { useState } from 'react';
import { Handle, Position, NodeProps } from '@xyflow/react';

interface EventNodeData {
  eventType:
    | 'User'
    | 'StateTimeout'
    | 'EventTimeout'
    | 'GenericTimeout'
    | 'Internal'
    | 'Enter';
  payload?: Record<string, unknown>;
  onEventTrigger: (eventType: string, payload?: Record<string, unknown>) => void;
}

const EventNode: React.FC<NodeProps> = ({ data, selected }) => {
  const [isAnimating, setIsAnimating] = useState(false);
  const [showPayload, setShowPayload] = useState(false);

  const typedData = data as unknown as EventNodeData;

  const handleClick = () => {
    setIsAnimating(true);
    typedData.onEventTrigger(typedData.eventType, typedData.payload);

    setTimeout(() => setIsAnimating(false), 300);
  };

  const getEventColor = () => {
    switch (typedData.eventType) {
      case 'User':
        return 'from-blue-500 to-blue-600';
      case 'StateTimeout':
        return 'from-purple-500 to-purple-600';
      case 'EventTimeout':
        return 'from-yellow-500 to-yellow-600';
      case 'GenericTimeout':
        return 'from-orange-500 to-orange-600';
      case 'Internal':
        return 'from-green-500 to-green-600';
      case 'Enter':
        return 'from-teal-500 to-teal-600';
      default:
        return 'from-gray-500 to-gray-600';
    }
  };

  const getEventIcon = () => {
    switch (typedData.eventType) {
      case 'User':
        return '👤';
      case 'StateTimeout':
        return '⏱️';
      case 'EventTimeout':
        return '⏰';
      case 'GenericTimeout':
        return '🕐';
      case 'Internal':
        return '🔄';
      case 'Enter':
        return '📥';
      default:
        return '❓';
    }
  };

  return (
    <div className="relative">
      {/* Input Handle */}
      <Handle
        type="target"
        position={Position.Top}
        className="w-3 h-3 !bg-gray-400"
      />

      {/* Event Button */}
      <button
        onClick={handleClick}
        className={`
          relative px-4 py-3 rounded-lg shadow-lg min-w-[180px]
          bg-gradient-to-br ${getEventColor()}
          text-white font-semibold
          transform transition-all duration-300
          ${
            isAnimating
              ? 'scale-105 shadow-2xl'
              : 'hover:scale-102 hover:shadow-xl'
          }
          ${selected ? 'ring-2 ring-purple-500 ring-offset-2' : ''}
          active:scale-95
        `}
      >
        {/* Ripple Effect */}
        {isAnimating && (
          <div
            className={`
              absolute inset-0 rounded-lg animate-ping
              bg-white opacity-30
            `}
          />
        )}

        {/* Event Header */}
        <div className="flex items-center justify-between mb-1">
          <span className="text-lg">{getEventIcon()}</span>
          <span className="text-xs uppercase tracking-wide opacity-80">
            {typedData.eventType}
          </span>
        </div>

        {/* Event Type Label */}
        <div className="text-sm font-medium">{typedData.eventType} Event</div>

        {/* Payload Indicator */}
        {typedData.payload && Object.keys(typedData.payload).length > 0 && (
          <div className="mt-2">
            <button
              onClick={(e) => {
                e.stopPropagation();
                setShowPayload(!showPayload);
              }}
              className="
                text-xs bg-white/20 hover:bg-white/30
                px-2 py-1 rounded transition-colors
              "
            >
              {showPayload ? 'Hide Payload' : 'Show Payload'}
            </button>
          </div>
        )}

        {/* Payload Display */}
        {showPayload && typedData.payload && (
          <div className="mt-2 p-2 bg-black/20 rounded text-xs font-mono">
            {Object.entries(typedData.payload).map(([key, value]) => (
              <div key={key} className="flex justify-between">
                <span className="opacity-80">{key}:</span>
                <span>{JSON.stringify(value)}</span>
              </div>
            ))}
          </div>
        )}

        {/* Click Feedback */}
        <div
          className={`
            absolute -top-1 -right-1 w-4 h-4
            bg-yellow-400 rounded-full
            text-xs font-bold text-gray-900
            transition-opacity duration-200
            ${isAnimating ? 'opacity-100' : 'opacity-0'}
          `}
        >
          !
        </div>
      </button>

      {/* Output Handle */}
      <Handle
        type="source"
        position={Position.Bottom}
        className="w-3 h-3 !bg-gray-400"
      />

      {/* Event Priority Badge */}
      <div
        className={`
          absolute -left-2 top-1/2 -translate-y-1/2
          px-1 py-0.5 rounded text-xs font-bold
          ${
            typedData.eventType === 'Internal' || typedData.eventType === 'Enter'
              ? 'bg-green-500 text-white'
              : 'bg-gray-300 text-gray-700'
          }
        `}
      >
        {typedData.eventType === 'Internal' || typedData.eventType === 'Enter'
          ? 'HIGH'
          : 'NORMAL'}
      </div>
    </div>
  );
};

export default EventNode;
