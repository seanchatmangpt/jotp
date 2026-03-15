'use client';

import React, { useState } from 'react';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import { formatNumber, formatMemory, formatThroughput } from '@/lib/chart-utils';
import { commonChartProps, commonAxisProps, commonGridProps, getColor } from '@/lib/chart-themes';

export interface CapacityData {
  load: number;
  throughput: number;
  cpu: number;
  memory: number;
}

interface CapacityChartProps {
  datasets: {
    name: string;
    data: CapacityData[];
    color?: string;
  }[];
  title?: string;
  height?: number;
  className?: string;
}

export function CapacityChart({
  datasets,
  title,
  height = 400,
  className = '',
}: CapacityChartProps) {
  const [activeMetric, setActiveMetric] = useState<string>('throughput');

  // Custom tooltip
  const CustomTooltip = ({ active, payload, label }: any) => {
    if (active && payload && payload.length) {
      return (
        <div className="bg-gray-900 text-white p-3 rounded-lg shadow-lg">
          <p className="font-semibold mb-2">Load: {label}</p>
          {payload.map((entry: any, index: number) => {
            let formattedValue;
            switch (entry.dataKey) {
              case 'throughput':
                formattedValue = formatThroughput(entry.value);
                break;
              case 'cpu':
                formattedValue = `${entry.value.toFixed(1)}%`;
                break;
              case 'memory':
                formattedValue = formatMemory(entry.value);
                break;
              default:
                formattedValue = formatNumber(entry.value);
            }
            return (
              <p key={index} style={{ color: entry.color }}>
                {entry.name} ({entry.dataKey}): {formattedValue}
              </p>
            );
          })}
        </div>
      );
    }
    return null;
  };

  const metrics = ['throughput', 'cpu', 'memory'] as const;

  return (
    <div className={className}>
      {title && (
        <h3 className="text-lg font-semibold mb-4 text-gray-800">{title}</h3>
      )}

      {/* Metric selector */}
      <div className="flex gap-2 mb-4">
        {metrics.map((metric) => (
          <button
            key={metric}
            onClick={() => setActiveMetric(metric)}
            className={`px-4 py-2 rounded-lg font-medium transition-colors ${
              activeMetric === metric
                ? 'bg-blue-600 text-white'
                : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
            }`}
          >
            {metric === 'throughput' ? 'Throughput' : metric === 'cpu' ? 'CPU' : 'Memory'}
          </button>
        ))}
      </div>

      <ResponsiveContainer width="100%" height={height}>
        <LineChart {...commonChartProps} data={datasets[0]?.data || []}>
          <CartesianGrid {...commonGridProps} />
          <XAxis
            dataKey="load"
            label={{ value: 'Concurrent Load', position: 'insideBottom', offset: -5 }}
            {...commonAxisProps}
          />
          <YAxis
            label={{
              value: activeMetric === 'throughput' ? 'Ops/sec' : activeMetric === 'cpu' ? 'CPU %' : 'Memory (bytes)',
              angle: -90,
              position: 'insideLeft'
            }}
            {...commonAxisProps}
            tickFormatter={(value) => {
              if (activeMetric === 'cpu') return value.toFixed(0);
              if (activeMetric === 'memory') return formatMemory(value);
              return formatNumber(value);
            }}
          />
          <Tooltip content={<CustomTooltip />} />
          <Legend
            wrapperStyle={{ paddingTop: '20px' }}
            iconType="circle"
          />
          {datasets.map((dataset, index) => (
            <Line
              key={dataset.name}
              type="monotone"
              dataKey={activeMetric}
              data={dataset.data}
              stroke={dataset.color || getColor(index)}
              strokeWidth={2}
              name={dataset.name}
              dot={{ r: 3 }}
              activeDot={{ r: 5 }}
              connectNulls={false}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
