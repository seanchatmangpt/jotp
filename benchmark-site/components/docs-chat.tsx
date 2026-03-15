'use client'

import { useRef, useEffect, useState } from 'react'

interface Source {
  section: string
  source: string
  similarity: string
}

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources?: Source[]
}

interface DocsChatProps {
  mode: 'chat' | 'docs'
  onModeChange: (mode: 'chat' | 'docs') => void
}

export function DocsChat({ mode, onModeChange }: DocsChatProps) {
  const [messages, setMessages] = useState<Message[]>([
    {
      id: 'welcome',
      role: 'assistant',
      content: mode === 'docs'
        ? '👋 Hi! I\'m your JOTP documentation assistant. I can help you understand JOTP primitives, fault tolerance patterns, and integration strategies. What would you like to know?'
        : '👋 Hi! I\'m powered by Groq\'s LPU for ultra-fast inference. How can I help you today?',
    },
  ])
  const [inputValue, setInputValue] = useState('')
  const [isLoading, setIsLoading] = useState(false)
  const [sources, setSources] = useState<Source[]>([])
  const messagesEndRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  useEffect(() => {
    // Update welcome message when mode changes
    setMessages([
      {
        id: 'welcome',
        role: 'assistant',
        content: mode === 'docs'
          ? '👋 Hi! I\'m your JOTP documentation assistant. I can help you understand JOTP primitives, fault tolerance patterns, and integration strategies. What would you like to know?'
          : '👋 Hi! I\'m powered by Groq\'s LPU for ultra-fast inference. How can I help you today?',
      },
    ])
    setSources([])
  }, [mode])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!inputValue.trim() || isLoading) return

    const userMessage: Message = {
      id: Date.now().toString(),
      role: 'user',
      content: inputValue,
    }

    setMessages(prev => [...prev, userMessage])
    setInputValue('')
    setIsLoading(true)
    setSources([])

    try {
      const endpoint = mode === 'docs' ? '/api/docs-search' : '/api/chat'
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: mode === 'docs'
          ? JSON.stringify({ query: inputValue })
          : JSON.stringify({ messages: [...messages, userMessage] }),
      })

      if (!response.ok) throw new Error('Failed to get response')

      // Extract sources from headers if in docs mode
      if (mode === 'docs') {
        const sourcesHeader = response.headers.get('X-Sources')
        if (sourcesHeader) {
          try {
            setSources(JSON.parse(sourcesHeader))
          } catch (e) {
            console.error('Failed to parse sources:', e)
          }
        }
      }

      const reader = response.body?.getReader()
      if (!reader) throw new Error('No response body')

      const assistantMessage: Message = {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: '',
      }
      setMessages(prev => [...prev, assistantMessage])

      const decoder = new TextDecoder()
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        const text = decoder.decode(value)
        setMessages(prev => prev.map(msg =>
          msg.id === assistantMessage.id
            ? { ...msg, content: msg.content + text }
            : msg
        ))
      }
    } catch (error) {
      console.error('Error sending message:', error)
      setMessages(prev => [...prev, {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: 'Sorry, I encountered an error. Please try again.',
      }])
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-white">
      {/* Mode Toggle */}
      <div className="border-b border-gray-200 bg-gray-50">
        <div className="mx-auto w-full max-w-2xl px-4 py-4">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-xl font-semibold text-gray-900">
                {mode === 'docs' ? 'JOTP Documentation' : 'AI Chat'}
              </h1>
              <p className="text-sm text-gray-600">
                {mode === 'docs'
                  ? 'Ask about JOTP primitives, patterns, and integration'
                  : 'Powered by Groq LPU for ultra-fast responses'}
              </p>
            </div>
            <div className="flex gap-2">
              <button
                onClick={() => onModeChange('chat')}
                className={`px-3 py-1.5 text-sm font-medium rounded-md transition-colors ${
                  mode === 'chat'
                    ? 'bg-[#f55036] text-white'
                    : 'bg-white text-gray-700 hover:bg-gray-100'
                }`}
              >
                Chat
              </button>
              <button
                onClick={() => onModeChange('docs')}
                className={`px-3 py-1.5 text-sm font-medium rounded-md transition-colors ${
                  mode === 'docs'
                    ? 'bg-[#f55036] text-white'
                    : 'bg-white text-gray-700 hover:bg-gray-100'
                }`}
              >
                Docs
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Messages */}
      <div className="mx-auto w-full max-w-2xl py-8 px-4">
        <div className="space-y-4 mb-4">
          {messages.map(m => (
            <div
              key={m.id}
              className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}
            >
              <div
                className={`max-w-[80%] rounded-lg px-4 py-2 ${
                  m.role === 'user'
                    ? 'bg-blue-100 text-black'
                    : 'bg-gray-100 text-black'
                }`}
              >
                <div className="text-xs text-gray-500 mb-1">
                  {m.role === 'user' ? 'You' : mode === 'docs' ? 'JOTP Docs Assistant' : 'GPT-OSS-20B powered by Groq'}
                </div>
                <div className="text-sm whitespace-pre-wrap">{m.content}</div>
              </div>
            </div>
          ))}

          {/* Sources */}
          {sources.length > 0 && (
            <div className="mt-4 p-4 bg-blue-50 rounded-lg border border-blue-200">
              <h3 className="text-sm font-semibold text-blue-900 mb-2">📚 Sources</h3>
              <ul className="space-y-1">
                {sources.map((source, i) => (
                  <li key={i} className="text-xs text-blue-800">
                    <span className="font-medium">[{i + 1}]</span> {source.section}
                    <span className="text-blue-600 ml-2">({source.source})</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        {/* Input */}
        <form onSubmit={handleSubmit} className="flex gap-4">
          <input
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            placeholder={
              mode === 'docs'
                ? 'Ask about JOTP (e.g., "How do I create a supervisor tree?")'
                : 'Type your message...'
            }
            className="flex-1 rounded-lg border border-gray-300 px-4 py-2 text-black focus:outline-none focus:ring-2 focus:ring-[#f55036]"
          />
          <button
            type="submit"
            disabled={isLoading}
            className="rounded-lg bg-[#f55036] px-4 py-2 text-white hover:bg-[#d94530] disabled:opacity-50"
          >
            {isLoading ? '...' : 'Send'}
          </button>
        </form>

        {/* Quick prompts for docs mode */}
        {mode === 'docs' && messages.length <= 2 && (
          <div className="mt-4 flex flex-wrap gap-2">
            {[
              'What is a supervisor tree?',
              'How does trap_exit work?',
              'Explain StateMachine usage',
              'Integration patterns for Spring Boot',
            ].map((prompt) => (
              <button
                key={prompt}
                onClick={() => setInputValue(prompt)}
                className="px-3 py-1.5 text-xs bg-gray-100 hover:bg-gray-200 rounded-full text-gray-700 transition-colors"
              >
                {prompt}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
