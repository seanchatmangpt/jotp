'use client';

import React, { useMemo } from 'react';

interface MailboxNodeProps {
  queueDepth: number;
  capacity: number;
  utilization: number;
}

export function MailboxNode({ queueDepth, capacity, utilization }: MailboxNodeProps) {
  const isUnderPressure = utilization > 80;
  const isHealthy = utilization < 50;

  const queueVisualization = useMemo(() => {
    const slots = Math.min(20, Math.ceil(capacity / 5)); // Show max 20 slots
    const filledSlots = Math.ceil((queueDepth / capacity) * slots);

    return Array.from({ length: slots }, (_, i) => ({
      id: i,
      filled: i < filledSlots,
      isHead: i === 0 && filledSlots > 0,
      isTail: i === filledSlots - 1 && filledSlots > 0
    }));
  }, [queueDepth, capacity]);

  return (
    <div className="relative">
      {/* Main Mailbox Container */}
      <div className={`
        relative bg-slate-800 rounded-lg border-2 p-4 w-64
        transition-all duration-300
        ${isUnderPressure ? 'border-red-500 shadow-lg shadow-red-500/20' : ''}
        ${isHealthy ? 'border-green-500/50' : 'border-blue-500/50'}
      `}>
        {/* Header */}
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <MailboxIcon isUnderPressure={isUnderPressure} />
            <h3 className="text-white font-semibold text-sm">LinkedTransferQueue</h3>
          </div>
          <div className={`
            px-2 py-1 rounded text-xs font-mono
            ${isUnderPressure ? 'bg-red-500/20 text-red-400' : ''}
            ${isHealthy ? 'bg-green-500/20 text-green-400' : 'bg-blue-500/20 text-blue-400'}
          `}>
            {queueDepth}/{capacity}
          </div>
        </div>

        {/* Queue Visualization */}
        <div className="mb-3">
          <div className="flex items-end justify-center gap-0.5 h-16 bg-slate-900 rounded p-2">
            {queueVisualization.map(slot => (
              <QueueSlot
                key={slot.id}
                filled={slot.filled}
                isHead={slot.isHead}
                isTail={slot.isTail}
                isUnderPressure={isUnderPressure}
              />
            ))}
          </div>
          <div className="flex justify-between text-xs text-slate-500 mt-1">
            <span>Head →</span>
            <span>← Tail</span>
          </div>
        </div>

        {/* Metrics */}
        <div className="grid grid-cols-2 gap-2 text-xs">
          <MetricRow
            label="Utilization"
            value={`${utilization.toFixed(1)}%`}
            isWarning={utilization > 80}
          />
          <MetricRow
            label="Free Slots"
            value={(capacity - queueDepth).toString()}
            isWarning={capacity - queueDepth < 10}
          />
          <MetricRow
            label="Transfer Mode"
            value="Linked"
            isWarning={false}
          />
          <MetricRow
            label="Algorithm"
            value="FIFO"
            isWarning={false}
          />
        </div>

        {/* LinkedTransferQueue-specific features */}
        <div className="mt-3 pt-3 border-t border-slate-700">
          <div className="text-xs text-slate-400 space-y-1">
            <div className="flex items-center gap-2">
              <FeatureIcon type="wait-free" />
              <span>Wait-free transfer</span>
            </div>
            <div className="flex items-center gap-2">
              <FeatureIcon type="lock-free" />
              <span>Lock-free operations</span>
            </div>
            <div className="flex items-center gap-2">
              <FeatureIcon type="bounded" />
              <span>Bounded queue depth</span>
            </div>
          </div>
        </div>

        {/* Backpressure Indicator */}
        {isUnderPressure && (
          <div className="absolute -top-2 -right-2">
            <div className="relative">
              <div className="w-6 h-6 bg-red-500 rounded-full animate-ping" />
              <div className="absolute top-0 left-0 w-6 h-6 bg-red-500 rounded-full flex items-center justify-center">
                <span className="text-white text-xs">!</span>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Performance Indicator */}
      <div className="mt-2 text-center">
        <PerformanceIndicator utilization={utilization} />
      </div>
    </div>
  );
}

interface QueueSlotProps {
  filled: boolean;
  isHead: boolean;
  isTail: boolean;
  isUnderPressure: boolean;
}

function QueueSlot({ filled, isHead, isTail, isUnderPressure }: QueueSlotProps) {
  return (
    <div className={`
      w-2 rounded-sm transition-all duration-200
      ${filled ? (isUnderPressure ? 'bg-red-500' : 'bg-blue-500') : 'bg-slate-700'}
      ${isHead ? 'ring-2 ring-green-400' : ''}
      ${isTail ? 'ring-2 ring-yellow-400' : ''}
    `} />
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
      ${isWarning ? 'bg-red-500/10' : 'bg-slate-700/50'}
    `}>
      <span className="text-slate-400">{label}</span>
      <span className={isWarning ? 'text-red-400 font-semibold' : 'text-slate-300'}>
        {value}
      </span>
    </div>
  );
}

interface PerformanceIndicatorProps {
  utilization: number;
}

function PerformanceIndicator({ utilization }: PerformanceIndicatorProps) {
  const getStatus = () => {
    if (utilization < 30) return { text: 'Underutilized', color: 'text-blue-400' };
    if (utilization < 60) return { text: 'Optimal', color: 'text-green-400' };
    if (utilization < 80) return { text: 'Elevated', color: 'text-yellow-400' };
    return { text: 'Backpressure', color: 'text-red-400' };
  };

  const status = getStatus();

  return (
    <div className={`text-xs font-semibold ${status.color}`}>
      {status.text}
    </div>
  );
}

interface MailboxIconProps {
  isUnderPressure: boolean;
}

function MailboxIcon({ isUnderPressure }: MailboxIconProps) {
  return (
    <svg
      className={`w-5 h-5 ${isUnderPressure ? 'text-red-500 animate-pulse' : 'text-blue-500'}`}
      fill="none"
      stroke="currentColor"
      viewBox="0 0 24 24"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth={2}
        d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4"
      />
    </svg>
  );
}

interface FeatureIconProps {
  type: 'wait-free' | 'lock-free' | 'bounded';
}

function FeatureIcon({ type }: FeatureIconProps) {
  const icons = {
    'wait-free': (
      <svg className="w-3 h-3 text-green-400" fill="currentColor" viewBox="0 0 20 20">
        <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
      </svg>
    ),
    'lock-free': (
      <svg className="w-3 h-3 text-blue-400" fill="currentColor" viewBox="0 0 20 20">
        <path fillRule="evenodd" d="M2.166 4.999A11.954 11.954 0 0010 1.944 11.954 11.954 0 0017.834 5c.11.65.166 1.32.166 2.001 0 5.225-3.34 9.67-8 11.317C5.34 16.67 2 12.225 2 7c0-.682.057-1.35.166-2.001zm11.541 3.708a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
      </svg>
    ),
    'bounded': (
      <svg className="w-3 h-3 text-purple-400" fill="currentColor" viewBox="0 0 20 20">
        <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM16 13a1 1 0 100-2 1 1 0 000 2z" />
      </svg>
    )
  };

  return icons[type];
}
