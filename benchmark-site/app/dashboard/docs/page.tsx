'use client'

import { useState, useEffect } from 'react'
import { Card, Tabs, Box, Heading, Text, Badge, Flex, Grid, Button } from '@radix-ui/themes'
import { SidebarNav } from '@/components/docs/sidebar-nav'
import { ContentTypeBadge } from '@/components/docs/content-type-badge'
import { Breadcrumbs } from '@/components/docs/breadcrumbs'
import Link from '@/components/ui/link'
import { ContentType } from '@/lib/content-schema'

interface DocCard {
  title: string
  description: string
  type: ContentType
  difficulty?: 'beginner' | 'intermediate' | 'advanced'
  duration?: string
  path: string
  tags?: string[]
  lastUpdated: string
}

export default function DocsPage() {
  const [activeTab, setActiveTab] = useState('all')

  const contentData: Record<string, DocCard[]> = {
    tutorials: [
      {
        title: 'Getting Started with JOTP',
        description: 'Learn the fundamentals of JOTP and set up your first fault-tolerant process',
        type: 'tutorial',
        difficulty: 'beginner',
        duration: '20 min',
        path: '/docs/tutorials/getting-started',
        tags: ['beginner', 'setup'],
        lastUpdated: '2024-01-15',
      },
      {
        title: 'First Benchmark Example',
        description: 'Write your first JOTP benchmark from scratch with observability integration',
        type: 'tutorial',
        difficulty: 'beginner',
        duration: '30 min',
        path: '/docs/tutorials/first-benchmark',
        tags: ['beginner', 'benchmark'],
        lastUpdated: '2024-01-15',
      },
      {
        title: 'Observability Integration',
        description: 'Learn how to integrate OpenTelemetry with your JOTP applications',
        type: 'tutorial',
        difficulty: 'intermediate',
        duration: '45 min',
        path: '/docs/tutorials/observability-integration',
        tags: ['observability', 'advanced'],
        lastUpdated: '2024-01-15',
      },
    ],
    'how-to-guides': [
      {
        title: 'Writing Benchmark Tests',
        description: 'Step-by-step guide to creating effective performance benchmarks',
        type: 'how-to',
        difficulty: 'intermediate',
        duration: '30 min',
        path: '/docs/how-to-guides/write-benchmark',
        tags: ['performance', 'testing'],
        lastUpdated: '2024-01-15',
      },
      {
        title: 'Configuring Supervisors',
        description: 'Learn how to configure supervision trees for fault tolerance',
        type: 'how-to',
        difficulty: 'advanced',
        duration: '45 min',
        path: '/docs/how-to-guides/configure-supervisors',
        tags: ['supervision', 'fault-tolerance'],
        lastUpdated: '2024-01-15',
      },
      {
        title: 'Analyzing Results',
        description: 'Understand and interpret benchmark metrics and reports',
        type: 'how-to',
        difficulty: 'intermediate',
        duration: '25 min',
        path: '/docs/how-to-guides/analyze-results',
        tags: ['metrics', 'analysis'],
        lastUpdated: '2024-01-15',
      },
      {
        title: 'Setting Up Development Environment',
        description: 'Configure your IDE and build tools for JOTP development',
        type: 'how-to',
        difficulty: 'beginner',
        duration: '15 min',
        path: '/docs/how-to-guides/setup-environment',
        tags: ['setup', 'tooling'],
        lastUpdated: '2024-01-15',
      },
    ],
    reference: [
      {
        title: 'Proc API Reference',
        description: 'Complete API documentation for the Process primitive',
        type: 'reference',
        difficulty: 'advanced',
        path: '/docs/reference/proc-api',
        tags: ['api', 'core'],
        lastUpdated: '2024-01-15',
      },
      {
        title: 'Supervisor API Reference',
        description: 'Build and manage supervision trees',
        type: 'reference',
        difficulty: 'advanced',
        path: '/docs/reference/supervisor-api',
        tags: ['api', 'supervision'],
        lastUpdated: '2024-01-15',
      },
      {
        title: 'StateMachine API Reference',
        description: 'State machine implementation details and examples',
        type: 'reference',
        difficulty: 'advanced',
        path: '/docs/reference/statemachine-api',
        tags: ['api', 'state-machine'],
        lastUpdated: '2024-01-15',
      },
      {
        title: 'JOTP Configuration',
        description: 'Configuration options and best practices',
        type: 'reference',
        difficulty: 'intermediate',
        path: '/docs/reference/configuration',
        tags: ['configuration', 'best-practices'],
        lastUpdated: '2024-01-15',
      },
    ],
    explanation: [
      {
        title: 'Supervision Trees',
        description: 'Understanding fault-tolerant supervision hierarchies',
        type: 'explanation',
        difficulty: 'intermediate',
        duration: '25 min',
        path: '/docs/explanation/supervision-trees',
        tags: ['patterns', 'fault-tolerance'],
        lastUpdated: '2024-01-15',
      },
      {
        title: 'Fault Tolerance Patterns',
        description: 'Learn about fault tolerance in distributed systems',
        type: 'explanation',
        difficulty: 'intermediate',
        duration: '30 min',
        path: '/docs/explanation/fault-tolerance',
        tags: ['patterns', 'distributed'],
        lastUpdated: '2024-01-15',
      },
      {
        title: 'Let It Crash Philosophy',
        description: 'Understanding the "let it crash" design principle',
        type: 'explanation',
        difficulty: 'beginner',
        duration: '20 min',
        path: '/docs/explanation/let-it-crash',
        tags: ['philosophy', 'design'],
        lastUpdated: '2024-01-15',
      },
      {
        title: 'Virtual Threads in JOTP',
        description: 'How virtual threads enable high-performance OTP patterns',
        type: 'explanation',
        difficulty: 'advanced',
        duration: '35 min',
        path: '/docs/explanation/virtual-threads',
        tags: ['performance', 'concurrency'],
        lastUpdated: '2024-01-15',
      },
    ],
  }

  const allDocs = Object.values(contentData).flat()
  const filteredDocs = activeTab === 'all'
    ? allDocs
    : contentData[activeTab] || []

  return (
    <Box p="8">
      <SidebarNav />
      <Flex direction="column" gap="8">
        <Box>
          <Breadcrumbs items={[{ label: 'Documentation Overview' }]} />
        </Box>

        <Box>
          <Flex justify="between" align="start">
            <Flex direction="column" gap="4">
              <Heading size="8">
                JOTP Documentation
              </Heading>
              <Text size="5" color="gray">
                Comprehensive guides for fault-tolerant Java 26 with virtual threads
              </Text>
            </Flex>
            <Flex gap="2">
              {/* Search and AI Assistant components temporarily disabled */}
              <Text size="2" color="gray">
                Search and AI features coming soon
              </Text>
            </Flex>
          </Flex>
        </Box>

        <Tabs.Root value={activeTab} onValueChange={setActiveTab}>
          <Tabs.List mb="6">
            <Tabs.Trigger value="all">All ({allDocs.length})</Tabs.Trigger>
            <Tabs.Trigger value="tutorials">Tutorials ({contentData.tutorials.length})</Tabs.Trigger>
            <Tabs.Trigger value="how-to-guides">How-to ({contentData['how-to-guides'].length})</Tabs.Trigger>
            <Tabs.Trigger value="reference">Reference ({contentData.reference.length})</Tabs.Trigger>
            <Tabs.Trigger value="explanation">Explanation ({contentData.explanation.length})</Tabs.Trigger>
          </Tabs.List>

          <Box mt="6">
            <Grid columns={{ initial: '1', md: '2', lg: '3' }} gap="6">
              {filteredDocs.map((doc, index) => (
                <Card
                  key={index}
                  style={{ cursor: 'pointer' }}
                  className="hover:shadow-lg transition-all duration-200 group"
                >
                  <Flex direction="column" gap="4" p="4">
                    <Flex justify="between" align="start">
                      <Flex direction="column" gap="2">
                        <Flex align="center" gap="2">
                          <Heading size="5" className="group-hover:text-blue-600 transition-colors">
                            {doc.title}
                          </Heading>
                          <ContentTypeBadge type={doc.type} />
                        </Flex>
                        <Text color="gray" size="2">
                          {doc.description}
                        </Text>
                      </Flex>
                    </Flex>

                    <Flex direction="column" gap="3">
                      {doc.tags && (
                        <Flex gap="1" wrap="wrap">
                          {doc.tags.map(tag => (
                            <Badge key={tag} variant="soft" size="1">
                              {tag}
                            </Badge>
                          ))}
                        </Flex>
                      )}

                      <Flex justify="between" align="center">
                        <Flex gap="2" align="center">
                          <Text color="gray">
                            {doc.duration && <span>{doc.duration}</span>}
                            {doc.duration && doc.difficulty && <Text>•</Text>}
                            {doc.difficulty && (
                              <Text color={
                                doc.difficulty === 'beginner' ? 'green' :
                                doc.difficulty === 'intermediate' ? 'yellow' :
                                'red'
                              }>
                                {doc.difficulty}
                              </Text>
                            )}
                            <Text>•</Text>
                            <Text>{new Date(doc.lastUpdated).toLocaleDateString()}</Text>
                          </Text>
                        </Flex>

                        <Link href={doc.path}>
                          <Button variant="outline" size="2" className="group-hover:bg-blue-50">
                            Read
                          </Button>
                        </Link>
                      </Flex>
                    </Flex>
                  </Flex>
                </Card>
              ))}
            </Grid>
          </Box>
        </Tabs.Root>

        {filteredDocs.length === 0 && (
          <Box py="12">
            <Text align="center" color="gray">No content found. Try searching or ask our AI assistant.</Text>
          </Box>
        )}
      </Flex>
    </Box>
  )
}
