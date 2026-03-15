'use client';

import React, { useMemo } from 'react';
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
  ReferenceLine,
} from 'recharts';
import { formatLatency } from '@/lib/chart-utils';
import { commonChartProps, commonAxisProps, commonGridProps, getColor } from '@/lib/chart-themes';

export interface LatencyData {
  name: string;
  p50: number;
  p95: number;
  p99: number;
  p999: number;
  threshold?: number;
}

interface LatencyChartProps {
  data: LatencyData[];
  title?: string;
  showThreshold?: boolean;
  thresholdValue?: number;
  height?: number;
  className?: string;
}

export function LatencyChart({
  data,
  title,
  showThreshold = true,
  thresholdValue = 10,
  height = 300,
  className = '',
}: LatencyChartProps) {
  // Custom tooltip
  const CustomTooltip = ({ active, payload, label }: any) => {
    if (active && payload && payload.length) {
      return (
        <div className="bg-gray-900 text-white p-3 rounded-lg shadow-lg">
          <p className="font-semibold mb-2">{label}</p>
          {payload.map((entry: any, index: number) => (
            <p key={index} style={{ color: entry.color }}>
              {entry.name}: {formatLatency(entry.value)}
            </p>
          ))}
        </div>
      );
    }
    return null;
  };

  const processedData = useMemo(() => {
    return data.map(item => ({
      ...item,
      threshold: thresholdValue,
    }));
  }, [data, thresholdValue]);

  return (
    <div className={className}>
      {title && (
        <h3 className="text-lg font-semibold mb-4 text-gray-800">{title}</h3>
      )}
      <ResponsiveContainer width="100%" height={height}>
        <BarChart {...commonChartProps} data={processedData}>
          <CartesianGrid {...commonGridProps} />
          <XAxis
            dataKey="name"
            {...commonAxisProps}
            tick={{ fill: '#374151', fontSize: 12 }}
          />
          <YAxis
            {...commonAxisProps}
            tickFormatter={(value) => formatLatency(value)}
          />
          <Tooltip content={<CustomTooltip />} />
          <Legend
            wrapperStyle={{ paddingTop: '20px' }}
            iconType="circle"
          />
          {showThreshold && (
            <ReferenceLine
              y={thresholdValue}
              stroke="#ef4444"
              strokeDasharray="5 5"
              label={{
                value: `SLA Threshold: ${formatLatency(thresholdValue)}`,
                position: 'top',
                fill: '#ef4444',
                fontSize: 12
              }}
            />
          )}
          <Bar
            dataKey="p50"
            fill={getColor(0)}
            name="P50 (Median)"
            radius={[4, 4, 0, 0]}
          />
          <Bar
            dataKey="p95"
            fill={getColor(1)}
            name="P95"
            radius={[4, 4, 0, 0]}
          />
          <Bar
            dataKey="p99"
            fill={getColor(2)}
            name="P99"
            radius={[4, 4, 0, 0]}
          />
          <Bar
            dataKey="p999"
            fill={getColor(3)}
            name="P999"
            radius={[4, 4, 0, 0]}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
