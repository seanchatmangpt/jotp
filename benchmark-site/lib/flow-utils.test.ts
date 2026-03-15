// Example test file for flow utilities
// Run with: npm test

import { describe, it, expect } from '@jest/globals';
import {
  createGridLayout,
  createSequentialEdges,
  generateBenchmarkMetrics,
  createBenchmarkPipeline,
  createArchitectureDiagram,
  createPerformanceFlow
} from './flow-utils';

describe('Flow Utilities', () => {
  describe('createGridLayout', () => {
    it('should arrange nodes in a grid', () => {
      const nodes = [
        { id: '1', label: 'Node 1' },
        { id: '2', label: 'Node 2' },
        { id: '3', label: 'Node 3' }
      ];

      const grid = createGridLayout(nodes, 2);

      expect(grid).toHaveLength(3);
      expect(grid[0].position).toEqual({ x: 0, y: 0 });
      expect(grid[1].position).toEqual({ x: 250, y: 0 });
      expect(grid[2].position).toEqual({ x: 0, y: 150 });
    });
  });

  describe('createSequentialEdges', () => {
    it('should create edges between sequential nodes', () => {
      const edges = createSequentialEdges(['1', '2', '3']);

      expect(edges).toHaveLength(2);
      expect(edges[0].source).toBe('1');
      expect(edges[0].target).toBe('2');
      expect(edges[1].source).toBe('2');
      expect(edges[1].target).toBe('3');
    });

    it('should apply options to edges', () => {
      const edges = createSequentialEdges(['1', '2'], {
        animated: true,
        label: 'test'
      });

      expect(edges[0].animated).toBe(true);
      expect(edges[0].label).toBe('test');
    });
  });

  describe('generateBenchmarkMetrics', () => {
    it('should generate valid metrics', () => {
      const metrics = generateBenchmarkMetrics();

      expect(metrics.throughput).toBeGreaterThan(0);
      expect(metrics.latency).toBeGreaterThan(0);
      expect(metrics.errorRate).toBeGreaterThanOrEqual(0);
      expect(metrics.errorRate).toBeLessThan(1);
      expect(metrics.duration).toBeGreaterThan(0);
    });
  });

  describe('createBenchmarkPipeline', () => {
    it('should create a complete pipeline', () => {
      const { nodes, edges } = createBenchmarkPipeline();

      expect(nodes).toHaveLength(6);
      expect(edges).toHaveLength(5);
      expect(nodes[0].data.label).toBe('Configuration');
      expect(nodes[5].data.label).toBe('Report Generation');
    });
  });

  describe('createArchitectureDiagram', () => {
    it('should create architecture components', () => {
      const { nodes, edges } = createArchitectureDiagram();

      expect(nodes.length).toBeGreaterThan(0);
      expect(edges.length).toBeGreaterThan(0);

      const procNode = nodes.find(n => n.id === 'proc');
      expect(procNode).toBeDefined();
      expect(procNode?.data.type).toBe('proc');
    });
  });

  describe('createPerformanceFlow', () => {
    it('should create performance flow with timing', () => {
      const { nodes, edges } = createPerformanceFlow();

      expect(nodes).toHaveLength(4);
      expect(edges).toHaveLength(3);

      const hotPathNodes = nodes.filter(n => n.data.isHotPath);
      expect(hotPathNodes).toHaveLength(3);
    });
  });
});
