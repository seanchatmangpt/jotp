/**
 * Chart themes and styling configuration
 */

import { Colors } from 'recharts';

export interface ChartTheme {
  colors: Colors;
  backgroundColor: string;
  gridColor: string;
  textColor: string;
  tooltipBackgroundColor: string;
  tooltipTextColor: string;
  axisColor: string;
}

export const lightTheme: ChartTheme = {
  colors: {
    primary: '#3b82f6',
    secondary: '#10b981',
    tertiary: '#f59e0b',
    quaternary: '#ef4444',
    quinary: '#8b5cf6'
  },
  backgroundColor: '#ffffff',
  gridColor: '#e5e7eb',
  textColor: '#374151',
  tooltipBackgroundColor: '#1f2937',
  tooltipTextColor: '#f9fafb',
  axisColor: '#6b7280'
};

export const darkTheme: ChartTheme = {
  colors: {
    primary: '#60a5fa',
    secondary: '#34d399',
    tertiary: '#fbbf24',
    quaternary: '#f87171',
    quinary: '#a78bfa'
  },
  backgroundColor: '#111827',
  gridColor: '#374151',
  textColor: '#e5e7eb',
  tooltipBackgroundColor: '#1f2937',
  tooltipTextColor: '#f9fafb',
  axisColor: '#9ca3af'
};

export const chartColors = [
  '#3b82f6', // blue
  '#10b981', // emerald
  '#f59e0b', // amber
  '#ef4444', // red
  '#8b5cf6', // violet
  '#ec4899', // pink
  '#06b6d4', // cyan
  '#84cc16', // lime
  '#f97316', // orange
  '#6366f1', // indigo
];

export function getColor(index: number): string {
  return chartColors[index % chartColors.length];
}

/**
 * Common chart props
 */
export const commonChartProps = {
  margin: { top: 10, right: 10, left: 10, bottom: 10 },
};

export const commonAxisProps = {
  stroke: '#6b7280',
  strokeWidth: 1,
  tick: { fill: '#6b7280', fontSize: 12 },
  axisLine: true,
  tickLine: true,
};

export const commonGridProps = {
  stroke: '#e5e7eb',
  strokeDasharray: '3 3',
};

export const commonTooltipProps = {
  backgroundColor: '#1f2937',
  contentStyle: {
    backgroundColor: '#1f2937',
    border: 'none',
    borderRadius: '8px',
    color: '#f9fafb',
    fontSize: '14px',
    padding: '12px',
  },
  itemStyle: {
    padding: '4px 0',
  },
};

/**
 * Chart size presets
 */
export const chartSizes = {
  small: { width: 300, height: 200 },
  medium: { width: 500, height: 300 },
  large: { width: 800, height: 400 },
  full: { width: '100%', height: 400 },
};

/**
 * Animation durations
 */
export const animationDurations = {
  fast: 300,
  normal: 500,
  slow: 800,
} as const;

/**
 * Line chart variants
 */
export const lineVariants = {
  default: {
    strokeWidth: 2,
    dot: { r: 3 },
    activeDot: { r: 5 },
  },
  thin: {
    strokeWidth: 1,
    dot: false,
    activeDot: { r: 4 },
  },
  thick: {
    strokeWidth: 3,
    dot: { r: 4 },
    activeDot: { r: 6 },
  },
};

/**
 * Bar chart variants
 */
export const barVariants = {
  default: {
    radius: [4, 4, 0, 0] as [number, number, number, number],
  },
  rounded: {
    radius: [8, 8, 0, 0] as [number, number, number, number],
  },
  sharp: {
    radius: [0, 0, 0, 0] as [number, number, number, number],
  },
};

/**
 * Area chart gradients
 */
export const areaGradients = {
  blue: {
    start: '#3b82f6',
    end: '#93c5fd',
  },
  green: {
    start: '#10b981',
    end: '#6ee7b7',
  },
  purple: {
    start: '#8b5cf6',
    end: '#c4b5fd',
  },
  amber: {
    start: '#f59e0b',
    end: '#fcd34d',
  },
};
