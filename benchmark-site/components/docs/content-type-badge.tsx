'use client'

import { ContentType } from '@/lib/content-schema'

interface ContentTypeBadgeProps {
  type: ContentType
  size?: 'sm' | 'md'
}

export function ContentTypeBadge({ type, size = 'md' }: ContentTypeBadgeProps) {
  const variants = {
    tutorial: {
      bgColor: 'bg-purple-100',
      textColor: 'text-purple-800',
      borderColor: 'border-purple-200',
      icon: '🎓',
      label: 'Tutorial',
    },
    'how-to': {
      bgColor: 'bg-blue-100',
      textColor: 'text-blue-800',
      borderColor: 'border-blue-200',
      icon: '📋',
      label: 'How-To',
    },
    reference: {
      bgColor: 'bg-green-100',
      textColor: 'text-green-800',
      borderColor: 'border-green-200',
      icon: '📚',
      label: 'Reference',
    },
    explanation: {
      bgColor: 'bg-orange-100',
      textColor: 'text-orange-800',
      borderColor: 'border-orange-200',
      icon: '🔍',
      label: 'Explanation',
    },
  }

  const variant = variants[type]

  const sizeClasses = {
    sm: 'px-2 py-1 text-xs',
    md: 'px-3 py-1.5 text-sm',
  }

  return (
    <span
      className={`${variant.bgColor} ${variant.textColor} ${variant.borderColor} border rounded-full flex items-center gap-1 ${sizeClasses[size]}`}
    >
      <span>{variant.icon}</span>
      <span className="font-medium">{variant.label}</span>
    </span>
  )
}