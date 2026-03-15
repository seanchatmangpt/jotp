/**
 * JOTP Documentation Embeddings Processor
 *
 * Handles parsing, chunking, and embedding of JOTP documentation
 * for RAG-based chat responses.
 */

import { DocumentChunk } from './vector-store';

export interface DocSection {
  title: string;
  content: string;
  level: number; // 1 for #, 2 for ##, etc.
  source: string;
}

export interface ProcessedDoc {
  source: string;
  sections: DocSection[];
  chunks: DocumentChunk[];
}

/**
 * Parse a markdown document into sections
 */
export function parseMarkdownDoc(
  content: string,
  source: string
): DocSection[] {
  const lines = content.split('\n');
  const sections: DocSection[] = [];
  let currentSection: Partial<DocSection> | null = null;
  let currentContent: string[] = [];

  for (const line of lines) {
    // Check for headers
    const headerMatch = line.match(/^(#{1,6})\s+(.+)$/);
    if (headerMatch) {
      // Save previous section
      if (currentSection && currentContent.length > 0) {
        sections.push({
          title: currentSection.title!,
          content: currentContent.join('\n').trim(),
          level: currentSection.level!,
          source,
        });
      }

      // Start new section
      currentSection = {
        title: headerMatch[2],
        level: headerMatch[1].length,
        source,
      };
      currentContent = [];
    } else if (currentSection) {
      currentContent.push(line);
    }
  }

  // Save last section
  if (currentSection && currentContent.length > 0) {
    sections.push({
      title: currentSection.title,
      content: currentContent.join('\n').trim(),
      level: currentSection.level,
      source,
    });
  }

  return sections;
}

/**
 * Split text into chunks with overlap for better context preservation
 */
export function chunkText(
  text: string,
  maxChunkSize: number = 500,
  overlap: number = 50
): string[] {
  if (text.length <= maxChunkSize) {
    return [text];
  }

  const chunks: string[] = [];
  let start = 0;

  while (start < text.length) {
    const end = Math.min(start + maxChunkSize, text.length);
    let chunkEnd = end;

    // Try to break at a sentence boundary
    if (end < text.length) {
      const lastPeriod = text.lastIndexOf('.', end);
      const lastNewline = text.lastIndexOf('\n', end);
      const breakPoint = Math.max(lastPeriod, lastNewline);

      if (breakPoint > start + maxChunkSize / 2) {
        chunkEnd = breakPoint + 1;
      }
    }

    chunks.push(text.slice(start, chunkEnd).trim());
    start = chunkEnd - overlap;
  }

  return chunks;
}

/**
 * Create document chunks with metadata
 */
export function createDocumentChunks(
  sections: DocSection[],
  source: string
): Omit<DocumentChunk, 'embedding'>[] {
  const chunks: Omit<DocumentChunk, 'embedding'>[] = [];
  let chunkIndex = 0;

  for (const section of sections) {
    // Skip empty sections
    if (!section.content.trim()) continue;

    const textChunks = chunkText(section.content);

    for (const textChunk of textChunks) {
      chunks.push({
        id: `${source}-${section.title}-${chunkIndex}`.replace(/[^a-zA-Z0-9-]/g, '-'),
        text: textChunk,
        metadata: {
          source,
          section: section.title,
          codeExample: textChunk.includes('```'),
          tags: extractTags(section.title, textChunk),
        },
      });
      chunkIndex++;
    }
  }

  return chunks;
}

/**
 * Extract relevant tags from content
 */
function extractTags(title: string, content: string): string[] {
  const tags: string[] = [];
  const lowerTitle = title.toLowerCase();
  const lowerContent = content.toLowerCase();

  // OTP primitives
  const primitives = [
    'proc', 'supervisor', 'statemachine', 'parallel',
    'procmonitor', 'procregistry', 'proctimer', 'eventmanager',
    'crashrecovery', 'proc.link', 'proc.trapexits'
  ];

  for (const primitive of primitives) {
    if (lowerContent.includes(primitive) || lowerTitle.includes(primitive)) {
      tags.push(primitive);
    }
  }

  // Patterns
  const patterns = [
    'circuit breaker', 'bulkhead', 'backpressure',
    'crash recovery', 'multi-tenancy', 'saga'
  ];

  for (const pattern of patterns) {
    if (lowerContent.includes(pattern) || lowerTitle.includes(pattern)) {
      tags.push(pattern);
    }
  }

  // Concepts
  const concepts = [
    'fault tolerance', 'supervision tree', 'actor model',
    'virtual threads', 'sla', 'latency', 'throughput'
  ];

  for (const concept of concepts) {
    if (lowerContent.includes(concept)) {
      tags.push(concept);
    }
  }

  return tags;
}

/**
 * Process a complete markdown document
 */
export function processMarkdownDoc(
  content: string,
  source: string
): ProcessedDoc {
  const sections = parseMarkdownDoc(content, source);
  const chunksWithoutEmbeddings = createDocumentChunks(sections, source);

  return {
    source,
    sections,
    chunks: chunksWithoutEmbeddings.map(c => ({
      ...c,
      embedding: [], // Will be populated by the embedding service
    })),
  };
}

/**
 * Merge processed docs for multi-source indexing
 */
export function mergeProcessedDocs(docs: ProcessedDoc[]): ProcessedDoc {
  return {
    source: 'merged',
    sections: docs.flatMap(d => d.sections),
    chunks: docs.flatMap(d => d.chunks),
  };
}
