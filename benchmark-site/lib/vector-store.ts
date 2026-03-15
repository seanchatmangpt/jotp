/**
 * In-Memory Vector Store for JOTP Documentation RAG
 *
 * Uses cosine similarity for document retrieval.
 * Optimized for Groq's fast inference - we want retrieval to be equally fast.
 */

export interface DocumentChunk {
  id: string;
  text: string;
  embedding: number[];
  metadata: {
    source: string;
    section?: string;
    codeExample?: boolean;
    tags?: string[];
  };
}

export interface SimilarityResult {
  chunk: DocumentChunk;
  similarity: number;
}

/**
 * Simple in-memory vector store with cosine similarity search
 */
export class VectorStore {
  private chunks: Map<string, DocumentChunk> = new Map();
  private dimension: number;

  constructor(dimension: number = 1536) {
    this.dimension = dimension;
  }

  /**
   * Add a document chunk to the store
   */
  addChunk(chunk: DocumentChunk): void {
    if (chunk.embedding.length !== this.dimension) {
      throw new Error(
        `Embedding dimension mismatch: expected ${this.dimension}, got ${chunk.embedding.length}`
      );
    }
    this.chunks.set(chunk.id, chunk);
  }

  /**
   * Add multiple chunks in batch
   */
  addChunks(chunks: DocumentChunk[]): void {
    chunks.forEach(chunk => this.addChunk(chunk));
  }

  /**
   * Calculate cosine similarity between two vectors
   */
  private cosineSimilarity(a: number[], b: number[]): number {
    if (a.length !== b.length) {
      throw new Error('Vector dimensions must match');
    }

    let dotProduct = 0;
    let normA = 0;
    let normB = 0;

    for (let i = 0; i < a.length; i++) {
      dotProduct += a[i] * b[i];
      normA += a[i] * b[i];
      normB += b[i] * b[i];
    }

    return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  /**
   * Find most similar chunks to a query embedding
   * @param queryEmbedding - The embedding vector for the query
   * @param topK - Number of results to return (default: 5)
   * @param threshold - Minimum similarity score (default: 0.7)
   */
  search(
    queryEmbedding: number[],
    topK: number = 5,
    threshold: number = 0.7
  ): SimilarityResult[] {
    if (queryEmbedding.length !== this.dimension) {
      throw new Error(
        `Query embedding dimension mismatch: expected ${this.dimension}, got ${queryEmbedding.length}`
      );
    }

    const results: SimilarityResult[] = [];

    for (const chunk of this.chunks.values()) {
      const similarity = this.cosineSimilarity(queryEmbedding, chunk.embedding);
      if (similarity >= threshold) {
        results.push({ chunk, similarity });
      }
    }

    // Sort by similarity descending and return top K
    return results.sort((a, b) => b.similarity - a.similarity).slice(0, topK);
  }

  /**
   * Get all chunks (for debugging/export)
   */
  getAllChunks(): DocumentChunk[] {
    return Array.from(this.chunks.values());
  }

  /**
   * Clear all chunks
   */
  clear(): void {
    this.chunks.clear();
  }

  /**
   * Get the number of stored chunks
   */
  size(): number {
    return this.chunks.size;
  }

  /**
   * Get chunk by ID
   */
  getChunk(id: string): DocumentChunk | undefined {
    return this.chunks.get(id);
  }
}

/**
 * Global singleton instance
 */
let globalStore: VectorStore | null = null;

export function getVectorStore(): VectorStore {
  if (!globalStore) {
    globalStore = new VectorStore();
  }
  return globalStore;
}

export function resetVectorStore(): void {
  globalStore = null;
}
