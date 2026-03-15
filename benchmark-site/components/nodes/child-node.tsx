'use client';

import React, { memo } from 'react';
import { Handle, Position, NodeProps } from '@xyflow/react';
import { useTheme } from 'next-themes';
import { RestartType, ChildState } from '../flows/supervision-tree';

interface ChildNodeData {
  id: string;
  name: string;
  restartType: RestartType;
  state: ChildState;
  childType: 'WORKER' | 'SUPERVISOR';
  supervisorId: string;
  supervisorStrategy: string;
  shutdown?: number;
  onCrash?: () => void;
}

// Restart type colors
const RESTART_TYPE_COLORS = {
  PERMANENT: {
    bg: '#fee2e2',
    border: '#ef4444',
    text: '#991b1b',
    description: 'Always restarted',
  },
  TRANSIENT: {
    bg: '#fef3c7',
    border: '#f59e0b',
    text: '#92400e',
    description: 'Restarted on abnormal exit',
  },
  TEMPORARY: {
    bg: '#d1fae5',
    border: '#10b981',
    text: '#065f46',
    description: 'Never restarted',
  },
};

const DARK_RESTART_TYPE_COLORS = {
  PERMANENT: {
    bg: '#450a0a',
    border: '#ef4444',
    text: '#fca5a5',
    description: 'Always restarted',
  },
  TRANSIENT: {
    bg: '#422006',
    border: '#f59e0b',
    text: '#fcd34d',
    description: 'Restarted on abnormal exit',
  },
  TEMPORARY: {
    bg: '#064e3b',
    border: '#10b981',
    text: '#6ee7b7',
    description: 'Never restarted',
  },
};

// State indicators
const STATE_CONFIGS = {
  running: {
    icon: '●',
    color: '#10b981',
    label: 'Running',
    pulse: false,
  },
  restarting: {
    icon: '⟳',
    color: '#f59e0b',
    label: 'Restarting',
    pulse: true,
  },
  stopped: {
    icon: '■',
    color: '#6b7280',
    label: 'Stopped',
    pulse: false,
  },
  crashed: {
    icon: '⚠',
    color: '#ef4444',
    label: 'Crashed',
    pulse: true,
  },
};

