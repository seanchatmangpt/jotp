'use client';

import React from 'react';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Area,
  AreaChart,
} from 'recharts';
import { commonChartProps } from '@/lib/chart-themes';

export interface SparklineData {
  timestamp: string;
  value: number;
}

interface SparklineProps {
  data: SparklineData[];
  type?: 'line' | 'area';
  color?: string;
  width?: number;
  height?: number;
  showTooltip?: boolean;
  showDots?: boolean;
  strokeWidth?: number;
  className?: string;
  valueFormatter?: (value: number) => string;
}

export function Sparkline({
  data,
  type = 'line',
  color = '#3b82f6',
  width = 100,
  height = 40,
  showTooltip = false,
  showDots = false,
  strokeWidth = 2,
  className = '',
  valueFormatter,
}: SparklineProps) {
  // Custom tooltip (minimal)
  const CustomTooltip = ({ active, payload }: any) => {
    if (active && payload && payload.length) {
      const value = payload[0].value;
      const formattedValue = valueFormatter ? valueFormatter(value) : value;
      return (
        <div className="bg-gray-900 text-white px-2 py-1 rounded text-xs">
          {formattedValue}
        </div>
      );
    }
    return null;
  };

  const ChartComponent = type === 'area' ? AreaChart : LineChart;

  return (
    <div className={className}>
      <ResponsiveContainer width={width} height={height}>
        <ChartComponent
          {...commonChartProps}
          data={data}
          margin={{ top: 0, right: 0, bottom: 0, left: 0 }}
        >
          {!showTooltip && <CartesianGrid stroke="none" />}
          <XAxis hide />
          <YAxis hide />
          {showTooltip && <Tooltip content={<CustomTooltip />} />}
          {type === 'area' ? (
            <Area
              type="monotone"
              dataKey="value"
              stroke={color}
              fill={color}
              fillOpacity={0.3}
              strokeWidth={strokeWidth}
            />
          ) : (
            <Line
              type="monotone"
              dataKey="value"
              stroke={color}
              strokeWidth={strokeWidth}
              dot={showDots ? { r: 2 } : false}
              activeDot={false}
            />
          )}
        </ChartComponent>
      </ResponsiveContainer>
    </div>
  );
}

/**
 * Mini sparkline for displaying trend with current value
 */
interface SparklineWithLabelProps extends SparklineProps {
  label?: string;
  currentValue?: number;
  showChange?: boolean;
  changePercent?: number;
}

export function SparklineWithLabel({
  label,
  currentValue,
  showChange,
  changePercent,
  ...sparklineProps
}: SparklineWithLabelProps) {
  return (
    <div className="flex items-center gap-3">
      <div className="flex-1">
        {label && <p className="text-xs text-gray-500 mb-1">{label}</p>}
        <div className="flex items-baseline gap-2">
          {currentValue !== undefined && (
            <span className="text-2xl font-semibold text-gray-900">
              {sparklineProps.valueFormatter
                ? sparklineProps.valueFormatter(currentValue)
                : currentValue}
            </span>
          )}
          {showChange && changePercent !== undefined && (
            <span
              className={`text-sm font-medium ${
                changePercent >= 0 ? 'text-green-600' : 'text-red-600'
              }`}
            >
              {changePercent >= 0 ? '+' : ''}
              {changePercent.toFixed(1)}%
            </span>
          )}
        </div>
      </div>
      <Sparkline {...sparklineProps} />
    </div>
  );
}

/**
 * Sparkline card component for use in metric cards
 */
interface SparklineCardProps {
  title: string;
  value: string | number;
  data: SparklineData[];
  change?: number;
  type?: 'line' | 'area';
  color?: string;
  className?: string;
}

export function SparklineCard({
  title,
  value,
  data,
  change,
  type = 'line',
  color = '#3b82f6',
  className = '',
}: SparklineCardProps) {
  return (
    <div className={`bg-white rounded-lg shadow p-4 ${className}`}>
      <div className="flex justify-between items-start mb-2">
        <h4 className="text-sm font-medium text-gray-600">{title}</h4>
        {change !== undefined && (
          <span
            className={`text-xs font-medium ${
              change >= 0 ? 'text-green-600' : 'text-red-600'
            }`}
          >
            {change >= 0 ? '↑' : '↓'} {Math.abs(change).toFixed(1)}%
          </span>
        )}
      </div>
      <div className="text-2xl font-bold text-gray-900 mb-3">{value}</div>
      <Sparkline
        data={data}
        type={type}
        color={color}
        height={40}
        showTooltip={false}
      />
    </div>
  );
}
