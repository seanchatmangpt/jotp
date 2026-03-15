/**
 * JOTP Documentation Search API (RAG)
 *
 * Fast RAG search powered by:
 * - OpenAI embeddings for semantic search
 * - Groq LPU for ultra-fast answer generation
 *
 * Target: <500ms total response time
 */

import { NextRequest, NextResponse } from 'next/server';
import { groq } from '@ai-sdk/groq';
import { openai } from '@ai-sdk/openai';
import { streamText, embed } from 'ai';
import { getVectorStore } from '@/lib/vector-store';
import { promises as fs } from 'fs';
import path from 'path';
import { processMarkdownDoc, createDocumentChunks } from '@/lib/docs-embeddings';
import { getGroqAPIKey, getOpenAIAPIKey } from '@/lib/api-keys';

export const maxDuration = 30;

// Simple in-memory cache for embeddings
const embeddingCache = new Map<string, number[]>();

/**
 * Get or create embedding for text
 */
async function getEmbedding(text: string): Promise<number[]> {
  // Check cache first
  const cacheKey = text.slice(0, 200); // Simple cache key
  if (embeddingCache.has(cacheKey)) {
    return embeddingCache.get(cacheKey)!;
  }

  // Generate embedding using OpenAI
  const { embedding } = await embed({
    model: openai.embedding('text-embedding-3-small', {
      apiKey: getOpenAIAPIKey(),
    }),
    value: text,
  });

  embeddingCache.set(cacheKey, embedding);
  return embedding;
}

/**
 * Ensure docs are loaded and embedded
 */
async function ensureDocsIndexed() {
  const vectorStore = getVectorStore();

  // If already indexed, return
  if (vectorStore.size() > 0) {
    return vectorStore;
  }

  // Auto-index on first search
  const DOC_FILES = [
    { relativePath: '../../.claude/ARCHITECTURE.md', name: 'ARCHITECTURE.md' },
    { relativePath: '../../.claude/INTEGRATION-PATTERNS.md', name: 'INTEGRATION-PATTERNS.md' },
    { relativePath: '../../.claude/SLA-PATTERNS.md', name: 'SLA-PATTERNS.md' },
  ];

  for (const docFile of DOC_FILES) {
    try {
      const filePath = path.join(__dirname, docFile.relativePath);
      const content = await fs.readFile(filePath, 'utf-8');
      const processed = processMarkdownDoc(content, docFile.name);
      const chunksWithoutEmbeddings = createDocumentChunks(processed.sections, docFile.name);

      // Generate embeddings in batch
      for (const chunk of chunksWithoutEmbeddings) {
        const embedding = await getEmbedding(chunk.text);
        vectorStore.addChunk({
          ...chunk,
          embedding,
        });
      }
    } catch (error) {
      console.error(`Failed to index ${docFile.name}:`, error);
    }
  }

  return vectorStore;
}

/**
 * POST /api/docs-search
 *
 * Search JOTP documentation and generate AI-powered answers.
 * Response includes source citations.
 */
export async function POST(req: NextRequest) {
  try {
    // Validate API keys
    try {
      getGroqAPIKey();
      getOpenAIAPIKey();
    } catch (error) {
      return NextResponse.json(
        {
          error: 'API keys not configured',
          details: error instanceof Error ? error.message : 'Unknown error',
        },
        { status: 500 }
      );
    }

    const { query } = await req.json();

    if (!query || typeof query !== 'string') {
      return NextResponse.json(
        { error: 'Query is required' },
        { status: 400 }
      );
    }

    // Step 1: Generate query embedding
    const queryEmbedding = await getEmbedding(query);

    // Step 2: Ensure docs are indexed
    const vectorStore = await ensureDocsIndexed();

    // Step 3: Search for relevant chunks
    const searchResults = vectorStore.search(queryEmbedding, 5, 0.6);

    if (searchResults.length === 0) {
      // Fallback to general Groq chat if no docs found
      const result = await streamText({
        model: groq('openai/gpt-oss-20b', {
          apiKey: getGroqAPIKey(),
        }),
        messages: [
          {
            role: 'system',
            content: `You are a helpful JOTP (Java OTP) documentation assistant.
Help users understand JOTP primitives, patterns, and fault tolerance concepts.
Be concise. Use code examples when helpful.
If you're not sure about something, say so honestly.`,
          },
          {
            role: 'user',
            content: query,
          },
        ],
      });

      return result.toTextStreamResponse();
    }

    // Step 4: Build context from search results
    const context = searchResults
      .map(
        (r, i) =>
          `[${i + 1}] ${r.chunk.metadata.section}\n${r.chunk.text}`
      )
      .join('\n\n');

    const sources = searchResults.map((r) => ({
      section: r.chunk.metadata.section,
      source: r.chunk.metadata.source,
      similarity: r.similarity.toFixed(3),
    }));

    // Step 5: Generate answer using Groq LPU (fast!)
    const result = await streamText({
      model: groq('openai/gpt-oss-20b', {
        apiKey: getGroqAPIKey(),
      }),
      messages: [
        {
          role: 'system',
          content: `You are a JOTP (Java OTP) documentation assistant.
Answer questions based on the provided context from JOTP documentation.

Guidelines:
- Be concise and direct
- Use code examples from the context when relevant
- Cite sources using [1], [2] notation
- If the context doesn't contain the answer, say so
- Focus on practical implementation details

Context from JOTP docs:
${context}`,
        },
        {
          role: 'user',
          content: query,
        },
      ],
    });

    // Create a custom stream that includes sources
    const stream = result.toTextStreamResponse();
    const headers = new Headers(stream.headers);
    headers.set('X-Sources', JSON.stringify(sources));

    return new Response(stream.body, {
      status: stream.status,
      headers,
    });
  } catch (error) {
    console.error('Docs search error:', error);
    return NextResponse.json(
      {
        error: 'Search failed',
        details: error instanceof Error ? error.message : 'Unknown error',
      },
      { status: 500 }
    );
  }
}

/**
 * GET /api/docs-search
 *
 * Health check for the search service.
 */
export async function GET() {
  const vectorStore = getVectorStore();

  const apiKeys = {
    groq: !!process.env.GROQ_API_KEY,
    openai: !!process.env.OPENAI_API_KEY,
  };

  return NextResponse.json({
    status: apiKeys.groq && apiKeys.openai ? 'ready' : 'missing-keys',
    indexed: vectorStore.size() > 0,
    chunkCount: vectorStore.size(),
    model: 'groq openai/gpt-oss-20b',
    embedding: 'openai text-embedding-3-small',
    apiKeys,
  });
}
