import React, { memo } from 'react';
import { Handle, Position, NodeProps } from '@xyflow/react';

interface BenchmarkNodeData {
  label: string;
  status?: 'pending' | 'running' | 'completed' | 'failed';
  duration?: number;
  description?: string;
  metrics?: {
    throughput?: number;
    latency?: number;
    errorRate?: number;
  };
}

export const BenchmarkNode: React.FC<any> = memo(({ data }) => {
  const getStatusColor = () => {
    switch (data.status) {
      case 'pending': return 'bg-gray-100 border-gray-300';
      case 'running': return 'bg-blue-50 border-blue-500 animate-pulse';
      case 'completed': return 'bg-green-50 border-green-500';
      case 'failed': return 'bg-red-50 border-red-500';
      default: return 'bg-white border-gray-200';
    }
  };

  const getStatusIcon = () => {
    switch (data.status) {
      case 'pending': return '⏳';
      case 'running': return '▶️';
      case 'completed': return '✅';
      case 'failed': return '❌';
      default: return '📊';
    }
  };

  return (
    <div className={`px-4 py-3 rounded-lg border-2 shadow-sm min-w-[200px] ${getStatusColor()}`}>
      <Handle type="target" position={Position.Top} className="w-3 h-3" />

      <div className="flex items-center gap-2 mb-2">
        <span className="text-lg">{getStatusIcon()}</span>
        <h3 className="font-semibold text-sm">{data.label}</h3>
      </div>

      {data.description && (
        <p className="text-xs text-gray-600 mb-2">{data.description}</p>
      )}

      {data.duration && (
        <div className="text-xs font-mono bg-white/50 px-2 py-1 rounded">
          ⏱️ {data.duration}ms
        </div>
      )}

      {data.metrics && (
        <div className="mt-2 space-y-1 text-xs">
          {data.metrics.throughput && (
            <div className="flex justify-between">
              <span className="text-gray-600">Throughput:</span>
              <span className="font-mono font-semibold">{data.metrics.throughput.toFixed(2)} ops/s</span>
            </div>
          )}
          {data.metrics.latency && (
            <div className="flex justify-between">
              <span className="text-gray-600">Latency:</span>
              <span className="font-mono font-semibold">{data.metrics.latency.toFixed(2)}ms</span>
            </div>
          )}
          {data.metrics.errorRate !== undefined && (
            <div className="flex justify-between">
              <span className="text-gray-600">Errors:</span>
              <span className="font-mono font-semibold">{(data.metrics.errorRate * 100).toFixed(2)}%</span>
            </div>
          )}
        </div>
      )}

      <Handle type="source" position={Position.Bottom} className="w-3 h-3" />
    </div>
  );
});

BenchmarkNode.displayName = 'BenchmarkNode';
