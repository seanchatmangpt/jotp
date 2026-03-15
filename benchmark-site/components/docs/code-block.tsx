'use client'

import { useState } from 'react'
import { CheckIcon, ClipboardIcon, PlayIcon, EyeIcon, CodeBracketIcon } from '@heroicons/react/24/outline'
import { Button } from '@radix-ui/themes'
import { DiffViewer } from './diff-viewer'
import { TryButton } from './try-button'

interface CodeBlockProps {
  children: React.ReactNode
  language?: string
  filename?: string
  showLineNumbers?: boolean
  editable?: boolean
  diff?: {
    original: string
    modified: string
  }
  runnable?: boolean
}

const languageAliases: Record<string, string> = {
  'java': 'java',
  'jotp': 'java',
  'jsx': 'javascript',
  'ts': 'typescript',
  'tsx': 'typescriptreact',
  'sh': 'bash',
  'shell': 'bash',
  'xml': 'xml',
  'md': 'markdown',
  'yml': 'yaml',
  'yaml': 'yaml',
}

export function CodeBlock({
  children,
  language = 'text',
  filename,
  showLineNumbers = true,
  editable = false,
  diff,
  runnable = false
}: CodeBlockProps) {
  const [copied, setCopied] = useState(false)
  const [showDiff, setShowDiff] = useState(false)
  const [showTry, setShowTry] = useState(false)

  const displayLanguage = languageAliases[language] || language

  const copyToClipboard = async () => {
    const text = Array.isArray(children) ? children.join('') : String(children)
    await navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const handleTry = async () => {
    setShowTry(true)
    setShowDiff(false)
  }

  const handleViewDiff = () => {
    setShowDiff(true)
    setShowTry(false)
  }

  return (
    <div className="relative rounded-lg border border-gray-200 bg-gray-50 overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between bg-gray-100 px-4 py-2 border-b border-gray-200">
        <div className="flex items-center gap-2">
          <CodeBracketIcon className="w-4 h-4 text-gray-600" />
          {filename ? (
            <span className="text-sm font-medium text-gray-700">{filename}</span>
          ) : (
            <span className="text-sm text-gray-600">{displayLanguage}</span>
          )}
        </div>

        <div className="flex items-center gap-1">
          {diff && (
            <Button
              variant="ghost"
             
              onClick={handleViewDiff}
              className="text-xs h-7 px-2"
            >
              <EyeIcon className="w-3 h-3 mr-1" />
              Diff
            </Button>
          )}
          {runnable && (
            <TryButton onClick={handleTry} />
          )}
          <Button
            variant="ghost"
           
            onClick={copyToClipboard}
            className="text-xs h-7 px-2"
          >
            {copied ? (
              <>
                <CheckIcon className="w-3 h-3 mr-1" />
                Copied!
              </>
            ) : (
              <>
                <ClipboardIcon className="w-3 h-3 mr-1" />
                Copy
              </>
            )}
          </Button>
        </div>
      </div>

      {/* Code Content */}
      {showDiff ? (
        <div className="p-4">
          <DiffViewer original={diff!.original} modified={diff!.modified} />
        </div>
      ) : showTry ? (
        <TryModal
          language={displayLanguage}
          code={Array.isArray(children) ? children.join('') : String(children)}
          onClose={() => setShowTry(false)}
        />
      ) : (
        <pre className={`overflow-x-auto ${showLineNumbers ? 'pl-12' : ''} p-4`}>
          <code className={`text-sm ${language === 'java' ? 'language-java' : ''}`}>
            {children}
          </code>
        </pre>
      )}

      {/* Line Numbers */}
      {showLineNumbers && !showDiff && !showTry && (
        <div className="absolute left-0 top-12 bottom-0 w-12 bg-gray-100 border-r border-gray-200 overflow-hidden">
          {Array.from(
            { length: Array.isArray(children) ? children.length : 1 },
            (_, i) => i + 1
          ).map((line) => (
            <div
              key={line}
              className="h-6 text-right pr-3 text-xs text-gray-400 select-none"
            >
              {line}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

interface TryModalProps {
  language: string
  code: string
  onClose: () => void
}

function TryModal({ language, code, onClose }: TryModalProps) {
  const [isExecuting, setIsExecuting] = useState(false)
  const [result, setResult] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const executeCode = async () => {
    setIsExecuting(true)
    setError(null)
    setResult(null)

    try {
      const response = await fetch('/api/code-exec', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          code,
          language,
        }),
      })

      const data = await response.json()

      if (data.success) {
        setResult(data.output)
      } else {
        setError(data.error)
      }
    } catch (err) {
      setError('Failed to execute code')
    } finally {
      setIsExecuting(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-lg max-w-4xl w-full max-h-[80vh] flex flex-col">
        <div className="flex items-center justify-between p-4 border-b">
          <h3 className="text-lg font-semibold">Try It in Browser</h3>
          <Button variant="ghost" onClick={onClose}>
            ×
          </Button>
        </div>

        <div className="p-4 flex-1 overflow-auto">
          <p className="text-sm text-gray-600 mb-4">
            This code will be executed in a secure sandboxed environment.
          </p>

          <div className="border border-gray-200 rounded-lg mb-4">
            <pre className="p-4 bg-gray-50 text-sm overflow-x-auto">
              <code>{code}</code>
            </pre>
          </div>

          {isExecuting && (
            <div className="flex items-center gap-2 text-blue-600">
              <div className="w-4 h-4 border-2 border-blue-600 border-t-transparent rounded-full animate-spin" />
              <span>Executing...</span>
            </div>
          )}

          {result && (
            <div className="mt-4 p-4 bg-green-50 rounded-lg">
              <h4 className="font-medium text-green-800 mb-2">Output:</h4>
              <pre className="text-sm text-green-700 whitespace-pre-wrap">{result}</pre>
            </div>
          )}

          {error && (
            <div className="mt-4 p-4 bg-red-50 rounded-lg">
              <h4 className="font-medium text-red-800 mb-2">Error:</h4>
              <pre className="text-sm text-red-700 whitespace-pre-wrap">{error}</pre>
            </div>
          )}

          {!isExecuting && !result && !error && (
            <div className="mt-4 p-4 bg-blue-50 rounded-lg">
              <p className="text-sm text-blue-800">
                <strong>Tip:</strong> For full Java execution, use the JOTP CLI or run locally with Java 26.
              </p>
            </div>
          )}
        </div>

        <div className="p-4 border-t flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            Close
          </Button>
          <Button onClick={executeCode} disabled={isExecuting}>
            {isExecuting ? 'Running...' : 'Run Code'}
          </Button>
        </div>
      </div>
    </div>
  )
}