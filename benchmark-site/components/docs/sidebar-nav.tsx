'use client'

import { useState } from 'react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { ContentType } from '@/lib/content-schema'
import { ContentTypeBadge } from './content-type-badge'
import { FolderOpen, Folder } from 'lucide-react'

interface DocNode {
  title: string
  type: ContentType
  path: string
  children?: DocNode[]
}

const contentSections: { [key in ContentType]: { title: string; icon: string; description: string } } = {
  tutorial: {
    title: 'Tutorials',
    icon: '🎓',
    description: 'Step-by-step learning guides',
  },
  'how-to': {
    title: 'How-To Guides',
    icon: '📋',
    description: 'Practical step-by-step instructions',
  },
  reference: {
    title: 'Reference',
    icon: '📚',
    description: 'API documentation and technical details',
  },
  explanation: {
    title: 'Explanations',
    icon: '🔍',
    description: 'Conceptual understanding and patterns',
  },
}

const contentTree: DocNode[] = [
  {
    title: 'Getting Started',
    type: 'tutorial',
    path: '/docs/tutorials/getting-started',
    children: [
      {
        title: 'Introduction to JOTP',
        type: 'tutorial',
        path: '/docs/tutorials/getting-started',
      },
      {
        title: 'First Benchmark',
        type: 'tutorial',
        path: '/docs/tutorials/first-benchmark',
      },
    ],
  },
  {
    title: 'Writing Benchmarks',
    type: 'how-to',
    path: '/docs/how-to-guides/write-benchmark',
    children: [
      {
        title: 'Write Your First Benchmark',
        type: 'how-to',
        path: '/docs/how-to-guides/write-benchmark',
      },
      {
        title: 'Configure Supervisors',
        type: 'how-to',
        path: '/docs/how-to-guides/configure-supervisors',
      },
      {
        title: 'Analyze Results',
        type: 'how-to',
        path: '/docs/how-to-guides/analyze-results',
      },
    ],
  },
  {
    title: 'API Reference',
    type: 'reference',
    path: '/docs/reference/proc-api',
    children: [
      {
        title: 'Proc API',
        type: 'reference',
        path: '/docs/reference/proc-api',
      },
      {
        title: 'Supervisor API',
        type: 'reference',
        path: '/docs/reference/supervisor-api',
      },
      {
        title: 'StateMachine API',
        type: 'reference',
        path: '/docs/reference/statemachine-api',
      },
    ],
  },
  {
    title: 'Patterns & Concepts',
    type: 'explanation',
    path: '/docs/explanation/supervision-trees',
    children: [
      {
        title: 'Supervision Trees',
        type: 'explanation',
        path: '/docs/explanation/supervision-trees',
      },
      {
        title: 'Fault Tolerance',
        type: 'explanation',
        path: '/docs/explanation/fault-tolerance',
      },
      {
        title: 'Let It Crash',
        type: 'explanation',
        path: '/docs/explanation/let-it-crash',
      },
    ],
  },
]

interface SidebarNavProps {
  className?: string
}

export function SidebarNav({ className }: SidebarNavProps) {
  const pathname = usePathname()
  const [expandedSections, setExpandedSections] = useState<{[key in ContentType]: boolean}>({
    tutorial: true,
    'how-to': true,
    reference: true,
    explanation: false,
  })

  const toggleSection = (type: ContentType) => {
    setExpandedSections(prev => ({ ...prev, [type]: !prev[type] }))
  }

  const isActive = (path: string) => {
    return pathname === path || pathname.startsWith(path + '/')
  }

  return (
    <nav className={`w-64 bg-white border-r border-gray-200 h-full ${className}`}>
      <div className="p-6">
        <h1 className="text-2xl font-bold text-gray-900">JOTP Docs</h1>
        <p className="text-gray-600 mt-2">Fault-tolerant Java 26</p>
      </div>

      <div className="px-4 pb-4">
        {Object.entries(contentSections).map(([type, section]) => (
          <div key={type} className="mb-6">
            <button
              onClick={() => toggleSection(type as ContentType)}
              className="w-full flex items-center justify-between p-3 rounded-lg hover:bg-gray-50 transition-colors"
            >
              <div className="flex items-center gap-3">
                <span className="text-xl">{section.icon}</span>
                <div>
                  <h2 className="font-semibold text-gray-900">{section.title}</h2>
                  <p className="text-sm text-gray-500">{section.description}</p>
                </div>
              </div>
              {expandedSections[type as ContentType] ? (
                <FolderOpen className="w-5 h-5 text-gray-400" />
              ) : (
                <Folder className="w-5 h-5 text-gray-400" />
              )}
            </button>

            {expandedSections[type as ContentType] && (
              <div className="ml-8 mt-2 space-y-1">
                {contentTree
                  .filter(node => node.type === type)
                  .map(node => (
                    <Link
                      key={node.path}
                      href={node.path}
                      className={`block p-2 rounded-md transition-colors ${
                        isActive(node.path)
                          ? 'bg-blue-50 text-blue-700 font-medium'
                          : 'text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      <div className="flex items-center justify-between">
                        <span>{node.title}</span>
                        <ContentTypeBadge size="sm" type={node.type} />
                      </div>
                    </Link>
                  ))}
              </div>
            )}
          </div>
        ))}
      </div>

      <div className="px-4 py-6 border-t border-gray-200">
        <p className="text-sm text-gray-500">
          Type-safe documentation powered by Diátaxis
        </p>
      </div>
    </nav>
  )
}