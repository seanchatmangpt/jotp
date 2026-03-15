'use client';

import { LogEntry } from '@/lib/types';
import { useEffect, useRef, useState } from 'react';

interface BenchmarkLogProps {
  logs: LogEntry[];
  benchmarkId?: string;
}

export function BenchmarkLog({ logs: initialLogs, benchmarkId }: BenchmarkLogProps) {
  const [logs, setLogs] = useState<LogEntry[]>(initialLogs);
  const logEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (benchmarkId) {
      // Subscribe to SSE updates for live logs
      const eventSource = new EventSource(`/api/benchmarks/stream`);
      
      eventSource.onmessage = (event) => {
        const update = JSON.parse(event.data);
        
        if (update.benchmarkId === benchmarkId && update.type === 'log') {
          setLogs(prev => [...prev, update.data]);
        }
      };

      return () => {
        eventSource.close();
      };
    }
  }, [benchmarkId]);

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  const levelColors = {
    info: 'text-blue-600',
    warn: 'text-yellow-600',
    error: 'text-red-600',
    debug: 'text-gray-500',
  };

  return (
    <div className="bg-gray-900 text-white rounded-lg p-4 h-96 overflow-y-auto font-mono text-sm">
      {logs.map((log, index) => (
        <div key={index} className="mb-2">
          <span className="text-gray-400">
            {new Date(log.timestamp).toLocaleTimeString()}
          </span>
          {' '}
          <span className={`${levelColors[log.level]} font-semibold`}>
            [{log.level.toUpperCase()}]
          </span>
          {' '}
          <span>{log.message}</span>
          {log.data && (
            <pre className="ml-4 mt-1 text-xs text-gray-300 overflow-x-auto">
              {JSON.stringify(log.data, null, 2)}
            </pre>
          )}
        </div>
      ))}
      <div ref={logEndRef} />
    </div>
  );
}
