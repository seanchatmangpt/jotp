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
  Area,
  AreaChart,
} from 'recharts';
import { formatNumber, formatLatency, formatPercent } from '@/lib/chart-utils';
import { commonChartProps, commonAxisProps, commonGridProps, getColor, areaGradients } from '@/lib/chart-themes';

export interface TimeSeriesData {
  timestamp: string;
  [key: string]: string | number;
}

interface TimeseriesChartProps {
  data: TimeSeriesData[];
  metrics: string[];
  title?: string;
  chartType?: 'line' | 'area';
  height?: number;
  className?: string;
}

export function TimeseriesChart({
  data,
  metrics,
  title,
  chartType = 'line',
  height = 300,
  className = '',
}: TimeseriesChartProps) {
  const [selectedMetrics, setSelectedMetrics] = useState<string[]>(metrics);

  // Custom tooltip
  const CustomTooltip = ({ active, payload, label }: any) => {
    if (active && payload && payload.length) {
      const timestamp = new Date(label).toLocaleTimeString();
      return (
        <div className="bg-gray-900 text-white p-3 rounded-lg shadow-lg">
          <p className="font-semibold mb-2">{timestamp}</p>
          {payload.map((entry: any, index: number) => (
            <p key={index} style={{ color: entry.color }}>
              {entry.name}: {typeof entry.value === 'number' ? formatNumber(entry.value) : entry.value}
            </p>
          ))}
        </div>
      );
    }
    return null;
  };

  const toggleMetric = (metric: string) => {
    setSelectedMetrics(prev =>
      prev.includes(metric)
        ? prev.filter(m => m !== metric)
        : [...prev, metric]
    );
  };

  const ChartComponent: any = chartType === 'area' ? AreaChart : LineChart;

  return (
    <div className={className}>
      {title && (
        <h3 className="text-lg font-semibold mb-4 text-gray-800">{title}</h3>
      )}

      {/* Metric selector */}
      <div className="flex flex-wrap gap-2 mb-4">
        {metrics.map((metric) => (
          <button
            key={metric}
            onClick={() => toggleMetric(metric)}
            className={`px-3 py-1 rounded-full text-sm font-medium transition-colors ${
              selectedMetrics.includes(metric)
                ? 'bg-blue-600 text-white'
                : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
            }`}
          >
            {metric}
          </button>
        ))}
      </div>

      <ResponsiveContainer width="100%" height={height}>
        <ChartComponent {...commonChartProps} data={data}>
          <CartesianGrid {...commonGridProps} />
          <XAxis
            dataKey="timestamp"
            {...commonAxisProps}
            tickFormatter={(value) => new Date(value).toLocaleTimeString()}
            tick={{ fill: '#374151', fontSize: 11 }}
          />
          <YAxis
            {...commonAxisProps}
            tickFormatter={(value) => formatNumber(value)}
          />
          <Tooltip content={<CustomTooltip />} />
          <Legend
            wrapperStyle={{ paddingTop: '20px' }}
            iconType="circle"
          />
          {metrics.map((metric, index) => {
            if (!selectedMetrics.includes(metric)) return null;

            const color = getColor(index);
            const Component: any = chartType === 'area' ? Area : Line;

            return (
              <Component
                key={metric}
                type="monotone"
                dataKey={metric}
                stroke={color}
                strokeWidth={2}
                name={metric}
                fill={chartType === 'area' ? color : undefined}
                fillOpacity={chartType === 'area' ? 0.3 : undefined}
                dot={chartType === 'line' ? { r: 3 } : false}
                activeDot={{ r: 5 }}
              />
            );
          })}
        </ChartComponent>
      </ResponsiveContainer>
    </div>
  );
}
