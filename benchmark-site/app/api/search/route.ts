import { NextRequest, NextResponse } from 'next/server'

interface SearchResult {
  title: string
  path: string
  type: 'tutorial' | 'how-to' | 'reference' | 'explanation'
  excerpt: string
  score: number
}

interface SearchQuery {
  q: string
  type?: 'tutorial' | 'how-to' | 'reference' | 'explanation'
  limit?: number
}

// Mock data - in a real implementation, this would come from a database
const mockContent = [
  {
    title: 'Getting Started with JOTP',
    path: '/docs/tutorials/getting-started',
    type: 'tutorial' as const,
    content: 'Learn the fundamentals of JOTP and set up your first benchmark. JOTP brings Erlang/OTP patterns to Java 26 with full type safety.',
    embedding: [0.1, 0.2, 0.3, 0.4, 0.5] // Mock embedding
  },
  {
    title: 'First Benchmark Example',
    path: '/docs/tutorials/first-benchmark',
    type: 'tutorial' as const,
    content: 'Write your first JOTP benchmark from scratch. Create throughput benchmarks and analyze performance metrics.',
    embedding: [0.2, 0.3, 0.4, 0.5, 0.6]
  },
  {
    title: 'Writing Benchmark Tests',
    path: '/docs/how-to-guides/write-benchmark',
    type: 'how-to' as const,
    content: 'Step-by-step guide to creating effective benchmarks. Learn proper warmup, measurement, and analysis techniques.',
    embedding: [0.3, 0.4, 0.5, 0.6, 0.7]
  },
  {
    title: 'Proc API Reference',
    path: '/docs/reference/proc-api',
    type: 'reference' as const,
    content: 'Complete API documentation for the Process primitive. Spawn, link, monitor, and communicate with processes.',
    embedding: [0.4, 0.5, 0.6, 0.7, 0.8]
  },
  {
    title: 'Supervision Trees',
    path: '/docs/explanation/supervision-trees',
    type: 'explanation' as const,
    content: 'Understanding fault-tolerant supervision hierarchies. Learn how supervisors monitor and restart failed processes.',
    embedding: [0.5, 0.6, 0.7, 0.8, 0.9]
  }
]

// Simple cosine similarity calculation
function cosineSimilarity(a: number[], b: number[]): number {
  if (a.length !== b.length) return 0

  const dotProduct = a.reduce((sum, val, i) => sum + val * b[i], 0)
  const magnitudeA = Math.sqrt(a.reduce((sum, val) => sum + val * val, 0))
  const magnitudeB = Math.sqrt(b.reduce((sum, val) => sum + val * val, 0))

  return magnitudeA && magnitudeB ? dotProduct / (magnitudeA * magnitudeB) : 0
}

// Generate mock embedding for search query
function generateQueryEmbedding(query: string): number[] {
  // This is a simplified version - in production, use a proper embedding model
  const words = query.toLowerCase().split(/\s+/)
  const embedding = new Array(5).fill(0)

  words.forEach(word => {
    const hash = word.split('').reduce((acc, char) => acc + char.charCodeAt(0), 0)
    for (let i = 0; i < 5; i++) {
      embedding[i] += Math.sin(hash + i) / 10
    }
  })

  return embedding.map(val => Math.max(0, val))
}

export async function GET(request: NextRequest) {
  const { searchParams } = new URL(request.url)
  const query = searchParams.get('q')

  if (!query || query.trim() === '') {
    return NextResponse.json({ error: 'Search query is required' }, { status: 400 })
  }

  const searchQuery: SearchQuery = {
    q: query.trim(),
    type: searchParams.get('type') as any,
    limit: parseInt(searchParams.get('limit') || '10')
  }

  // Generate query embedding
  const queryEmbedding = generateQueryEmbedding(searchQuery.q)

  // Calculate similarity scores
  const results = mockContent
    .filter(item => !searchQuery.type || item.type === searchQuery.type)
    .map(item => ({
      title: item.title,
      path: item.path,
      type: item.type,
      excerpt: item.content.substring(0, 150) + '...',
      score: cosineSimilarity(item.embedding, queryEmbedding)
    }))
    .sort((a, b) => b.score - a.score)
    .slice(0, searchQuery.limit)

  return NextResponse.json({
    query: searchQuery.q,
    results,
    total: results.length,
    executionTime: Date.now()
  })
}