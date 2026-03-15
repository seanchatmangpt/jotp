'use client';

import React, { useState, useEffect } from 'react';

interface VirtualThreadNodeProps {
  activeThreads: number;
  carrierThreads: number;
  currentHandler: string | null;
  processingTime: number;
}

interface ThreadExecution {
  id: string;
  virtualThreadId: string;
  carrierThreadId: number;
  handler: string;
  startTime: number;
  processingTime: number;
  progress: number;
}

export function VirtualThreadNode({
  activeThreads,
  carrierThreads,
  currentHandler,
  processingTime
}: VirtualThreadNodeProps) {
  const [executions, setExecutions] = useState<ThreadExecution[]>([]);
  const [expanded, setExpanded] = useState(false);

  // Simulate thread executions
  useEffect(() => {
    if (currentHandler && activeThreads > 0) {
      const newExecution: ThreadExecution = {
        id: `exec-${Date.now()}`,
        virtualThreadId: `VT-${Math.floor(Math.random() * 10000)}`,
        carrierThreadId: Math.floor(Math.random() * carrierThreads),
        handler: currentHandler,
        startTime: Date.now(),
        processingTime,
        progress: 0
      };

      setExecutions(prev => [...prev, newExecution]);

      // Animate progress
      const interval = setInterval(() => {
        setExecutions(prev =>
          prev.map(exec => {
            if (exec.id === newExecution.id) {
              const elapsed = Date.now() - exec.startTime;
              const newProgress = Math.min((elapsed / exec.processingTime) * 100, 100);

              if (newProgress >= 100) {
                // Remove completed execution after delay
                setTimeout(() => {
                  setExecutions(prev => prev.filter(e => e.id !== exec.id));
                }, 500);
              }

              return { ...exec, progress: newProgress };
            }
            return exec;
          })
        );
      }, 50);

      return () => clearInterval(interval);
    }
  }, [currentHandler, activeThreads, processingTime, carrierThreads]);

  const hasActiveExecutions = executions.length > 0;
  const carrierUtilization = (activeThreads / carrierThreads) * 100;

  return (
    <div className="relative">
      {/* Main Virtual Thread Container */}
      <div
        className={`
          relative bg-slate-800 rounded-lg border-2 p-4 w-80
          transition-all duration-300 cursor-pointer
          ${hasActiveExecutions ? 'border-yellow-500 shadow-lg shadow-yellow-500/20' : 'border-slate-600'}
          hover:border-yellow-400
        `}
        onClick={() => setExpanded(!expanded)}
      >
        {/* Header */}
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <ThreadIcon isActive={hasActiveExecutions} />
            <h3 className="text-white font-semibold text-sm">Virtual Thread Pool</h3>
          </div>
          <div className={`
            px-2 py-1 rounded text-xs font-mono
            ${hasActiveExecutions ? 'bg-yellow-500/20 text-yellow-400 animate-pulse' : 'bg-slate-700 text-slate-400'}
          `}>
            {activeThreads} active
          </div>
        </div>

        {/* Carrier Thread Visualization */}
        <div className="mb-3">
          <div className="text-xs text-slate-400 mb-1">Carrier Threads (ForkJoinPool)</div>
          <div className="flex gap-1">
            {Array.from({ length: carrierThreads }, (_, i) => (
              <CarrierThreadSlot
                key={i}
                id={i}
                isActive={executions.some(e => e.carrierThreadId === i)}
                utilization={executions.filter(e => e.carrierThreadId === i).length}
              />
            ))}
          </div>
          <div className="text-xs text-slate-500 mt-1">
            Utilization: {carrierUtilization.toFixed(0)}%
          </div>
        </div>

        {/* Active Executions */}
        {hasActiveExecutions && (
          <div className="space-y-2">
            <div className="text-xs text-slate-400">Active Executions</div>
            {executions.slice(0, expanded ? undefined : 3).map(exec => (
              <ExecutionCard key={exec.id} execution={exec} />
            ))}
            {!expanded && executions.length > 3 && (
              <div className="text-xs text-slate-500 text-center">
                +{executions.length - 3} more
              </div>
            )}
          </div>
        )}

        {/* Metrics */}
        <div className="mt-3 pt-3 border-t border-slate-700 grid grid-cols-2 gap-2 text-xs">
          <MetricRow
            label="Carrier Threads"
            value={carrierThreads.toString()}
            isWarning={false}
          />
          <MetricRow
            label="Virtual Threads"
            value={activeThreads.toString()}
            isWarning={activeThreads > carrierThreads * 10}
          />
          <MetricRow
            label="Avg Processing"
            value={`${processingTime.toFixed(0)}ms`}
            isWarning={processingTime > 1000}
          />
          <MetricRow
            label="Expansion"
            value="unlimited"
            isWarning={false}
          />
        </div>

        {/* Virtual Thread Features */}
        <div className="mt-3 pt-3 border-t border-slate-700">
          <div className="text-xs text-slate-400 space-y-1">
            <div className="flex items-center gap-2">
              <FeatureIcon type="lightweight" />
              <span>Lightweight (1KB stack)</span>
            </div>
            <div className="flex items-center gap-2">
              <FeatureIcon type="mount" />
              <span>Mount on carrier</span>
            </div>
            <div className="flex items-center gap-2">
              <FeatureIcon type="unmount" />
              <span>Unmount when blocking</span>
            </div>
          </div>
        </div>
      </div>

      {/* Expand/Collapse Indicator */}
      <div className="absolute -bottom-1 -right-1">
        <div className={`
          w-4 h-4 bg-slate-700 rounded-full flex items-center justify-center
          ${expanded ? 'rotate-180' : ''}
        `}>
          <svg className="w-3 h-3 text-slate-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
          </svg>
        </div>
      </div>
    </div>
  );
}

