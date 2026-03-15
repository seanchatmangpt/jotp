'use client';

import React, { useState, useCallback } from 'react';
import { motion } from 'framer-motion';
import { useMachine } from '@xstate/react';
import { flowMachine } from '@/lib/state-machines/flow-machine';

interface FlowControlsProps {
  machine?: ReturnType<typeof useMachine>;
  onStepForward?: () => void;
  onStepBackward?: () => void;
  onSpeedChange?: (speed: number) => void;
  className?: string;
}

const PlayIcon = () => (
  <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
    <path d="M6.3 2.841A1.5 1.5 0 004 4.11V15.89a1.5 1.5 0 002.3 1.269l9.344-5.89a1.5 1.5 0 000-2.538L6.3 2.84z" />
  </svg>
);

const PauseIcon = () => (
  <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
    <path d="M5.75 3a.75.75 0 00-.75.75v12.5c0 .414.336.75.75.75h1.5a.75.75 0 00.75-.75V3.75A.75.75 0 007.25 3h-1.5zM12.75 3a.75.75 0 00-.75.75v12.5c0 .414.336.75.75.75h1.5a.75.75 0 00.75-.75V3.75a.75.75 0 00-.75-.75h-1.5z" />
  </svg>
);

const StopIcon = () => (
  <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
    <path d="M4.75 4A.75.75 0 004 4.75v10.5c0 .414.336.75.75.75h10.5a.75.75 0 00.75-.75V4.75A.75.75 0 0015.25 4H4.75z" />
  </svg>
);

const StepForwardIcon = () => (
  <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
    <path d="M4.75 3a.75.75 0 00-.75.75v12.5c0 .414.336.75.75.75h1.5a.75.75 0 00.75-.75V3.75A.75.75 0 006.25 3h-1.5zM8.75 3.5a.75.75 0 011.183-.613l6.032 4.5a.75.75 0 010 1.226l-6.032 4.5A.75.75 0 018.75 12.5v-9z" />
  </svg>
);

const StepBackwardIcon = () => (
  <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
    <path d="M15.25 3a.75.75 0 01.75.75v12.5c0 .414-.336.75-.75.75h-1.5a.75.75 0 01-.75-.75V3.75A.75.75 0 0113.75 3h1.5zM11.25 3.5a.75.75 0 00-1.183-.613l-6.032 4.5a.75.75 0 000 1.226l6.032 4.5A.75.75 0 0011.25 12.5v-9z" />
  </svg>
);

const SpeedIcon = () => (
  <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
    <path fillRule="evenodd" d="M3.75 4.5a.75.75 0 01.75-.75h.75c8.284 0 15 6.716 15 15a.75.75 0 01-.75.75h-.75a.75.75 0 01-.75-.75c0-7.456-6.044-13.5-13.5-13.5a.75.75 0 01-.75-.75V4.5zm0 5.25a.75.75 0 01.75-.75h.75c3.866 0 7 3.134 7 7a.75.75 0 01-.75.75h-.75a.75.75 0 01-.75-.75c0-3.038-2.462-5.5-5.5-5.5a.75.75 0 01-.75-.75v-.75zm0 5.25a.75.75 0 01.75-.75h.75c1.243 0 2.25 1.007 2.25 2.25a.75.75 0 01-.75.75h-.75a.75.75 0 01-.75-.75.75.75 0 00-.75-.75h-.75a.75.75 0 01-.75-.75v-.75z" clipRule="evenodd" />
  </svg>
);

