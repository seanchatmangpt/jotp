export interface SearchResult {
  title: string
  path: string
  type: 'tutorial' | 'how-to' | 'reference' | 'explanation'
  excerpt: string
  score: number
}

export interface SearchResults {
  query: string
  results: SearchResult[]
  total: number
  executionTime: number
}