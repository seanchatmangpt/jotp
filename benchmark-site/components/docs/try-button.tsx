'use client'

import { useState } from 'react'
import { PlayIcon, CheckCircleIcon } from '@heroicons/react/24/outline'
import { Button } from '@radix-ui/themes'

interface TryButtonProps {
  onClick: () => void
  className?: string
}

export function TryButton({ onClick, className }: TryButtonProps) {
  const [isLoading, setIsLoading] = useState(false)
  const [success, setSuccess] = useState(false)

  const handleClick = async () => {
    setIsLoading(true)
    try {
      await onClick()
      setSuccess(true)
      setTimeout(() => setSuccess(false), 2000)
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <Button
      variant="ghost"
     
      onClick={handleClick}
      disabled={isLoading}
      className={`text-xs h-7 px-2 ${className}`}
    >
      {isLoading ? (
        <div className="flex items-center">
          <div className="w-3 h-3 border-2 border-blue-600 border-t-transparent rounded-full animate-spin mr-1" />
          Running...
        </div>
      ) : success ? (
        <>
          <CheckCircleIcon className="w-3 h-3 mr-1" />
          Success!
        </>
      ) : (
        <>
          <PlayIcon className="w-3 h-3 mr-1" />
          Try
        </>
      )}
    </Button>
  )
}