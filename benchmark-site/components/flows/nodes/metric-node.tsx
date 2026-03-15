import React, { memo } from 'react';
import { Handle, Position, NodeProps } from '@xyflow/react';

interface MetricNodeData {
  label: string;
  value: number;
  unit: string;
  change?: number;
  threshold?: {
    warning: number;
    critical: number;
  };
  format?: 'number' | 'percentage' | 'bytes' | 'throughput';
}

export const MetricNode: React.FC<any> = memo(({ data }) => {
  const formatValue = (val: number): string => {
    switch (data.format) {
      case 'percentage':
        return `${val.toFixed(2)}%`;
      case 'bytes':
        if (val >= 1024 * 1024 * 1024) return `${(val / (1024 * 1024 * 1024)).toFixed(2)} GB`;
        if (val >= 1024 * 1024) return `${(val / (1024 * 1024)).toFixed(2)} MB`;
        if (val >= 1024) return `${(val / 1024).toFixed(2)} KB`;
        return `${val} B`;
      case 'throughput':
        return `${val.toFixed(2)} ops/s`;
      default:
        return val.toLocaleString();
    }
  };

  const getStatusColor = () => {
    if (!data.threshold) return 'bg-white border-gray-200';
    if (data.value >= data.threshold.critical) return 'bg-red-50 border-red-500';
    if (data.value >= data.threshold.warning) return 'bg-yellow-50 border-yellow-500';
    return 'bg-green-50 border-green-500';
  };

  const getChangeColor = () => {
    if (!data.change) return 'text-gray-500';
    return data.change > 0 ? 'text-red-600' : 'text-green-600';
  };

  const getChangeIcon = () => {
    if (!data.change) return '';
    return data.change > 0 ? '↑' : '↓';
  };

  return (
    <div className={`px-4 py-3 rounded-lg border-2 shadow-sm min-w-[180px] ${getStatusColor()}`}>
      <Handle type="target" position={Position.Top} className="w-3 h-3" />

      <div className="text-center">
        <p className="text-xs text-gray-600 mb-1">{data.label}</p>

        <div className="text-2xl font-bold mb-1">
          {formatValue(data.value)}
        </div>

        <div className="text-xs text-gray-500 mb-2">{data.unit}</div>

        {data.change !== undefined && (
          <div className={`text-xs font-semibold ${getChangeColor()}`}>
            {getChangeIcon()} {Math.abs(data.change).toFixed(2)}%
          </div>
        )}
      </div>

      <Handle type="source" position={Position.Bottom} className="w-3 h-3" />
    </div>
  );
});

MetricNode.displayName = 'MetricNode';