export const FlowControls: React.FC<FlowControlsProps> = ({
  machine,
  onStepForward,
  onStepBackward,
  onSpeedChange,
  className = ''
}) => {
  const [localSpeed, setLocalSpeed] = useState(1.0);

  // If no machine provided, use local state
  const [state, send] = machine || useMachine(flowMachine);
  const { isPlaying, animationSpeed } = state.context;

  const handlePlayPause = useCallback(() => {
    if (isPlaying) {
      send({ type: 'PAUSE' });
    } else {
      send({ type: 'RESUME' });
    }
  }, [isPlaying, send]);

  const handleStop = useCallback(() => {
    send({ type: 'RESET' });
  }, [send]);

  const handleSpeedChange = useCallback((speed: number) => {
    setLocalSpeed(speed);
    send({ type: 'SET_SPEED', speed });
    onSpeedChange?.(speed);
  }, [send, onSpeedChange]);

  const speedPresets = [0.25, 0.5, 1.0, 2.0, 4.0];

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      className={`flex items-center gap-3 bg-gray-900/80 backdrop-blur-md rounded-xl border border-gray-700/50 p-3 ${className}`}
    >
      {/* Playback Controls */}
      <div className="flex items-center gap-2">
        <motion.button
          whileHover={{ scale: 1.05 }}
          whileTap={{ scale: 0.95 }}
          onClick={onStepBackward}
          className="p-2 bg-gray-800 hover:bg-gray-700 rounded-lg transition-colors"
          title="Step Backward"
        >
          <StepBackwardIcon />
        </motion.button>

        <motion.button
          whileHover={{ scale: 1.05 }}
          whileTap={{ scale: 0.95 }}
          onClick={handlePlayPause}
          className={`p-3 rounded-lg transition-colors ${
            isPlaying
              ? 'bg-yellow-600 hover:bg-yellow-500'
              : 'bg-green-600 hover:bg-green-500'
          }`}
          title={isPlaying ? 'Pause' : 'Play'}
        >
          {isPlaying ? <PauseIcon /> : <PlayIcon />}
        </motion.button>

        <motion.button
          whileHover={{ scale: 1.05 }}
          whileTap={{ scale: 0.95 }}
          onClick={handleStop}
          className="p-2 bg-gray-800 hover:bg-red-600/80 rounded-lg transition-colors"
          title="Stop"
        >
          <StopIcon />
        </motion.button>

        <motion.button
          whileHover={{ scale: 1.05 }}
          whileTap={{ scale: 0.95 }}
          onClick={onStepForward}
          className="p-2 bg-gray-800 hover:bg-gray-700 rounded-lg transition-colors"
          title="Step Forward"
        >
          <StepForwardIcon />
        </motion.button>
      </div>

      {/* Separator */}
      <div className="w-px h-8 bg-gray-700" />

      {/* Speed Controls */}
      <div className="flex items-center gap-2">
        <SpeedIcon />
        <span className="text-xs text-gray-400">Speed</span>
        <div className="flex items-center gap-1">
          {speedPresets.map(preset => (
            <motion.button
              key={preset}
              whileHover={{ scale: 1.05 }}
              whileTap={{ scale: 0.95 }}
              onClick={() => handleSpeedChange(preset)}
              className={`px-2 py-1 text-xs rounded transition-colors ${
                localSpeed === preset
                  ? 'bg-blue-600 text-white'
                  : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
              }`}
            >
              {preset}x
            </motion.button>
          ))}
        </div>
      </div>

      {/* Custom Speed Slider */}
      <div className="flex items-center gap-2">
        <input
          type="range"
          min="0.1"
          max="5.0"
          step="0.1"
          value={animationSpeed}
          onChange={(e) => handleSpeedChange(parseFloat(e.target.value))}
          className="w-24 h-2 bg-gray-700 rounded-lg appearance-none cursor-pointer"
        />
        <span className="text-xs text-gray-400 w-12">
          {animationSpeed.toFixed(1)}x
        </span>
      </div>

      {/* Status Indicator */}
      <div className="ml-auto flex items-center gap-2">
        <motion.div
          animate={{
            scale: isPlaying ? [1, 1.2, 1] : 1,
            opacity: isPlaying ? [1, 0.7, 1] : 0.3
          }}
          transition={{
            duration: 2,
            repeat: isPlaying ? Infinity : 0,
            ease: 'easeInOut'
          }}
          className={`w-2 h-2 rounded-full ${
            isPlaying ? 'bg-green-500' : 'bg-gray-500'
          }`}
        />
        <span className="text-xs text-gray-400">
          {isPlaying ? 'Running' : 'Paused'}
        </span>
      </div>
    </motion.div>
  );
};

// Mini version for embedded controls
export const MiniFlowControls: React.FC<{
  isPlaying: boolean;
  onPlayPause: () => void;
  speed: number;
  onSpeedChange: (speed: number) => void;
}> = ({ isPlaying, onPlayPause, speed, onSpeedChange }) => {
  return (
    <div className="flex items-center gap-2">
      <button
        onClick={onPlayPause}
        className={`p-2 rounded-lg transition-colors ${
          isPlaying
            ? 'bg-yellow-600 hover:bg-yellow-500'
            : 'bg-green-600 hover:bg-green-500'
        }`}
      >
        {isPlaying ? <PauseIcon /> : <PlayIcon />}
      </button>
      <div className="flex items-center gap-1 bg-gray-800 rounded-lg p-1">
        {[0.5, 1.0, 2.0].map(preset => (
          <button
            key={preset}
            onClick={() => onSpeedChange(preset)}
            className={`px-2 py-1 text-xs rounded transition-colors ${
              speed === preset
                ? 'bg-blue-600 text-white'
                : 'text-gray-400 hover:bg-gray-700'
            }`}
          >
            {preset}x
          </button>
        ))}
      </div>
    </div>
  );
};
