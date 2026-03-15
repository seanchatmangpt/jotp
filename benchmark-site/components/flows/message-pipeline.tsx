'use client';

import React, { useState, useEffect, useRef } from 'react';
import { MailboxNode } from '../nodes/mailbox-node';
import { VirtualThreadNode } from '../nodes/virtual-thread-node';

interface Message {
  id: string;
  type: 'tell' | 'ask';
  payload: string;
  timestamp: number;
  state: 'in-transit' | 'queued' | 'processing' | 'responding' | 'completed';
  position: { x: number; y: number };
}

interface PipelineProps {
  messageRate?: number; // messages per second
  mailboxCapacity?: number;
  simulationSpeed?: number;
}

export function MessagePipeline({
  messageRate = 2,
  mailboxCapacity = 100,
  simulationSpeed = 1
}: PipelineProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [mailboxState, setMailboxState] = useState({
    queueDepth: 0,
    capacity: mailboxCapacity,
    utilization: 0
  });
  const [threadState, setThreadState] = useState({
    activeThreads: 0,
    carrierThreads: 4,
    currentHandler: null as string | null,
    processingTime: 0
  });
  const [metrics, setMetrics] = useState({
    totalMessages: 0,
    avgLatency: 0,
    throughput: 0,
    backpressureEvents: 0
  });

  const messageCounter = useRef(0);
  const animationFrameRef = useRef<number | undefined>(undefined);
  const lastMessageTime = useRef(Date.now());
  const latencyHistory = useRef<number[]>([]);

  // Generate messages at specified rate
  useEffect(() => {
    const interval = setInterval(() => {
      const now = Date.now();
      if (now - lastMessageTime.current >= (1000 / messageRate) * simulationSpeed) {
        const id = `msg-${messageCounter.current++}`;
        const type: 'tell' | 'ask' = Math.random() > 0.6 ? 'ask' : 'tell';

        const newMessage: Message = {
          id,
          type,
          payload: `Data-${Math.floor(Math.random() * 1000)}`,
          timestamp: now,
          state: 'in-transit',
          position: { x: 0, y: 0 }
        };

        setMessages(prev => [...prev, newMessage]);
        setMetrics(prev => ({ ...prev, totalMessages: prev.totalMessages + 1 }));
        lastMessageTime.current = now;
      }
    }, 100);

    return () => clearInterval(interval);
  }, [messageRate, simulationSpeed]);

  // Animate messages through pipeline
  useEffect(() => {
    const animate = () => {
      setMessages(prevMessages => {
        const updated = prevMessages.map(msg => {
          const startTime = msg.timestamp;
          const elapsed = Date.now() - startTime;

          // Pipeline stages with timing (in ms, scaled by simulationSpeed)
          const stages = {
            toMailbox: 300 / simulationSpeed,
            inMailbox: 500 / simulationSpeed,
            processing: 800 / simulationSpeed,
            response: 400 / simulationSpeed
          };

          // Calculate position based on stage
          let newState = msg.state;
          let newPosition = { ...msg.position };

          if (elapsed < stages.toMailbox) {
            // Message traveling to mailbox
            const progress = elapsed / stages.toMailbox;
            newPosition.x = progress * 25; // 0% to 25% of pipeline
            newPosition.y = 0;
            newState = 'in-transit';
          } else if (elapsed < stages.toMailbox + stages.inMailbox) {
            // Message queued in mailbox
            if (msg.state !== 'queued') {
              setMailboxState(prev => ({
                ...prev,
                queueDepth: prev.queueDepth + 1,
                utilization: ((prev.queueDepth + 1) / prev.capacity) * 100
              }));
            }
            newPosition.x = 25;
            newState = 'queued';
          } else if (elapsed < stages.toMailbox + stages.inMailbox + stages.processing) {
            // Message being processed
            if (msg.state === 'queued') {
              setMailboxState(prev => ({
                ...prev,
                queueDepth: Math.max(0, prev.queueDepth - 1),
                utilization: (Math.max(0, prev.queueDepth - 1) / prev.capacity) * 100
              }));
              setThreadState(prev => ({
                ...prev,
                activeThreads: prev.activeThreads + 1,
                currentHandler: `Handler-${Number(msg.id.split('-')[1]) % 4}`,
                processingTime: stages.processing
              }));
            }
            const processingProgress = (elapsed - stages.toMailbox - stages.inMailbox) / stages.processing;
            newPosition.x = 25 + processingProgress * 50; // 25% to 75% of pipeline
            newState = 'processing';
          } else if (msg.type === 'ask' && elapsed < stages.toMailbox + stages.inMailbox + stages.processing + stages.response) {
            // Response being sent back
            if (msg.state === 'processing') {
              setThreadState(prev => ({
                ...prev,
                activeThreads: Math.max(0, prev.activeThreads - 1),
                currentHandler: null
              }));

              // Track latency
              const latency = elapsed;
              latencyHistory.current.push(latency);
              if (latencyHistory.current.length > 100) {
                latencyHistory.current.shift();
              }
              const avgLatency = latencyHistory.current.reduce((a, b) => a + b, 0) / latencyHistory.current.length;
              setMetrics(prev => ({ ...prev, avgLatency }));
            }
            const responseProgress = (elapsed - stages.toMailbox - stages.inMailbox - stages.processing) / stages.response;
            newPosition.x = 75 - responseProgress * 75; // Return to start
            newState = 'responding';
          } else if (msg.type === 'tell' && elapsed >= stages.toMailbox + stages.inMailbox + stages.processing) {
            // Tell message completed
            if (msg.state === 'processing') {
              setThreadState(prev => ({
                ...prev,
                activeThreads: Math.max(0, prev.activeThreads - 1),
                currentHandler: null
              }));
            }
            newState = 'completed';
          } else if (msg.type === 'ask' && elapsed >= stages.toMailbox + stages.inMailbox + stages.processing + stages.response) {
            // Ask message completed
            newState = 'completed';
          }

          return { ...msg, state: newState, position: newPosition };
        });

        // Remove completed messages
        const active = updated.filter(msg => msg.state !== 'completed');

        // Update throughput calculation
        if (active.length !== prevMessages.length) {
          const completed = prevMessages.length - active.length;
          setMetrics(prev => ({
            ...prev,
            throughput: (prev.totalMessages / ((Date.now() - lastMessageTime.current) / 1000)) * messageRate
          }));
        }

        return active;
      });

      animationFrameRef.current = requestAnimationFrame(animate);
    };

    animationFrameRef.current = requestAnimationFrame(animate);

    return () => {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
      }
    };
  }, [simulationSpeed, messageRate]);

  return (
    <div className="w-full">
      {/* Metrics Header */}
      <div className="mb-6 grid grid-cols-4 gap-4">
        <MetricCard
          label="Total Messages"
          value={metrics.totalMessages.toString()}
          unit="msgs"
          color="blue"
        />
        <MetricCard
          label="Avg Latency"
          value={metrics.avgLatency.toFixed(2)}
          unit="ms"
          color="green"
        />
        <MetricCard
          label="Throughput"
          value={metrics.throughput.toFixed(0)}
          unit="msg/s"
          color="purple"
        />
        <MetricCard
          label="Queue Utilization"
          value={mailboxState.utilization.toFixed(1)}
          unit="%"
          color={mailboxState.utilization > 80 ? 'red' : 'orange'}
        />
      </div>

      {/* Pipeline Visualization */}
      <div className="relative bg-gradient-to-r from-slate-900 via-slate-800 to-slate-900 rounded-lg p-8 h-96 overflow-hidden">
        {/* Pipeline Background */}
        <div className="absolute inset-0 flex items-center">
          {/* Pipeline Track */}
          <div className="w-full h-1 bg-slate-700 relative">
            {/* Stage Markers */}
            <div className="absolute top-0 left-[25%] w-0.5 h-8 bg-slate-600" />
            <div className="absolute top-0 left-[75%] w-0.5 h-8 bg-slate-600" />

            {/* Stage Labels */}
            <div className="absolute -top-6 left-[12.5%] text-xs text-slate-400 whitespace-nowrap">
              Send
            </div>
            <div className="absolute -top-6 left-[50%] text-xs text-slate-400 whitespace-nowrap">
              Mailbox → Process
            </div>
            <div className="absolute -top-6 left-[87.5%] text-xs text-slate-400 whitespace-nowrap">
              Respond
            </div>
          </div>
        </div>

        {/* Mailbox Node */}
        <div className="absolute left-[25%] top-1/2 -translate-y-1/2 -translate-x-1/2 z-10">
          <MailboxNode
            queueDepth={mailboxState.queueDepth}
            capacity={mailboxState.capacity}
            utilization={mailboxState.utilization}
          />
        </div>

        {/* Virtual Thread Node */}
        <div className="absolute left-[60%] top-1/2 -translate-y-1/2 -translate-x-1/2 z-10">
          <VirtualThreadNode
            activeThreads={threadState.activeThreads}
            carrierThreads={threadState.carrierThreads}
            currentHandler={threadState.currentHandler}
            processingTime={threadState.processingTime}
          />
        </div>

        {/* Animated Messages */}
        {messages.map(msg => (
          <AnimatedMessage
            key={msg.id}
            message={msg}
          />
        ))}

        {/* Legend */}
        <div className="absolute bottom-4 left-4 flex gap-4 text-xs">
          <LegendItem color="bg-blue-500" label="Tell (Async)" />
          <LegendItem color="bg-green-500" label="Ask (Sync)" />
          <LegendItem color="bg-yellow-500" label="Processing" />
        </div>
      </div>

      {/* Message Type Controls */}
      <div className="mt-4 flex gap-4 items-center">
        <span className="text-sm text-slate-400">Message Mix:</span>
        <div className="flex gap-2">
          <span className="px-3 py-1 bg-blue-500/20 text-blue-400 rounded text-sm">
            Tell: {messages.filter(m => m.type === 'tell').length}
          </span>
          <span className="px-3 py-1 bg-green-500/20 text-green-400 rounded text-sm">
            Ask: {messages.filter(m => m.type === 'ask').length}
          </span>
        </div>
      </div>
    </div>
  );
}