interface CarrierThreadSlotProps {
  id: number;
  isActive: boolean;
  utilization: number;
}

function CarrierThreadSlot({ id, isActive, utilization }: CarrierThreadSlotProps) {
  return (
    <div className="flex-1">
      <div
        className={`
          h-8 rounded flex flex-col items-center justify-center text-xs transition-all duration-200
          ${isActive ? 'bg-yellow-500/30 border border-yellow-500' : 'bg-slate-700 border border-slate-600'}
        `}
      >
        <div className="text-slate-300">C{id}</div>
        {isActive && (
          <div className="text-yellow-400 font-semibold">{utilization}v</div>
        )}
      </div>
    </div>
  );
}

interface ExecutionCardProps {
  execution: ThreadExecution;
}

function ExecutionCard({ execution }: ExecutionCardProps) {
  return (
    <div className="bg-slate-900 rounded p-2 border border-slate-700">
      <div className="flex items-center justify-between mb-1">
        <div className="flex items-center gap-2">
          <div className="w-2 h-2 bg-yellow-500 rounded-full animate-pulse" />
          <span className="text-xs text-slate-300 font-mono">{execution.virtualThreadId}</span>
        </div>
        <span className="text-xs text-slate-500">C{execution.carrierThreadId}</span>
      </div>
      <div className="text-xs text-slate-400 mb-1">{execution.handler}</div>
      <div className="w-full bg-slate-700 rounded-full h-1.5">
        <div
          className="bg-yellow-500 h-1.5 rounded-full transition-all duration-100"
          style={{ width: `${execution.progress}%` }}
        />
      </div>
      <div className="text-xs text-slate-500 mt-1">
        {execution.progress.toFixed(0)}%
      </div>
    </div>
  );
}

interface MetricRowProps {
  label: string;
  value: string;
  isWarning: boolean;
}

function MetricRow({ label, value, isWarning }: MetricRowProps) {
  return (
    <div className={`
      flex justify-between px-2 py-1 rounded
      ${isWarning ? 'bg-yellow-500/10' : 'bg-slate-700/50'}
    `}>
      <span className="text-slate-400">{label}</span>
      <span className={isWarning ? 'text-yellow-400 font-semibold' : 'text-slate-300'}>
        {value}
      </span>
    </div>
  );
}

interface ThreadIconProps {
  isActive: boolean;
}

function ThreadIcon({ isActive }: ThreadIconProps) {
  return (
    <svg
      className={`w-5 h-5 ${isActive ? 'text-yellow-500 animate-spin' : 'text-slate-500'}`}
      fill="none"
      stroke="currentColor"
      viewBox="0 0 24 24"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth={2}
        d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
      />
    </svg>
  );
}

interface FeatureIconProps {
  type: 'lightweight' | 'mount' | 'unmount';
}

function FeatureIcon({ type }: FeatureIconProps) {
  const icons = {
    lightweight: (
      <svg className="w-3 h-3 text-green-400" fill="currentColor" viewBox="0 0 20 20">
        <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-11a1 1 0 10-2 0v2H7a1 1 0 100 2h2v2a1 1 0 102 0v-2h2a1 1 0 100-2h-2V7z" clipRule="evenodd" />
      </svg>
    ),
    mount: (
      <svg className="w-3 h-3 text-blue-400" fill="currentColor" viewBox="0 0 20 20">
        <path d="M2 10a8 8 0 018-8v8h8a8 8 0 11-16 0z" />
        <path d="M12 2.252A8.014 8.014 0 0117.748 8H12V2.252z" />
      </svg>
    ),
    unmount: (
      <svg className="w-3 h-3 text-purple-400" fill="currentColor" viewBox="0 0 20 20">
        <path fillRule="evenodd" d="M3 3a1 1 0 00-1 1v12a1 1 0 102 0V4a1 1 0 00-1-1zm10.293 9.293a1 1 0 001.414 1.414l3-3a1 1 0 000-1.414l-3-3a1 1 0 10-1.414 1.414L14.586 9H7a1 1 0 100 2h7.586l-1.293 1.293z" clipRule="evenodd" />
      </svg>
    )
  };

  return icons[type];
}
