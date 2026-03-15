"use client";

import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from "recharts";

interface BenchmarkChartProps {
  data: Array<Record<string, string | number>>;
  title: string;
  dataKey: string;
  xAxisKey: string;
  color?: string;
}

export function BenchmarkChart({ data, title, dataKey, xAxisKey, color = "#3b82f6" }: BenchmarkChartProps) {
  return (
    <div className="bg-slate-800/30 border border-slate-700 rounded-lg p-6">
      <h3 className="text-lg font-semibold text-white mb-4">{title}</h3>
      <ResponsiveContainer width="100%" height={300}>
        <BarChart data={data}>
          <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
          <XAxis 
            dataKey={xAxisKey} 
            stroke="#94a3b8"
            tick={{ fill: "#94a3b8" }}
          />
          <YAxis 
            stroke="#94a3b8"
            tick={{ fill: "#94a3b8" }}
          />
          <Tooltip 
            contentStyle={{ 
              backgroundColor: "#1e293b", 
              border: "1px solid #334155",
              borderRadius: "8px"
            }}
            labelStyle={{ color: "#f1f5f9" }}
          />
          <Legend />
          <Bar dataKey={dataKey} fill={color} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