interface AnimatedMessageProps {
  message: Message;
}

function AnimatedMessage({ message }: AnimatedMessageProps) {
  const getColor = () => {
    switch (message.state) {
      case 'in-transit':
        return message.type === 'tell' ? 'bg-blue-500' : 'bg-green-500';
      case 'queued':
        return 'bg-purple-500';
      case 'processing':
        return 'bg-yellow-500';
      case 'responding':
        return 'bg-green-400';
      default:
        return 'bg-slate-500';
    }
  };

  const getAnimationClass = () => {
    switch (message.state) {
      case 'in-transit':
        return 'animate-pulse';
      case 'processing':
        return 'animate-spin';
      default:
        return '';
    }
  };

  return (
    <div
      className={`absolute top-1/2 -translate-y-1/2 -translate-x-1/2 z-20 transition-all duration-100 ease-linear ${getAnimationClass()}`}
      style={{
        left: `${message.position.x}%`,
        transform: `translate(-50%, -50%)`
      }}
    >
      <div className={`w-3 h-3 rounded-full ${getColor()} shadow-lg`} />
      <div className="absolute -top-6 left-1/2 -translate-x-1/2 text-xs text-white whitespace-nowrap">
        {message.id}
      </div>
      <div className="absolute -bottom-6 left-1/2 -translate-x-1/2 text-xs text-slate-400 whitespace-nowrap">
        {message.type}
      </div>
    </div>
  );
}

interface MetricCardProps {
  label: string;
  value: string;
  unit: string;
  color: 'blue' | 'green' | 'purple' | 'orange' | 'red';
}

function MetricCard({ label, value, unit, color }: MetricCardProps) {
  const colorClasses = {
    blue: 'bg-blue-500/10 border-blue-500 text-blue-400',
    green: 'bg-green-500/10 border-green-500 text-green-400',
    purple: 'bg-purple-500/10 border-purple-500 text-purple-400',
    orange: 'bg-orange-500/10 border-orange-500 text-orange-400',
    red: 'bg-red-500/10 border-red-500 text-red-400'
  };

  return (
    <div className={`p-4 rounded-lg border ${colorClasses[color]}`}>
      <div className="text-sm opacity-70">{label}</div>
      <div className="text-2xl font-bold">
        {value}
        <span className="text-sm font-normal ml-1">{unit}</span>
      </div>
    </div>
  );
}

interface LegendItemProps {
  color: string;
  label: string;
}

function LegendItem({ color, label }: LegendItemProps) {
  return (
    <div className="flex items-center gap-2">
      <div className={`w-3 h-3 rounded-full ${color}`} />
      <span className="text-slate-300">{label}</span>
    </div>
  );
}
