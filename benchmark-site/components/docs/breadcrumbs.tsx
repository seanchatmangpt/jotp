'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { Fragment } from 'react'
import { HomeIcon, ChevronRightIcon } from '@heroicons/react/24/outline'

interface BreadcrumbItem {
  label: string
  href?: string
}

interface BreadcrumbsProps {
  items?: BreadcrumbItem[]
}

export function Breadcrumbs({ items }: BreadcrumbsProps) {
  const pathname = usePathname()
  const defaultItems: BreadcrumbItem[] = [
    { label: 'Dashboard', href: '/dashboard' },
    { label: 'Documentation' },
  ]

  const allItems = items ? defaultItems.concat(items) : defaultItems

  return (
    <nav className="flex items-center space-x-2 text-sm text-gray-500">
      {allItems.map((item, index) => (
        <Fragment key={index}>
          {index > 0 && <ChevronRightIcon className="w-4 h-4" />}
          {item.href ? (
            <Link
              href={item.href}
              className="hover:text-gray-700 transition-colors"
            >
              {item.label}
            </Link>
          ) : (
            <span className="font-medium text-gray-900">{item.label}</span>
          )}
        </Fragment>
      ))}
    </nav>
  )
}