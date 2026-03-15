'use client';

import React, { memo } from 'react';
import { Handle, Position, NodeProps } from '@xyflow/react';
import { useTheme } from 'next-themes';
import { SupervisorNodeData } from '../flows/supervision-tree';

// Strategy colors
const STRATEGY_COLORS = {
  ONE_FOR_ONE: { bg: '#dbeafe', border: '#3b82f6', text: '#1e40af' },
  ONE_FOR_ALL: { bg: '#fce7f3', border: '#ec4899', text: '#9d174d' },
  REST_FOR_ONE: { bg: '#fef3c7', border: '#f59e0b', text: '#92400e' },
  SIMPLE_ONE_FOR_ONE: { bg: '#d1fae5', border: '#10b981', text: '#065f46' },
};

const DARK_STRATEGY_COLORS = {
  ONE_FOR_ONE: { bg: '#1e3a5f', border: '#3b82f6', text: '#93c5fd' },
  ONE_FOR_ALL: { bg: '#4a1d3d', border: '#ec4899', text: '#f9a8d4' },
  REST_FOR_ONE: { bg: '#423506', border: '#f59e0b', text: '#fcd34d' },
  SIMPLE_ONE_FOR_ONE: { bg: '#064e3b', border: '#10b981', text: '#6ee7b7' },
};

