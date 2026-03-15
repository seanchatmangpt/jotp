'use client'

import NextLink from 'next/link'
import { ReactNode } from 'react'

interface LinkProps {
  href: string
  children: ReactNode
  className?: string
  target?: '_blank'
  rel?: string
}

export function Link({ href, children, className = '', target, rel }: LinkProps) {
  return (
    <NextLink
      href={href}
      className={className}
      target={target}
      rel={rel}
    >
      {children}
    </NextLink>
  )
}

export default Link