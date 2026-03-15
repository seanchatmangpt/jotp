/**
 * TypeScript data structures for JOTP primitive relationships.
 * Defines how primitives connect and interact with each other.
 */

import { Primitive, RelationshipType } from './primitives'

export interface RelationshipGraph {
  nodes: Map<string, PrimitiveNode>
  edges: RelationshipEdge[]
}

export interface PrimitiveNode {
  id: string
  primitive: Primitive
  incomingEdges: RelationshipEdge[]
  outgoingEdges: RelationshipEdge[]
}

export interface RelationshipEdge {
  id: string
  source: string
  target: string
  type: RelationshipType
  label?: string
  bidirectional: boolean
  strength: number // 0-1, indicates coupling strength
}

// Relationship type definitions with descriptions
export const RELATIONSHIP_TYPES: Record<RelationshipType, {
  description: string
  color: string
  icon: string
  dashed: boolean
}> = {
  manages: {
    description: 'A manages or controls the lifecycle of B',
    color: '#8B5CF6', // Purple
    icon: '👑',
    dashed: false
  },
  monitors: {
    description: 'A observes B for termination (unilateral)',
    color: '#3B82F6', // Blue
    icon: '👁️',
    dashed: true
  },
  'links-to': {
    description: 'Bidirectional crash propagation between A and B',
    color: '#EF4444', // Red
    icon: '🔗',
    dashed: false
  },
  'registers-with': {
    description: 'A is registered in B for lookup',
    color: '#F59E0B', // Orange
    icon: '📋',
    dashed: true
  },
  uses: {
    description: 'A uses B as a dependency',
    color: '#10B981', // Green
    icon: '⚙️',
    dashed: true
  },
  extends: {
    description: 'A extends or is built on top of B',
    color: '#6366F1', // Indigo
    icon: '🏗️',
    dashed: false
  },
  supervises: {
    description: 'A supervises B for fault tolerance',
    color: '#8B5CF6', // Purple
    icon: '🛡️',
    dashed: false
  },
  'spawned-by': {
    description: 'A is spawned/created by B',
    color: '#EC4899', // Pink
    icon: '✨',
    dashed: true
  }
}

