import { z } from 'zod'

export const ContentType = z.enum(['tutorial', 'how-to', 'reference', 'explanation'])

export const Difficulty = z.enum(['beginner', 'intermediate', 'advanced'])

export const DocFrontmatterSchema = z.object({
  title: z.string(),
  type: ContentType,
  description: z.string(),
  difficulty: Difficulty.optional(),
  duration: z.string().optional(),
  prerequisites: z.array(z.string()).optional(),
  tags: z.array(z.string()).optional(),
  lastUpdated: z.string(),
  author: z.string().optional(),
  seo: z.object({
    title: z.string(),
    description: z.string(),
    keywords: z.array(z.string()).optional(),
  }).optional(),
})

export type DocFrontmatter = z.infer<typeof DocFrontmatterSchema>

export type ContentType = z.infer<typeof ContentType>