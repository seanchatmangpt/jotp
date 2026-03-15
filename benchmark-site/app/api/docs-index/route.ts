/**
 * JOTP Documentation Indexing API
 *
 * Processes and indexes JOTP documentation for RAG search.
 * This endpoint should be called when documentation changes.
 */

import { NextRequest, NextResponse } from 'next/server';
import { promises as fs } from 'fs';
import path from 'path';
import { processMarkdownDoc } from '@/lib/docs-embeddings';
import { getVectorStore, DocumentChunk } from '@/lib/vector-store';
import { openai } from '@ai-sdk/openai';
import { embed } from 'ai';

// List of documentation files to index
const DOC_FILES = [
  { relativePath: '../../.claude/ARCHITECTURE.md', name: 'ARCHITECTURE.md' },
  { relativePath: '../../.claude/INTEGRATION-PATTERNS.md', name: 'INTEGRATION-PATTERNS.md' },
  { relativePath: '../../.claude/SLA-PATTERNS.md', name: 'SLA-PATTERNS.md' },
];

export const maxDuration = 60; // Allow up to 60 seconds for indexing

/**
 * Generate embeddings for a text chunk
 */
async function generateEmbedding(text: string): Promise<number[]> {
  const { embedding } = await embed({
    model: openai.embedding('text-embedding-3-small'),
    value: text,
  });
  return embedding;
}

/**
 * POST /api/docs-index
 *
 * Indexes all JOTP documentation files.
 * Returns summary of indexed documents.
 */
export async function POST(req: NextRequest) {
  try {
    const vectorStore = getVectorStore();
    vectorStore.clear();

    const results: {
      file: string;
      sections: number;
      chunks: number;
      status: string;
    }[] = [];

    let totalChunks = 0;

    // Process each documentation file
    for (const docFile of DOC_FILES) {
      try {
        const filePath = path.join(__dirname, docFile.relativePath);
        const content = await fs.readFile(filePath, 'utf-8');
        const processed = processMarkdownDoc(content, docFile.name);

        // Generate embeddings for all chunks
        const chunksWithEmbeddings: DocumentChunk[] = [];

        for (const chunk of processed.chunks) {
          try {
            const embedding = await generateEmbedding(chunk.text);
            chunksWithEmbeddings.push({
              ...chunk,
              embedding,
            });
          } catch (embedError) {
            console.error(`Failed to embed chunk ${chunk.id}:`, embedError);
            // Add chunk with empty embedding (will be skipped in search)
            chunksWithEmbeddings.push({
              ...chunk,
              embedding: [],
            });
          }
        }

        // Add all chunks to vector store
        vectorStore.addChunks(chunksWithEmbeddings);

        results.push({
          file: docFile.name,
          sections: processed.sections.length,
          chunks: chunksWithEmbeddings.length,
          status: 'indexed',
        });

        totalChunks += chunksWithEmbeddings.length;
      } catch (error) {
        console.error(`Failed to index ${docFile.name}:`, error);
        results.push({
          file: docFile.name,
          sections: 0,
          chunks: 0,
          status: `error: ${error instanceof Error ? error.message : 'unknown'}`,
        });
      }
    }

    return NextResponse.json({
      success: true,
      indexed: results,
      totalSections: results.reduce((sum, r) => sum + r.sections, 0),
      totalChunks,
      message: `Successfully indexed ${totalChunks} chunks from ${DOC_FILES.length} documents`,
    });
  } catch (error) {
    console.error('Indexing error:', error);
    return NextResponse.json(
      {
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error',
      },
      { status: 500 }
    );
  }
}

/**
 * GET /api/docs-index
 *
 * Returns current indexing status.
 */
export async function GET() {
  const vectorStore = getVectorStore();

  return NextResponse.json({
    indexed: vectorStore.size() > 0,
    chunkCount: vectorStore.size(),
    files: DOC_FILES.map(f => f.name),
  });
}