export const ChildNode = memo(({ data, selected }: NodeProps) => {
  const { theme } = useTheme();
  const isDark = theme === 'dark';

  const colors = isDark ? DARK_RESTART_TYPE_COLORS : RESTART_TYPE_COLORS;
  const typedData = data as unknown as ChildNodeData;
  const restartColor = colors[typedData.restartType];
  const stateConfig = STATE_CONFIGS[typedData.state];

  return (
    <div
      className={`child-node ${selected ? 'selected' : ''} ${typedData.state}`}
      style={{
        padding: '12px',
        borderRadius: '10px',
        minWidth: '200px',
        background: isDark ? '#1e293b' : '#ffffff',
        border: `2px solid ${selected ? '#3b82f6' : restartColor.border}`,
        boxShadow: selected
          ? '0 0 0 4px rgba(59, 130, 246, 0.2)'
          : isDark
          ? '0 4px 6px -1px rgba(0, 0, 0, 0.3)'
          : '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
        transition: 'all 0.2s ease',
        cursor: 'pointer',
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      <style jsx>{`
        .child-node {
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
        }

        .child-node:hover {
          transform: translateY(-2px);
          box-shadow: ${isDark
            ? '0 8px 12px -1px rgba(0, 0, 0, 0.4)'
            : '0 8px 12px -1px rgba(0, 0, 0, 0.15)'};
        }

        .child-node.selected {
          border-color: #3b82f6;
        }

        .child-node.restarting::before,
        .child-node.crashed::before {
          content: '';
          position: absolute;
          top: 0;
          left: 0;
          right: 0;
          bottom: 0;
          background: linear-gradient(
            90deg,
            transparent,
            ${typedData.state === 'restarting' ? 'rgba(245, 158, 11, 0.1)' : 'rgba(239, 68, 68, 0.1)'},
            transparent
          );
          animation: shimmer 2s infinite;
        }

        @keyframes shimmer {
          0% {
            transform: translateX(-100%);
          }
          100% {
            transform: translateX(100%);
          }
        }

        .child-header {
          display: flex;
          align-items: center;
          justify-content: space-between;
          margin-bottom: 10px;
        }

        .child-title {
          display: flex;
          align-items: center;
          gap: 8px;
          font-weight: 600;
          font-size: 13px;
          color: ${isDark ? '#e2e8f0' : '#1e293b'};
        }

        .child-icon {
          width: 20px;
          height: 20px;
          border-radius: 4px;
          background: ${restartColor.bg};
          color: ${restartColor.text};
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 10px;
          font-weight: 700;
        }

        .state-indicator {
          display: flex;
          align-items: center;
          gap: 4px;
          font-size: 12px;
          font-weight: 600;
          color: ${stateConfig.color};
        }

        .state-icon {
          ${stateConfig.pulse ? 'animation: pulse 1.5s ease-in-out infinite;' : ''}
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

        .restart-type-badge {
          display: inline-block;
          padding: 3px 8px;
          border-radius: 5px;
          background: ${restartColor.bg};
          color: ${restartColor.text};
          font-size: 10px;
          font-weight: 600;
          margin-bottom: 8px;
        }

        .child-details {
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: 6px;
          margin-bottom: 8px;
          font-size: 10px;
        }

        .detail-item {
          padding: 4px 6px;
          background: ${isDark ? '#334155' : '#f8fafc'};
          border-radius: 4px;
        }

        .detail-label {
          color: ${isDark ? '#94a3b8' : '#64748b'};
          margin-bottom: 1px;
        }

        .detail-value {
          font-weight: 600;
          color: ${isDark ? '#e2e8f0' : '#1e293b'};
        }

        .child-actions {
          display: flex;
          gap: 6px;
        }

        .crash-button {
          flex: 1;
          padding: 6px;
          border: 1px solid ${isDark ? '#475569' : '#cbd5e0'};
          background: ${isDark ? '#334155' : '#f1f5f9'};
          color: ${isDark ? '#e2e8f0' : '#475569'};
          border-radius: 5px;
          font-size: 10px;
          font-weight: 600;
          cursor: pointer;
          transition: all 0.2s;
        }

        .crash-button:hover {
          background: #fee2e2;
          color: #991b1b;
          border-color: #ef4444;
        }

        .crash-button:active {
          transform: scale(0.95);
        }

        .tooltip {
          position: absolute;
          bottom: 100%;
          left: 50%;
          transform: translateX(-50%);
          background: ${isDark ? '#1e293b' : '#ffffff'};
          border: 1px solid ${isDark ? '#475569' : '#e2e8f0'};
          padding: 8px;
          border-radius: 6px;
          font-size: 11px;
          color: ${isDark ? '#e2e8f0' : '#1e293b'};
          white-space: nowrap;
          opacity: 0;
          pointer-events: none;
          transition: opacity 0.2s;
          margin-bottom: 8px;
          box-shadow: ${isDark ? '0 4px 6px rgba(0, 0, 0, 0.3)' : '0 4px 6px rgba(0, 0, 0, 0.1)'};
        }

        .child-node:hover .tooltip {
          opacity: 1;
        }
      `}</style>

      <Handle
        type="target"
        position={Position.Top}
        style={{
          background: restartColor.border,
          width: 8,
          height: 8,
        }}
      />

      <div className="child-header">
        <div className="child-title">
          <div className="child-icon">{typedData.childType === 'WORKER' ? 'W' : 'S'}</div>
          <span>{typedData.name}</span>
        </div>
        <div className="state-indicator">
          <span className={`state-icon ${stateConfig.pulse ? 'pulse' : ''}`}>
            {stateConfig.icon}
          </span>
          <span>{stateConfig.label}</span>
        </div>
      </div>

      <div className="restart-type-badge">{typedData.restartType}</div>

      <div className="child-details">
        <div className="detail-item">
          <div className="detail-label">Type</div>
          <div className="detail-value">{typedData.childType}</div>
        </div>
        {typedData.shutdown && (
          <div className="detail-item">
            <div className="detail-label">Shutdown</div>
            <div className="detail-value">{typedData.shutdown}ms</div>
          </div>
        )}
      </div>

      <div className="child-actions">
        <button className="crash-button" onClick={typedData.onCrash}>
          Simulate Crash
        </button>
      </div>

      <div className="tooltip">
        <div>
          <strong>{typedData.name}</strong>
        </div>
        <div>{restartColor.description}</div>
        <div>Strategy: {typedData.supervisorStrategy}</div>
      </div>

      <Handle
        type="source"
        position={Position.Bottom}
        style={{
          background: restartColor.border,
          width: 8,
          height: 8,
        }}
      />
    </div>
  );
});

ChildNode.displayName = 'ChildNode';
