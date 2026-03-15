'use client'

import { useState } from 'react'
import { DocsChat } from '@/components/docs-chat'

export default function Chat() {
  const [mode, setMode] = useState<'chat' | 'docs'>('docs')

  return <DocsChat mode={mode} onModeChange={setMode} />
}
