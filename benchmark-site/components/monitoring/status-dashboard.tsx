'use client';

import { SystemMetrics, Benchmark } from '@/lib/types';
import { useEffect, useState } from 'react';

interface StatusDashboardProps {
  initialMetrics: SystemMetrics;
  activeBenchmarks: Benchmark[];
}

export function StatusDashboard({ initialMetrics, activeBenchmarks }: StatusDashboardProps) {
  const [metrics, setMetrics] = useState(initialMetrics);

  useEffect(() => {
    // Poll for updates every 5 seconds
    const interval = setInterval(async () => {
      try {
        const response = await fetch('/api/metrics');
        const data = await response.json();
        setMetrics(data);
      } catch (error) {
        console.error('Failed to fetch metrics:', error);
      }
    }, 5000);

    return () => clearInterval(interval);
  }, []);

  const getLoadColor = (load: number) => {
    if (load < 2) return 'text-green-600';
    if (load < 4) return 'text-yellow-600';
    return 'text-red-600';
  };

  return (
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-6">
      <div className="bg-white dark:bg-gray-800 rounded-lg shadow p-6">
        <h3 className="text-sm font-medium text-gray-600 dark:text-gray-400 mb-2">
          Active Benchmarks
        </h3>
        <p className="text-3xl font-bold">{metrics.activeBenchmarks}</p>
      </div>

      <div className="bg-white dark:bg-gray-800 rounded-lg shadow p-6">
        <h3 className="text-sm font-medium text-gray-600 dark:text-gray-400 mb-2">
          System Load
        </h3>
        <p className={`text-3xl font-bold ${getLoadColor(metrics.systemLoad)}`}>
          {metrics.systemLoad.toFixed(2)}
        </p>
      </div>

      <div className="bg-white dark:bg-gray-800 rounded-lg shadow p-6">
        <h3 className="text-sm font-medium text-gray-600 dark:text-gray-400 mb-2">
          Memory Usage
        </h3>
        <p className="text-3xl font-bold">{metrics.memoryUsage}%</p>
        <div className="w-full bg-gray-200 rounded-full h-2 mt-2">
          <div 
            className="bg-blue-600 h-2 rounded-full"
            style={{ width: `${metrics.memoryUsage}%` }}
          />
        </div>
      </div>

      <div className="bg-white dark:bg-gray-800 rounded-lg shadow p-6">
        <h3 className="text-sm font-medium text-gray-600 dark:text-gray-400 mb-2">
          CPU Usage
        </h3>
        <p className="text-3xl font-bold">{metrics.cpuUsage}%</p>
        <div className="w-full bg-gray-200 rounded-full h-2 mt-2">
          <div 
            className="bg-green-600 h-2 rounded-full"
            style={{ width: `${metrics.cpuUsage}%` }}
          />
        </div>
      </div>
    </div>
  );
}
