import React, { memo } from 'react';
import { Handle, Position, NodeProps } from '@xyflow/react';

interface TimingNodeData {
  label: string;
  startTime: number;
  endTime: number;
  duration: number;
  isHotPath?: boolean;
  children?: Array<{
    label: string;
    duration: number;
  }>;
}

export const TimingNode: React.FC<any> = memo(({ data }) => {
  const getPathColor = () => {
    return data.isHotPath
      ? 'bg-green-50 border-green-500'
      : 'bg-gray-50 border-gray-400';
  };

  const getTimingBar = () => {
    const totalDuration = data.duration;
    const maxChildDuration = Math.max(...(data.children?.map((c: any) => c.duration) || [0]));

    return (
      <div className="space-y-1 mt-2">
        {data.children?.map((child: any, i: number) => {
          const percentage = (child.duration / totalDuration) * 100;
          return (
            <div key={i} className="flex items-center gap-2">
              <span className="text-xs text-gray-600 w-24 truncate">{child.label}</span>
              <div className="flex-1 bg-gray-200 rounded-full h-4 overflow-hidden">
                <div
                  className="h-full bg-blue-500 flex items-center justify-end pr-1"
                  style={{ width: `${percentage}%` }}
                >
                  <span className="text-[10px] text-white font-semibold">
                    {child.duration.toFixed(2)}ms
                  </span>
                </div>
              </div>
            </div>
          );
        })}
      </div>
    );
  };

  return (
    <div className={`px-4 py-3 rounded-lg border-2 shadow-sm min-w-[300px] ${getPathColor()}`}>
      <Handle type="target" position={Position.Top} className="w-3 h-3" />

      <div className="flex items-center justify-between mb-2">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-lg">{data.isHotPath ? '🔥' : '⏱️'}</span>
            <h3 className="font-bold text-sm">{data.label}</h3>
          </div>
          {data.isHotPath && (
            <span className="text-xs bg-red-500 text-white px-2 py-0.5 rounded-full font-semibold">
              HOT PATH
            </span>
          )}
        </div>

        <div className="text-right">
          <div className="text-xl font-bold font-mono">{data.duration.toFixed(2)}ms</div>
        </div>
      </div>

      <div className="text-xs text-gray-600 mb-2 flex justify-between">
        <span>Start: {data.startTime.toFixed(2)}ms</span>
        <span>End: {data.endTime.toFixed(2)}ms</span>
      </div>

      {data.children && data.children.length > 0 && getTimingBar()}

      <Handle type="source" position={Position.Bottom} className="w-3 h-3" />
    </div>
  );
});

TimingNode.displayName = 'TimingNode';
