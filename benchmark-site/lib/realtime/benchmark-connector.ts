/**
 * Real-time benchmark data connector using WebSocket/SSE
 * Connects to running benchmarks and streams metrics to flow components
 */

import { FlowMetrics } from '@/lib/state-machines/flow-machine';

export interface BenchmarkEvent {
  type: 'metrics' | 'status' | 'error' | 'complete';
  timestamp: number;
  nodeId: string;
  data: FlowMetrics | { status: string } | { error: string } | { complete: boolean };
}

export interface BenchmarkConnectorConfig {
  url: string;
  reconnectInterval?: number;
  maxReconnectAttempts?: number;
  bufferSize?: number;
  onMessage?: (event: BenchmarkEvent) => void;
  onError?: (error: Error) => void;
  onStatusChange?: (status: 'connecting' | 'connected' | 'disconnected' | 'error') => void;
}

export class BenchmarkConnector {
  private ws: WebSocket | null = null;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private reconnectAttempts = 0;
  private messageBuffer: BenchmarkEvent[] = [];
  private isConnected = false;
  private isConnecting = false;

  constructor(private config: BenchmarkConnectorConfig) {}

  /**
   * Establish WebSocket connection
   */
  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      if (this.isConnected || this.isConnecting) {
        resolve();
        return;
      }

      this.isConnecting = true;
      this.notifyStatus('connecting');

      try {
        this.ws = new WebSocket(this.config.url);

        this.ws.onopen = () => {
          this.isConnected = true;
          this.isConnecting = false;
          this.reconnectAttempts = 0;
          this.notifyStatus('connected');
          resolve();
        };

        this.ws.onmessage = (event) => {
          this.handleMessage(event.data);
        };

        this.ws.onerror = (error) => {
          this.notifyStatus('error');
          this.config.onError?.(new Error('WebSocket error'));
        };

        this.ws.onclose = () => {
          this.isConnected = false;
          this.isConnecting = false;
          this.notifyStatus('disconnected');
          this.scheduleReconnect();
        };
      } catch (error) {
        this.isConnecting = false;
        this.notifyStatus('error');
        reject(error);
      }
    });
  }

  /**
   * Disconnect from WebSocket
   */
  disconnect(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }

    this.isConnected = false;
    this.isConnecting = false;
  }

  /**
   * Send command to benchmark
   */
  sendCommand(command: { type: string; data?: unknown }): void {
    if (!this.isConnected || !this.ws) {
      throw new Error('Not connected');
    }

    this.ws.send(JSON.stringify(command));
  }

  /**
   * Get buffered messages
   */
  getBufferedEvents(): BenchmarkEvent[] {
    return [...this.messageBuffer];
  }

  /**
   * Clear message buffer
   */
  clearBuffer(): void {
    this.messageBuffer = [];
  }

  /**
   * Handle incoming message
   */
  private handleMessage(data: string): void {
    try {
      const event: BenchmarkEvent = JSON.parse(data);

      // Add to buffer
      this.messageBuffer.push(event);
      if (this.messageBuffer.length > (this.config.bufferSize || 1000)) {
        this.messageBuffer.shift();
      }

      // Notify listener
      this.config.onMessage?.(event);
    } catch (error) {
      this.config.onError?.(error as Error);
    }
  }

  /**
   * Schedule reconnection attempt
   */
  private scheduleReconnect(): void {
    const maxAttempts = this.config.maxReconnectAttempts || 10;
    const interval = this.config.reconnectInterval || 1000;

    if (this.reconnectAttempts >= maxAttempts) {
      this.notifyStatus('error');
      this.config.onError?.(new Error('Max reconnection attempts reached'));
      return;
    }

    this.reconnectTimer = setTimeout(() => {
      this.reconnectAttempts++;
      this.connect().catch(error => {
        this.config.onError?.(error);
      });
    }, interval * Math.pow(1.5, this.reconnectAttempts));
  }

  /**
   * Notify status change
   */
  private notifyStatus(status: 'connecting' | 'connected' | 'disconnected' | 'error'): void {
    this.config.onStatusChange?.(status);
  }
}

/**
 * Server-Sent Events connector for one-way streaming
 */
export class SSEConnector {
  private eventSource: EventSource | null = null;
  private isConnected = false;

