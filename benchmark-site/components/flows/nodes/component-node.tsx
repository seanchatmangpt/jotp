import React, { memo } from 'react';
import { Handle, Position, NodeProps } from '@xyflow/react';

interface ComponentNodeData {
  label: string;
  type?: 'proc' | 'supervisor' | 'state-machine' | 'event-bus' | 'registry' | 'monitor' | string;
  category?: string;
  state?: string;
  processes?: number;
  children?: string[];
  status?: 'active' | 'idle' | 'terminated' | 'restarting';
}

export const ComponentNode: React.FC<any> = memo(({ data }) => {
  const getComponentColor = () => {
    // First try type, then category, then default
    const key = data.type || data.category;

    switch (key) {
      case 'proc':
      case 'core':
        return 'bg-blue-50 border-blue-500';
      case 'supervisor':
      case 'lifecycle':
        return 'bg-purple-50 border-purple-500';
      case 'state-machine':
      case 'enterprise':
        return 'bg-green-50 border-green-500';
      case 'event-bus':
      case 'messaging':
        return 'bg-orange-50 border-orange-500';
      case 'registry':
        return 'bg-yellow-50 border-yellow-500';
      case 'monitor':
      case 'observability':
        return 'bg-pink-50 border-pink-500';
      default:
        // If we have a color from data, use it
        if (data.color) {
          return `border-[${data.color}] bg-white`;
        }
        return 'bg-white border-gray-200';
    }
  };

  const getComponentIcon = () => {
    const key = data.type || data.category;

    switch (key) {
      case 'proc':
      case 'core':
        return '⚡';
      case 'supervisor':
      case 'lifecycle':
        return '🛡️';
      case 'state-machine':
      case 'enterprise':
        return '🔄';
      case 'event-bus':
      case 'messaging':
        return '📡';
      case 'registry':
        return '📋';
      case 'monitor':
      case 'observability':
        return '👁️';
      default:
        return '📦';
    }
  };

  const getStatusBadge = () => {
    if (!data.status) return null;

    const statusColors = {
      active: 'bg-green-500',
      idle: 'bg-gray-400',
      terminated: 'bg-red-500',
      restarting: 'bg-yellow-500 animate-pulse'
    };

    return (
      <div className={`absolute -top-2 -right-2 w-4 h-4 rounded-full ${statusColors[data.status as keyof typeof statusColors]} border-2 border-white`} />
    );
  };

  return (
    <div className={`relative px-4 py-3 rounded-lg border-2 shadow-sm min-w-[200px] ${getComponentColor()}`}>
      <Handle type="target" position={Position.Top} className="w-3 h-3" />
      {getStatusBadge()}

      <div className="flex items-center gap-2 mb-2">
        <span className="text-2xl">{getComponentIcon()}</span>
        <div>
          <h3 className="font-bold text-sm">{data.label}</h3>
          <p className="text-xs text-gray-600 capitalize">{data.type ? data.type.replace(/-/g, ' ') : data.category || 'component'}</p>
        </div>
      </div>

      {data.state && (
        <div className="text-xs bg-white/50 px-2 py-1 rounded mb-2">
          <span className="text-gray-600">State:</span> <span className="font-mono font-semibold">{data.state}</span>
        </div>
      )}

      {data.processes !== undefined && (
        <div className="flex items-center gap-2 text-xs mb-2">
          <span className="text-gray-600">Processes:</span>
          <span className="font-mono font-semibold bg-white/50 px-2 py-1 rounded-full">
            {data.processes}
          </span>
        </div>
      )}

      {data.children && data.children.length > 0 && (
        <div className="mt-2 pt-2 border-t border-current/20">
          <p className="text-xs text-gray-600 mb-1">Children:</p>
          <div className="flex flex-wrap gap-1">
            {data.children.slice(0, 3).map((child: string, i: number) => (
              <span key={i} className="text-xs bg-white/50 px-2 py-0.5 rounded">
                {child}
              </span>
            ))}
            {data.children.length > 3 && (
              <span className="text-xs text-gray-500">+{data.children.length - 3} more</span>
            )}
          </div>
        </div>
      )}

      <Handle type="source" position={Position.Bottom} className="w-3 h-3" />
    </div>
  );
});

ComponentNode.displayName = 'ComponentNode';
