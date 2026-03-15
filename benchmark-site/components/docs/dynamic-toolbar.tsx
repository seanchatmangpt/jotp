'use client'

import { useState } from 'react'
import { SearchTrigger } from './search-trigger'
import { KeyboardShortcuts } from './keyboard-shortcuts'
import { AIAssistant } from './ai-assistant'
import { Button } from '@radix-ui/themes'
import { ArrowPathIcon } from '@heroicons/react/24/outline'

interface DynamicToolbarProps {
  className?: string
}

export function DynamicToolbar({ className }: DynamicToolbarProps) {
  const [isRefreshing, setIsRefreshing] = useState(false)

  const handleRefresh = async () => {
    setIsRefreshing(true)
    // In a real implementation, this would refresh the content
    await new Promise(resolve => setTimeout(resolve, 1000))
    setIsRefreshing(false)
  }

  return (
    <div className={`flex items-center gap-2 ${className}`}>
      <SearchTrigger />
      <KeyboardShortcuts />
      <Button
        variant="ghost"
       
        onClick={handleRefresh}
        disabled={isRefreshing}
        className="relative"
      >
        <ArrowPathIcon
          className={`w-4 h-4 mr-2 ${isRefreshing ? 'animate-spin' : ''}`}
        />
        {isRefreshing ? 'Refreshing...' : 'Refresh'}
      </Button>
      <AIAssistant />
    </div>
  )
}