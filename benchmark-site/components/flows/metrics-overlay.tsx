'use client';

import React, { useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { FlowMetrics } from '@/lib/state-machines/flow-machine';
import { useMeasure } from '@/lib/hooks/use-measure';

interface MetricsOverlayProps {
  nodeId: string;
  metrics: FlowMetrics | null;
  history: FlowMetrics[];
  position: { x: number; y: number };
  isVisible: boolean;
  onClose?: () => void;
}

interface SparklineProps {
  data: number[];
  color: string;
  height?: number;
}

const Sparkline: React.FC<SparklineProps> = ({ data, color, height = 40 }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || data.length < 2) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const width = canvas.width;
    const h = canvas.height;

    ctx.clearRect(0, 0, width, h);

    const max = Math.max(...data);
    const min = Math.min(...data);
    const range = max - min || 1;

    ctx.beginPath();
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    data.forEach((value, index) => {
      const x = (index / (data.length - 1)) * width;
      const y = h - ((value - min) / range) * h;

      if (index === 0) {
        ctx.moveTo(x, y);
      } else {
        ctx.lineTo(x, y);
      }
    });

    ctx.stroke();

    // Add gradient fill
    const gradient = ctx.createLinearGradient(0, 0, 0, h);
    gradient.addColorStop(0, color + '40');
    gradient.addColorStop(1, color + '00');

    ctx.lineTo(width, h);
    ctx.lineTo(0, h);
    ctx.closePath();
    ctx.fillStyle = gradient;
    ctx.fill();
  }, [data, color]);

  return (
    <canvas
      ref={canvasRef}
      width={100}
      height={height}
      className="w-full"
    />
  );
};

const MetricCard: React.FC<{
  label: string;
  value: string | number;
  unit?: string;
  trend?: number[];
  color: string;
}> = ({ label, value, unit, trend, color }) => {
  return (
    <div className="bg-gray-800/50 backdrop-blur-sm rounded-lg p-3 border border-gray-700/50">
      <div className="text-xs text-gray-400 mb-1">{label}</div>
      <div className="flex items-end justify-between gap-2">
        <div className="flex items-baseline gap-1">
          <span className="text-xl font-bold text-white">{value}</span>
          {unit && <span className="text-xs text-gray-500">{unit}</span>}
        </div>
        {trend && trend.length > 1 && (
          <div className="w-16 h-8">
            <Sparkline data={trend} color={color} height={32} />
          </div>
        )}
      </div>
    </div>
  );
};

export const MetricsOverlay: React.FC<MetricsOverlayProps> = ({
  nodeId,
  metrics,
  history,
  position,
  isVisible,
  onClose
}) => {
  const [ref, bounds] = useMeasure();

  const getTrend = (field: keyof FlowMetrics): number[] => {
    return history.slice(-20).map(m => {
      if (field === 'throughput') return m.throughput;
      if (field === 'errorRate') return m.errorRate;
      if (field === 'cpu') return m.cpu;
      return 0;
    });
  };

  const latencyTrend = history.slice(-20).map(m => m.latency.p95);

  if (!isVisible || !metrics) return null;

  return (
    <AnimatePresence>
      <motion.div
        ref={ref}
        initial={{ opacity: 0, scale: 0.95, y: -10 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.95, y: -10 }}
        transition={{ duration: 0.2 }}
        className="fixed z-50 w-80 bg-gray-900/95 backdrop-blur-md rounded-xl border border-gray-700/50 shadow-2xl overflow-hidden"
        style={{
          left: Math.min(position.x, window.innerWidth - 340),
          top: Math.min(position.y + 20, window.innerHeight - 400)
        }}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 bg-gradient-to-r from-blue-600/20 to-purple-600/20 border-b border-gray-700/50">
          <div>
            <h3 className="text-sm font-semibold text-white">Node Metrics</h3>
            <p className="text-xs text-gray-400">{nodeId}</p>
          </div>
          {onClose && (
            <button
              onClick={onClose}
              className="p-1 hover:bg-gray-700/50 rounded transition-colors"
            >
              <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          )}
        </div>

        {/* Metrics Grid */}
        <div className="p-4 space-y-3 max-h-96 overflow-y-auto">
          {/* Throughput */}
          <MetricCard
            label="Throughput"
            value={metrics.throughput.toLocaleString()}
            unit="ops/s"
            trend={getTrend('throughput')}
            color="#10b981"
          />

          {/* Latency */}
          <div className="bg-gray-800/50 backdrop-blur-sm rounded-lg p-3 border border-gray-700/50">
            <div className="text-xs text-gray-400 mb-2">Latency</div>
            <div className="grid grid-cols-3 gap-2">
              <div className="text-center">
                <div className="text-xs text-gray-500">p50</div>
                <div className="text-sm font-semibold text-white">{metrics.latency.p50.toFixed(2)}ms</div>
              </div>
              <div className="text-center">
                <div className="text-xs text-gray-500">p95</div>
                <div className="text-sm font-semibold text-yellow-400">{metrics.latency.p95.toFixed(2)}ms</div>
              </div>
              <div className="text-center">
                <div className="text-xs text-gray-500">p99</div>
                <div className="text-sm font-semibold text-red-400">{metrics.latency.p99.toFixed(2)}ms</div>
              </div>
            </div>
            {latencyTrend.length > 1 && (
              <div className="mt-2 h-8">
                <Sparkline data={latencyTrend} color="#fbbf24" height={32} />
              </div>
            )}
          </div>

          {/* Error Rate */}
          <MetricCard
            label="Error Rate"
            value={metrics.errorRate.toFixed(2)}
            unit="%"
            trend={getTrend('errorRate')}
            color="#ef4444"
          />

          {/* CPU */}
          <MetricCard
            label="CPU"
            value={metrics.cpu.toFixed(1)}
            unit="%"
            trend={getTrend('cpu')}
            color="#8b5cf6"
          />

          {/* Memory */}
          <div className="bg-gray-800/50 backdrop-blur-sm rounded-lg p-3 border border-gray-700/50">
            <div className="text-xs text-gray-400 mb-2">Memory</div>
            <div className="flex items-center justify-between">
              <div>
                <span className="text-lg font-bold text-white">
                  {(metrics.memory.used / 1024 / 1024).toFixed(1)}
                </span>
                <span className="text-xs text-gray-500 ml-1">MB</span>
              </div>
              <div className="text-xs text-gray-500">
                of {(metrics.memory.total / 1024 / 1024).toFixed(1)} MB
              </div>
            </div>
            <div className="mt-2 h-2 bg-gray-700 rounded-full overflow-hidden">
              <motion.div
                initial={{ width: 0 }}
                animate={{ width: `${(metrics.memory.used / metrics.memory.total) * 100}%` }}
                transition={{ duration: 0.5 }}
                className="h-full bg-gradient-to-r from-blue-500 to-purple-500"
              />
            </div>
          </div>

          {/* Timestamp */}
          <div className="text-xs text-gray-500 text-center pt-2 border-t border-gray-700/50">
            Updated {new Date(metrics.timestamp).toLocaleTimeString()}
          </div>
        </div>
      </motion.div>
    </AnimatePresence>
  );
};
