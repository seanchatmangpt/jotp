'use client';

import { Benchmark } from '@/lib/types';
import { useEffect, useState } from 'react';

interface LiveCardProps {
  benchmark: Benchmark;
}

export function LiveCard({ benchmark }: LiveCardProps) {
  const [progress, setProgress] = useState(benchmark.progress);
  const [status, setStatus] = useState(benchmark.status);

  useEffect(() => {
    if (benchmark.status === 'running') {
      const interval = setInterval(() => {
        setProgress(prev => {
          if (prev >= 100) return 100;
          return prev + Math.random() * 5;
        });
      }, 1000);

      return () => clearInterval(interval);
    }
  }, [benchmark.status]);

  const statusColors = {
    pending: 'bg-yellow-100 text-yellow-800',
    running: 'bg-blue-100 text-blue-800',
    completed: 'bg-green-100 text-green-800',
    failed: 'bg-red-100 text-red-800',
  };

  return (
    <div className="bg-white dark:bg-gray-800 rounded-lg shadow p-6">
      <div className="flex justify-between items-start mb-4">
        <div>
          <h3 className="text-lg font-semibold">{benchmark.name}</h3>
          <p className="text-sm text-gray-600 dark:text-gray-400">{benchmark.category}</p>
        </div>
        <span className={`px-3 py-1 rounded-full text-sm font-medium ${statusColors[status]}`}>
          {status.toUpperCase()}
        </span>
      </div>

      {status === 'running' && (
        <div className="mb-4">
          <div className="flex justify-between text-sm mb-2">
            <span>Progress</span>
            <span>{Math.round(progress)}%</span>
          </div>
          <div className="w-full bg-gray-200 rounded-full h-2">
            <div 
              className="bg-blue-600 h-2 rounded-full transition-all duration-300"
              style={{ width: `${progress}%` }}
            />
          </div>
        </div>
      )}

      {benchmark.metrics && (
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <p className="text-gray-600 dark:text-gray-400">Throughput</p>
            <p className="font-semibold">{benchmark.metrics.throughput.toLocaleString()} ops/s</p>
          </div>
          <div>
            <p className="text-gray-600 dark:text-gray-400">Avg Latency</p>
            <p className="font-semibold">{benchmark.metrics.latency.mean.toFixed(2)} ns</p>
          </div>
          <div>
            <p className="text-gray-600 dark:text-gray-400">Memory</p>
            <p className="font-semibold">{benchmark.metrics.memory.used} MB</p>
          </div>
          <div>
            <p className="text-gray-600 dark:text-gray-400">CPU</p>
            <p className="font-semibold">{benchmark.metrics.cpu}%</p>
          </div>
        </div>
      )}

      {benchmark.duration && (
        <div className="mt-4 pt-4 border-t text-sm text-gray-600 dark:text-gray-400">
          Duration: {benchmark.duration}s
        </div>
      )}
    </div>
  );
}
