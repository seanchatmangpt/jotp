'use client'

import { useState } from 'react'
import { BookOpenIcon, ArrowRightIcon } from '@heroicons/react/24/outline'
import { Link } from '@/components/ui/link'
import { Card } from '@radix-ui/themes'
import { ContentTypeBadge } from './content-type-badge'

interface RelatedContentProps {
  currentPath: string
  className?: string
}

interface RelatedItem {
  title: string
  path: string
  type: 'tutorial' | 'how-to' | 'reference' | 'explanation'
  description: string
  relevance: number
}

export function RelatedContent({ currentPath, className }: RelatedContentProps) {
  const [selectedItem, setSelectedItem] = useState<number | null>(null)

  // Mock related content - in production, this would be determined by content analysis
  const relatedItems: RelatedItem[] = [
    {
      title: 'First Benchmark Example',
      path: '/docs/tutorials/first-benchmark',
      type: 'tutorial' as const,
      description: 'Write your first JOTP benchmark from scratch',
      relevance: 0.9
    },
    {
      title: 'Writing Benchmark Tests',
      path: '/docs/how-to-guides/write-benchmark',
      type: 'how-to' as const,
      description: 'Step-by-step guide to creating effective benchmarks',
      relevance: 0.85
    },
    {
      title: 'Supervision Trees',
      path: '/docs/explanation/supervision-trees',
      type: 'explanation' as const,
      description: 'Understanding fault-tolerant supervision hierarchies',
      relevance: 0.7
    },
    {
      title: 'Proc API Reference',
      path: '/docs/reference/proc-api',
      type: 'reference' as const,
      description: 'Complete API documentation for the Process primitive',
      relevance: 0.65
    }
  ].sort((a, b) => b.relevance - a.relevance)

  const getTypeColor = (type: string) => {
    switch (type) {
      case 'tutorial': return 'text-purple-600 bg-purple-50'
      case 'how-to': return 'text-blue-600 bg-blue-50'
      case 'reference': return 'text-green-600 bg-green-50'
      case 'explanation': return 'text-orange-600 bg-orange-50'
      default: return 'text-gray-600 bg-gray-50'
    }
  }

  return (
    <div className={`space-y-4 ${className}`}>
      <div className="flex items-center gap-2 mb-4">
        <BookOpenIcon className="w-5 h-5 text-gray-600" />
        <h3 className="font-semibold text-gray-900">Related Content</h3>
      </div>

      <div className="space-y-3">
        {relatedItems.map((item, index) => (
          <div
            key={item.path}
            onMouseEnter={() => setSelectedItem(index)}
            onMouseLeave={() => setSelectedItem(null)}
          >
            <Link href={item.path} className="block">
              <Card
                className={`transition-all duration-200 ${
                  selectedItem === index
                    ? 'shadow-md border-blue-200 bg-blue-50'
                    : 'hover:shadow-md hover:border-gray-300'
                }`}
              >
              <CardContent className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-2 mb-1">
                      <h4 className="font-medium text-gray-900">
                        {item.title}
                      </h4>
                      <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${getTypeColor(item.type)}`}>
                        <ContentTypeBadge size="sm" type={item.type} />
                      </span>
                    </div>
                    <p className="text-sm text-gray-600 line-clamp-2">
                      {item.description}
                    </p>
                    <div className="flex items-center gap-2 mt-2">
                      <div className="flex items-center gap-1">
                        <div className="w-full bg-gray-200 rounded-full h-1.5">
                          <div
                            className="bg-blue-600 h-1.5 rounded-full transition-all duration-300"
                            style={{ width: `${item.relevance * 100}%` }}
                          />
                        </div>
                        <span className="text-xs text-gray-500 ml-2">
                          {Math.round(item.relevance * 100)}%
                        </span>
                      </div>
                    </div>
                  </div>
                  <ArrowRightIcon
                    className={`w-4 h-4 ml-3 transition-transform duration-200 ${
                      selectedItem === index ? 'translate-x-1 text-blue-600' : 'text-gray-400'
                    }`}
                  />
                </div>
              </CardContent>
            </Card>
            </Link>
          </div>
        ))}
      </div>

      <div className="pt-4 border-t border-gray-200">
        <div className="flex items-center justify-between">
          <p className="text-sm text-gray-500">
            Based on your current document
          </p>
          <Link
            href="/docs"
            className="text-sm text-blue-600 hover:text-blue-700 font-medium"
          >
            View all docs
          </Link>
        </div>
      </div>
    </div>
  )
}