  constructor(private config: BenchmarkConnectorConfig) {}

  /**
   * Connect to SSE endpoint
   */
  connect(): void {
    if (this.isConnected) return;

    this.notifyStatus('connecting');

    this.eventSource = new EventSource(this.config.url);

    this.eventSource.onopen = () => {
      this.isConnected = true;
      this.notifyStatus('connected');
    };

    this.eventSource.onmessage = (event) => {
      this.handleMessage(event.data);
    };

    this.eventSource.onerror = () => {
      this.isConnected = false;
      this.notifyStatus('error');
      this.eventSource?.close();
    };
  }

  /**
   * Disconnect from SSE endpoint
   */
  disconnect(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.isConnected = false;
  }

  /**
   * Handle incoming message
   */
  private handleMessage(data: string): void {
    try {
      const event: BenchmarkEvent = JSON.parse(data);
      this.config.onMessage?.(event);
    } catch (error) {
      this.config.onError?.(error as Error);
    }
  }

  /**
   * Notify status change
   */
  private notifyStatus(status: 'connecting' | 'connected' | 'disconnected' | 'error'): void {
    this.config.onStatusChange?.(status);
  }
}

/**
 * Polling-based connector for REST APIs
 */
export class PollingConnector {
  private pollTimer: NodeJS.Timeout | null = null;
  private isPolling = false;
  private lastData: Record<string, FlowMetrics> = {};

  constructor(
    private config: {
      url: string;
      interval?: number;
      onMessage: (event: BenchmarkEvent) => void;
      onError?: (error: Error) => void;
    }
  ) {}

  /**
   * Start polling
   */
  start(): void {
    if (this.isPolling) return;

    this.isPolling = true;
    this.poll();

    const interval = this.config.interval || 5000;
    this.pollTimer = setInterval(() => {
      this.poll();
    }, interval);
  }

  /**
   * Stop polling
   */
  stop(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
    this.isPolling = false;
  }

  /**
   * Poll for data
   */
  private async poll(): Promise<void> {
    try {
      const response = await fetch(this.config.url);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const data = await response.json();
      this.processData(data);
    } catch (error) {
      this.config.onError?.(error as Error);
    }
  }

  /**
   * Process polled data
   */
  private processData(data: Record<string, FlowMetrics>): void {
    Object.entries(data).forEach(([nodeId, metrics]) => {
      const lastMetrics = this.lastData[nodeId];

      // Only send if data changed
      if (!lastMetrics || JSON.stringify(lastMetrics) !== JSON.stringify(metrics)) {
        this.config.onMessage({
          type: 'metrics',
          timestamp: Date.now(),
          nodeId,
          data: metrics
        });
      }
    });

    this.lastData = data;
  }
}

/**
 * Factory function to create appropriate connector
 */
export function createBenchmarkConnector(
  type: 'websocket' | 'sse' | 'polling',
  config: BenchmarkConnectorConfig
): BenchmarkConnector | SSEConnector | PollingConnector {
  switch (type) {
    case 'websocket':
      return new BenchmarkConnector(config);
    case 'sse':
      return new SSEConnector(config);
    case 'polling':
      return new PollingConnector({
        url: config.url,
        onMessage: config.onMessage || (() => {}),
        onError: config.onError
      });
    default:
      throw new Error(`Unknown connector type: ${type}`);
  }
}

/**
 * React hook for using benchmark connector
 */
export function useBenchmarkConnector(config: {
  type: 'websocket' | 'sse' | 'polling';
  url: string;
  onMetrics?: (nodeId: string, metrics: FlowMetrics) => void;
}) {
  const [status, setStatus] = React.useState<
    'connecting' | 'connected' | 'disconnected' | 'error'
  >('disconnected');

  const connector = React.useMemo(() => {
    return createBenchmarkConnector(config.type, {
      url: config.url,
      onMessage: (event) => {
        if (event.type === 'metrics') {
          config.onMetrics?.(event.nodeId, event.data as FlowMetrics);
        }
      },
      onStatusChange: setStatus
    });
  }, [config.type, config.url]);

  React.useEffect(() => {
    connector.connect();
    return () => connector.disconnect();
  }, [connector]);

  return {
    status,
    connector
  };
}

// Import React for the hook
import React from 'react';