// Primitive relationship mappings
// Defines all relationships between primitives
export const PRIMITIVE_RELATIONSHIPS: Array<{
  source: string
  target: string
  type: RelationshipType
  label?: string
  bidirectional?: boolean
  strength: number
}> = [
  // Proc relationships
  {
    source: 'proc',
    target: 'procref',
    type: 'extends',
    label: 'wrapped by',
    strength: 0.9
  },
  {
    source: 'proc',
    target: 'supervisor',
    type: 'manages',
    label: 'supervised by',
    strength: 1.0
  },
  {
    source: 'proc',
    target: 'proclink',
    type: 'links-to',
    label: 'bidirectional crash',
    bidirectional: true,
    strength: 0.95
  },
  {
    source: 'proc',
    target: 'procmonitor',
    type: 'monitors',
    label: 'monitored by',
    strength: 0.7
  },
  {
    source: 'proc',
    target: 'procregistry',
    type: 'registers-with',
    label: 'can register with',
    strength: 0.6
  },
  {
    source: 'proc',
    target: 'proctimer',
    type: 'uses',
    label: 'receives timed messages',
    strength: 0.5
  },
  {
    source: 'proc',
    target: 'procsys',
    type: 'uses',
    label: 'inspected by',
    strength: 0.4
  },
  {
    source: 'proc',
    target: 'exitsignal',
    type: 'uses',
    label: 'can trap as message',
    strength: 0.8
  },

  // ProcRef relationships
  {
    source: 'procref',
    target: 'proc',
    type: 'extends',
    label: 'wraps',
    strength: 0.9
  },
  {
    source: 'procref',
    target: 'supervisor',
    type: 'uses',
    label: 'returned by',
    strength: 0.8
  },

  // Supervisor relationships
  {
    source: 'supervisor',
    target: 'proc',
    type: 'supervises',
    label: 'manages',
    strength: 1.0
  },
  {
    source: 'supervisor',
    target: 'procref',
    type: 'uses',
    label: 'returns',
    strength: 0.8
  },
  {
    source: 'supervisor',
    target: 'application',
    type: 'manages',
    label: 'coordinated by',
    strength: 0.7
  },

  // StateMachine relationships
  {
    source: 'statemachine',
    target: 'proc',
    type: 'extends',
    label: 'built on',
    strength: 0.9
  },

  // Parallel relationships
  {
    source: 'parallel',
    target: 'result',
    type: 'uses',
    label: 'returns',
    strength: 0.8
  },

  // ProcLink relationships
  {
    source: 'proclink',
    target: 'proc',
    type: 'uses',
    label: 'links',
    strength: 0.9
  },
  {
    source: 'proclink',
    target: 'exitsignal',
    type: 'uses',
    label: 'delivers',
    strength: 0.8
  },

  // ProcMonitor relationships
  {
    source: 'procmonitor',
    target: 'proc',
    type: 'monitors',
    label: 'watches',
    strength: 0.7
  },

  // ProcRegistry relationships
  {
    source: 'procregistry',
    target: 'proc',
    type: 'manages',
    label: 'tracks',
    strength: 0.6
  },
  {
    source: 'procregistry',
    target: 'eventmanager',
    type: 'registers-with',
    label: 'can register with',
    strength: 0.5
  },

  // ProcTimer relationships
  {
    source: 'proctimer',
    target: 'proc',
    type: 'uses',
    label: 'sends to',
    strength: 0.5
  },

  // ProcSys relationships
  {
    source: 'procsys',
    target: 'proc',
    type: 'uses',
    label: 'inspects',
    strength: 0.4
  },

  // ProcLib relationships
  {
    source: 'proclib',
    target: 'proc',
    type: 'spawned-by',
    label: 'creates',
    strength: 0.8
  },

  // EventManager relationships
  {
    source: 'eventmanager',
    target: 'proc',
    type: 'extends',
    label: 'built on',
    strength: 0.9
  },
  {
    source: 'eventmanager',
    target: 'procregistry',
    type: 'registers-with',
    label: 'can register with',
    strength: 0.5
  },

  // ExitSignal relationships
  {
    source: 'exitsignal',
    target: 'proc',
    type: 'uses',
    label: 'delivered to',
    strength: 0.8
  },
  {
    source: 'exitsignal',
    target: 'proclink',
    type: 'uses',
    label: 'sent by',
    strength: 0.8
  },

  // Result relationships
  {
    source: 'result',
    target: 'parallel',
    type: 'used-by',
    label: 'returned by',
    strength: 0.8
  },

  // Application relationships
  {
    source: 'application',
    target: 'supervisor',
    type: 'manages',
    label: 'coordinates',
    strength: 0.7
  },
  {
    source: 'application',
    target: 'proc',
    type: 'manages',
    label: 'coordinates',
    strength: 0.6
  },
  {
    source: 'application',
    target: 'procref',
    type: 'uses',
    label: 'tracks services',
    strength: 0.5
  }
]

