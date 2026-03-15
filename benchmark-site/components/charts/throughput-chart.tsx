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
  LabelList,
} from 'recharts';
import { formatThroughput, formatNumber } from '@/lib/chart-utils';
import { commonChartProps, commonAxisProps, commonGridProps, commonTooltipProps, barVariants, getColor } from '@/lib/chart-themes';

export interface ThroughputData {
  name: string;
  disabled: number;
  enabled: number;
  withSubscribers?: number;
  target?: number;
}

interface ThroughputChartProps {
  data: ThroughputData[];
  title?: string;
  showTarget?: boolean;
  targetValue?: number;
  height?: number;
  className?: string;
}

export function ThroughputChart({
  data,
  title,
  showTarget = true,
  targetValue = 100000,
  height = 300,
  className = '',
}: ThroughputChartProps) {
  // Custom tooltip
  const CustomTooltip = ({ active, payload, label }: any) => {
    if (active && payload && payload.length) {
      return (
        <div className="bg-gray-900 text-white p-3 rounded-lg shadow-lg">
          <p className="font-semibold mb-2">{label}</p>
          {payload.map((entry: any, index: number) => (
            <p key={index} style={{ color: entry.color }}>
              {entry.name}: {formatThroughput(entry.value)}
            </p>
          ))}
        </div>
      );
    }
    return null;
  };

  // Custom label for bars
  const CustomLabel = ({ x, y, width, value }: any) => {
    return (
      <text
        x={x + width / 2}
        y={y - 5}
        textAnchor="middle"
        fill="#374151"
        fontSize={12}
        fontWeight="bold"
      >
        {formatNumber(value)}
      </text>
    );
  };

  const processedData = useMemo(() => {
    return data.map(item => ({
      ...item,
      target: targetValue,
    }));
  }, [data, targetValue]);

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
            tickFormatter={(value) => formatNumber(value)}
          />
          <Tooltip content={<CustomTooltip />} />
          <Legend
            wrapperStyle={{ paddingTop: '20px' }}
            iconType="circle"
          />
          {showTarget && (
            <ReferenceLine
              y={targetValue}
              stroke="#ef4444"
              strokeDasharray="5 5"
              label={{
                value: `Target: ${formatNumber(targetValue)}`,
                position: 'top',
                fill: '#ef4444',
                fontSize: 12
              }}
            />
          )}
          <Bar
            dataKey="disabled"
            fill={getColor(0)}
            name="Observability Disabled"
            {...barVariants.default}
          >
            <LabelList dataKey="disabled" content={<CustomLabel />} />
          </Bar>
          <Bar
            dataKey="enabled"
            fill={getColor(1)}
            name="Observability Enabled"
            {...barVariants.default}
          >
            <LabelList dataKey="enabled" content={<CustomLabel />} />
          </Bar>
          {data.some(d => d.withSubscribers !== undefined) && (
            <Bar
              dataKey="withSubscribers"
              fill={getColor(2)}
              name="With Active Subscribers"
              {...barVariants.default}
            >
              <LabelList dataKey="withSubscribers" content={<CustomLabel />} />
            </Bar>
          )}
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
