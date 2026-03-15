'use client';

import React, { useMemo } from 'react';
import {
  RadarChart,
  PolarGrid,
  PolarAngleAxis,
  PolarRadiusAxis,
  Radar,
  Legend,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';
import { getCategoryColor } from '@/lib/chart-utils';
import { commonChartProps } from '@/lib/chart-themes';

export interface ComparisonData {
  metric: string;
  [key: string]: string | number;
}

interface ComparisonChartProps {
  data: ComparisonData[];
  systems: string[];
  title?: string;
  height?: number;
  className?: string;
}

export function ComparisonChart({
  data,
  systems,
  title,
  height = 400,
  className = '',
}: ComparisonChartProps) {
  // Filter out only the systems present in the data
  const availableSystems = useMemo(() => {
    if (data.length === 0) return systems;
    return systems.filter(system =>
      data.some(item => item.hasOwnProperty(system))
    );
  }, [data, systems]);

  // Custom tooltip
  const CustomTooltip = ({ active, payload }: any) => {
    if (active && payload && payload.length) {
      return (
        <div className="bg-gray-900 text-white p-3 rounded-lg shadow-lg">
          {payload.map((entry: any, index: number) => (
            <p key={index} style={{ color: entry.color }}>
              {entry.name}: {entry.value}/100
            </p>
          ))}
        </div>
      );
    }
    return null;
  };

  return (
    <div className={className}>
      {title && (
        <h3 className="text-lg font-semibold mb-4 text-gray-800">{title}</h3>
      )}
      <ResponsiveContainer width="100%" height={height}>
        <RadarChart {...commonChartProps} data={data}>
          <PolarGrid stroke="#e5e7eb" strokeDasharray="3 3" />
          <PolarAngleAxis
            dataKey="metric"
            tick={{ fill: '#374151', fontSize: 12 }}
          />
          <PolarRadiusAxis
            angle={90}
            domain={[0, 100]}
            tick={{ fill: '#6b7280', fontSize: 10 }}
            tickFormatter={(value) => value.toString()}
          />
          <Tooltip content={<CustomTooltip />} />
          <Legend
            wrapperStyle={{ paddingTop: '20px' }}
            iconType="circle"
          />
          {availableSystems.map((system, index) => (
            <Radar
              key={system}
              name={system}
              dataKey={system}
              stroke={getCategoryColor(index)}
              fill={getCategoryColor(index)}
              fillOpacity={0.3}
              strokeWidth={2}
            />
          ))}
        </RadarChart>
      </ResponsiveContainer>
    </div>
  );
}

/**
 * Helper function to generate comparison data for common systems
 */
export function generateSystemComparison(
  metrics: string[],
  systems: {
    name: string;
    values: number[];
  }[]
): ComparisonData[] {
  return metrics.map((metric, index) => {
    const dataPoint: ComparisonData = { metric };

    systems.forEach(system => {
      dataPoint[system.name] = system.values[index] || 0;
    });

    return dataPoint;
  });
}