// Build relationship graph from primitives and relationships
export function buildRelationshipGraph(primitives: Primitive[]): RelationshipGraph {
  const nodes = new Map<string, PrimitiveNode>()
  const edges: RelationshipEdge[] = []

  // Initialize nodes
  for (const primitive of primitives) {
    nodes.set(primitive.id, {
      id: primitive.id,
      primitive,
      incomingEdges: [],
      outgoingEdges: []
    })
  }

  // Build edges from relationship mappings
  for (const rel of PRIMITIVE_RELATIONSHIPS) {
    const edgeId = `${rel.source}-${rel.target}-${rel.type}`
    const edge: RelationshipEdge = {
      id: edgeId,
      source: rel.source,
      target: rel.target,
      type: rel.type,
      label: rel.label,
      bidirectional: rel.bidirectional ?? false,
      strength: rel.strength
    }

    edges.push(edge)

    // Update node edges
    const sourceNode = nodes.get(rel.source)
    const targetNode = nodes.get(rel.target)

    if (sourceNode) {
      sourceNode.outgoingEdges.push(edge)
    }
    if (targetNode) {
      targetNode.incomingEdges.push(edge)
    }

    // Handle bidirectional
    if (rel.bidirectional) {
      const reverseEdgeId = `${rel.target}-${rel.source}-${rel.type}`
      const reverseEdge: RelationshipEdge = {
        id: reverseEdgeId,
        source: rel.target,
        target: rel.source,
        type: rel.type,
        label: rel.label,
        bidirectional: true,
        strength: rel.strength
      }

      edges.push(reverseEdge)

      if (targetNode) {
        targetNode.outgoingEdges.push(reverseEdge)
      }
      if (sourceNode) {
        sourceNode.incomingEdges.push(reverseEdge)
      }
    }
  }

  return { nodes, edges }
}

// Query functions
export function getRelationships(primitiveId: string): RelationshipEdge[] {
  // This would need access to the graph, placeholder for now
  return []
}

export function getIncomingRelationships(primitiveId: string): RelationshipEdge[] {
  // This would need access to the graph, placeholder for now
  return []
}

export function getOutgoingRelationships(primitiveId: string): RelationshipEdge[] {
  // This would need access to the graph, placeholder for now
  return []
}

export function getRelationshipsByType(type: RelationshipType): Array<{
  source: string
  target: string
  type: RelationshipType
  label?: string
  bidirectional?: boolean
  strength: number
}> {
  return PRIMITIVE_RELATIONSHIPS.filter(rel => rel.type === type)
}

export function getRelationshipInfo(type: RelationshipType) {
  return RELATIONSHIP_TYPES[type]
}

// Export relationship type guard
export function isValidRelationshipType(type: string): type is RelationshipType {
  return [
    'manages',
    'monitors',
    'links-to',
    'registers-with',
    'uses',
    'extends',
    'supervises',
    'spawned-by'
  ].includes(type)
}

// Helper to find shortest path between primitives (BFS)
export function findShortestPath(
  graph: RelationshipGraph,
  startId: string,
  endId: string
): string[] | null {
  if (startId === endId) return [startId]

  const visited = new Set<string>()
  const queue: Array<{ node: string; path: string[] }> = [
    { node: startId, path: [startId] }
  ]

  while (queue.length > 0) {
    const { node, path } = queue.shift()!

    if (node === endId) {
      return path
    }

    if (visited.has(node)) continue
    visited.add(node)

    const nodeData = graph.nodes.get(node)
    if (!nodeData) continue

    // Add all neighbors
    for (const edge of nodeData.outgoingEdges) {
      if (!visited.has(edge.target)) {
        queue.push({
          node: edge.target,
          path: [...path, edge.target]
        })
      }
    }
  }

  return null
}

// Helper to get all primitives reachable from a given primitive
export function getReachablePrimitives(
  graph: RelationshipGraph,
  startId: string,
  maxDepth: number = 3
): Set<string> {
  const reachable = new Set<string>()
  const visited = new Set<string>()
  const queue: Array<{ node: string; depth: number }> = [
    { node: startId, depth: 0 }
  ]

  while (queue.length > 0) {
    const { node, depth } = queue.shift()!

    if (depth > maxDepth) continue
    if (visited.has(node)) continue

    visited.add(node)
    reachable.add(node)

    const nodeData = graph.nodes.get(node)
    if (!nodeData) continue

    for (const edge of nodeData.outgoingEdges) {
      if (!visited.has(edge.target)) {
        queue.push({ node: edge.target, depth: depth + 1 })
      }
    }
  }

  return reachable
}

// Helper to get relationship strength between two primitives
export function getRelationshipStrength(
  source: string,
  target: string
): number {
  const rel = PRIMITIVE_RELATIONSHIPS.find(
    r => r.source === source && r.target === target
  )
  return rel?.strength ?? 0
}
