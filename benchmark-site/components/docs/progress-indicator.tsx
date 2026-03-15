'use client'

import { useState, useEffect } from 'react'
import { BookOpenIcon, ArrowPathIcon } from '@heroicons/react/24/outline'

interface ProgressIndicatorProps {
  contentId: string
  className?: string
}

export function ProgressIndicator({ contentId, className }: ProgressIndicatorProps) {
  const [progress, setProgress] = useState(0)
  const [isReading, setIsReading] = useState(false)

  useEffect(() => {
    const storedProgress = localStorage.getItem(`doc-progress-${contentId}`)
    if (storedProgress) {
      setProgress(parseInt(storedProgress))
    }

    const handleScroll = () => {
      const scrolled = window.scrollY
      const maxScroll = document.documentElement.scrollHeight - window.innerHeight
      const newProgress = Math.round((scrolled / maxScroll) * 100)
      setProgress(newProgress)

      if (scrolled > 100) {
        setIsReading(true)
      } else {
        setIsReading(false)
      }
    }

    window.addEventListener('scroll', handleScroll)
    return () => window.removeEventListener('scroll', handleScroll)
  }, [contentId])

  const resetProgress = () => {
    setProgress(0)
    localStorage.removeItem(`doc-progress-${contentId}`)
  }

  const progressColor = progress < 25 ? 'bg-red-500' :
                       progress < 75 ? 'bg-yellow-500' :
                       'bg-green-500'

  return (
    <div className={`fixed top-4 right-4 z-50 bg-white rounded-lg shadow-lg p-4 ${className}`}>
      <div className="flex items-center gap-3">
        <div className="relative">
          <BookOpenIcon className="w-6 h-6 text-gray-600" />
          <div
            className={`absolute inset-0 rounded-full animate-pulse ${
              isReading ? 'bg-blue-100' : ''
            }`}
          />
        </div>

        <div className="flex-1">
          <div className="flex items-center justify-between text-xs text-gray-600 mb-1">
            <span>Progress</span>
            <span>{progress}%</span>
          </div>
          <div className="w-32 h-2 bg-gray-200 rounded-full overflow-hidden">
            <div
              className={`h-full transition-all duration-300 ${progressColor}`}
              style={{ width: `${progress}%` }}
            />
          </div>
        </div>

        <button
          onClick={resetProgress}
          className="p-1 hover:bg-gray-100 rounded transition-colors"
          title="Reset progress"
        >
          <ArrowPathIcon className="w-4 h-4 text-gray-600" />
        </button>
      </div>
    </div>
  )
}