export const SupervisorNode = memo(
  ({ data, selected }: NodeProps) => {
    const { theme } = useTheme();
    const isDark = theme === 'dark';

    const colors = isDark ? DARK_STRATEGY_COLORS : STRATEGY_COLORS;
    const typedData = data as unknown as SupervisorNodeData;
    const strategyColor = colors[typedData.strategy];

    const workerCount = typedData.children.filter(c => c.childType === 'WORKER').length;
    const supervisorCount = typedData.children.filter(c => c.childType === 'SUPERVISOR').length;

    return (
      <div
        className={`supervisor-node ${selected ? 'selected' : ''}`}
        style={{
          padding: '16px',
          borderRadius: '12px',
          minWidth: '280px',
          background: isDark ? '#1e293b' : '#ffffff',
          border: `2px solid ${selected ? '#3b82f6' : strategyColor.border}`,
          boxShadow: selected
            ? '0 0 0 4px rgba(59, 130, 246, 0.2)'
            : isDark
            ? '0 4px 6px -1px rgba(0, 0, 0, 0.3)'
            : '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
          transition: 'all 0.2s ease',
          cursor: 'pointer',
        }}
      >
        <style jsx>{`
          .supervisor-node {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
          }

          .supervisor-node:hover {
            transform: translateY(-2px);
            box-shadow: ${isDark
              ? '0 8px 12px -1px rgba(0, 0, 0, 0.4)'
              : '0 8px 12px -1px rgba(0, 0, 0, 0.15)'};
          }

          .supervisor-node.selected {
            border-color: #3b82f6;
          }

          .supervisor-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin-bottom: 12px;
          }

          .supervisor-title {
            display: flex;
            align-items: center;
            gap: 8px;
            font-weight: 600;
            font-size: 14px;
            color: ${isDark ? '#e2e8f0' : '#1e293b'};
          }

          .supervisor-icon {
            width: 24px;
            height: 24px;
            border-radius: 6px;
            background: ${strategyColor.bg};
            display: flex;
            align-items: center;
            justify-content: center;
            color: ${strategyColor.text};
            font-size: 12px;
            font-weight: 700;
          }

          .expand-button {
            width: 24px;
            height: 24px;
            border-radius: 6px;
            border: 1px solid ${isDark ? '#475569' : '#cbd5e0'};
            background: ${isDark ? '#334155' : '#f1f5f9'};
            color: ${isDark ? '#e2e8f0' : '#475569'};
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            transition: all 0.2s;
            font-size: 12px;
          }

          .expand-button:hover {
            background: ${strategyColor.bg};
            color: ${strategyColor.text};
            border-color: ${strategyColor.border};
          }

          .strategy-badge {
            display: inline-block;
            padding: 4px 8px;
            border-radius: 6px;
            background: ${strategyColor.bg};
            color: ${strategyColor.text};
            font-size: 11px;
            font-weight: 600;
            margin-bottom: 12px;
          }

          .supervisor-stats {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 8px;
            margin-bottom: 12px;
          }

          .stat-item {
            display: flex;
            flex-direction: column;
            padding: 6px 8px;
            background: ${isDark ? '#334155' : '#f8fafc'};
            border-radius: 6px;
            font-size: 11px;
          }

          .stat-label {
            color: ${isDark ? '#94a3b8' : '#64748b'};
            margin-bottom: 2px;
          }

          .stat-value {
            font-weight: 600;
            color: ${isDark ? '#e2e8f0' : '#1e293b'};
          }

          .restart-stats {
            padding: 8px;
            background: ${isDark ? '#334155' : '#f8fafc'};
            border-radius: 6px;
            font-size: 11px;
          }

          .restart-info {
            display: flex;
            justify-content: space-between;
            color: ${isDark ? '#94a3b8' : '#64748b'};
            margin-bottom: 4px;
          }

          .restart-count {
            font-weight: 600;
            color: ${typedData.restartCount > 3 ? '#ef4444' : typedData.restartCount > 1 ? '#f59e0b' : '#10b981'};
          }

          .child-indicator {
            display: flex;
            gap: 4px;
            font-size: 10px;
            color: ${isDark ? '#94a3b8' : '#64748b'};
          }

          .child-badge {
            padding: 2px 6px;
            border-radius: 4px;
            background: ${isDark ? '#475569' : '#e2e8f0'};
          }

          .pulse {
            animation: pulse 1.5s ease-in-out infinite;
          }

          @keyframes pulse {
            0%,
            100% {
              opacity: 1;
            }
            50% {
              opacity: 0.5;
            }
          }
        `}</style>

        <Handle
          type="target"
          position={Position.Top}
          style={{
            background: strategyColor.border,
            width: 10,
            height: 10,
          }}
        />

        <div className="supervisor-header">
          <div className="supervisor-title">
            <div className="supervisor-icon">S</div>
            <span>{typedData.name}</span>
          </div>
          <button
            className="expand-button"
            onClick={(e) => {
              e.stopPropagation();
              typedData.onToggle?.();
            }}
            aria-label={typedData.isExpanded ? 'Collapse' : 'Expand'}
          >
            {typedData.isExpanded ? '−' : '+'}
          </button>
        </div>

        <div className="strategy-badge">{typedData.strategy}</div>

        <div className="supervisor-stats">
          <div className="stat-item">
            <span className="stat-label">Intensity</span>
            <span className="stat-value">{typedData.intensity}</span>
          </div>
          <div className="stat-item">
            <span className="stat-label">Period</span>
            <span className="stat-value">{typedData.period}ms</span>
          </div>
        </div>

        <div className="restart-stats">
          <div className="restart-info">
            <span>Restarts</span>
            <span className="restart-count">{typedData.restartCount}</span>
          </div>
          {typedData.lastRestart && (
            <div className="restart-info" style={{ fontSize: '10px' }}>
              <span>Last: {new Date(typedData.lastRestart).toLocaleTimeString()}</span>
            </div>
          )}
          <div className="child-indicator">
            <span className="child-badge">
              {supervisorCount} Supervisor{supervisorCount !== 1 ? 's' : ''}
            </span>
            <span className="child-badge">
              {workerCount} Worker{workerCount !== 1 ? 's' : ''}
            </span>
          </div>
        </div>

        <Handle
          type="source"
          position={Position.Bottom}
          style={{
            background: strategyColor.border,
            width: 10,
            height: 10,
          }}
        />
      </div>
    );
  }
);

SupervisorNode.displayName = 'SupervisorNode';
