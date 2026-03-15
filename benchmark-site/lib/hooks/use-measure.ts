/**
 * useMeasure hook for measuring DOM element dimensions
 * Alternative to react-use for performance
 */

import { useState, useEffect, RefObject } from 'react';

export interface MeasureRect {
  width: number;
  height: number;
  top: number;
  left: number;
  right: number;
  bottom: number;
  x: number;
  y: number;
}

export function useMeasure(): [RefObject<HTMLDivElement>, MeasureRect] {
  const ref = React.useRef<HTMLDivElement>(null);
  const [rect, setRect] = useState<MeasureRect>({
    width: 0,
    height: 0,
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    x: 0,
    y: 0
  });

  useEffect(() => {
    const element = ref.current;
    if (!element) return;

    const observer = new ResizeObserver(([entry]) => {
      if (entry) {
        const { width, height, top, left, right, bottom, x, y } = entry.contentRect;
        setRect({ width, height, top, left, right, bottom, x, y });
      }
    });

    observer.observe(element);

    return () => observer.disconnect();
  }, []);

  return [ref, rect];
}

// Import React for the hook
import React from 'react';
