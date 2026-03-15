'use client'

import Link from 'next/link'
import { ArrowLeftIcon, ArrowRightIcon } from '@heroicons/react/24/outline'
import { Button } from '@radix-ui/themes'
import { ContentTypeBadge } from './content-type-badge'

interface NavigationItem {
  title: string
  path: string
  type: 'tutorial' | 'how-to' | 'reference' | 'explanation'
}

interface NextPrevNavProps {
  previous: NavigationItem | null
  next: NavigationItem | null
  currentType: 'tutorial' | 'how-to' | 'reference' | 'explanation'
}

export function NextPrevNav({ previous, next, currentType }: NextPrevNavProps) {
  return (
    <div className="border-t border-gray-200 pt-8 mt-8">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {/* Previous */}
        {previous && (
          <Link href={previous.path} className="group">
            <Button
              variant="outline"
              className="w-full justify-start group-hover:bg-gray-50"
            >
              <ArrowLeftIcon className="w-4 h-4 mr-2" />
              <div className="text-left">
                <p className="text-sm text-gray-500 mb-1">Previous</p>
                <p className="font-medium group-hover:text-blue-600">
                  {previous.title}
                </p>
                <div className="flex items-center gap-1 mt-1">
                  <ContentTypeBadge size="sm" type={previous.type} />
                </div>
              </div>
            </Button>
          </Link>
        )}

        {/* Next */}
        {next && (
          <Link href={next.path} className="group">
            <Button
              variant="outline"
              className="w-full justify-end group-hover:bg-gray-50"
            >
              <div className="text-right">
                <p className="text-sm text-gray-500 mb-1">Next</p>
                <p className="font-medium group-hover:text-blue-600">
                  {next.title}
                </p>
                <div className="flex items-center gap-1 mt-1 justify-end">
                  <ContentTypeBadge size="sm" type={next.type} />
                </div>
              </div>
              <ArrowRightIcon className="w-4 h-4 ml-2" />
            </Button>
          </Link>
        )}

        {!previous && !next && (
          <div className="text-center py-8">
            <p className="text-gray-500">You've reached the end of this section!</p>
          </div>
        )}
      </div>
    </div>
  )
}