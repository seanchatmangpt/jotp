'use client'

import { useState } from 'react'
import { MinusIcon, PlusIcon } from '@heroicons/react/24/outline'
import { Button } from '@radix-ui/themes'

interface DiffViewerProps {
  original: string
  modified: string
}

interface DiffLine {
  type: 'equal' | 'insert' | 'delete'
  line: string
  lineNumber: number
}

function generateDiff(original: string, modified: string): DiffLine[] {
  const originalLines = original.split('\n')
  const modifiedLines = modified.split('\n')

  const diff: DiffLine[] = []
  let i = 0, j = 0

  while (i < originalLines.length || j < modifiedLines.length) {
    if (i < originalLines.length && j < modifiedLines.length && originalLines[i] === modifiedLines[j]) {
      diff.push({ type: 'equal', line: originalLines[i], lineNumber: i + 1 })
      i++
      j++
    } else if (j < modifiedLines.length) {
      diff.push({ type: 'insert', line: modifiedLines[j], lineNumber: j + 1 })
      j++
    } else if (i < originalLines.length) {
      diff.push({ type: 'delete', line: originalLines[i], lineNumber: i + 1 })
      i++
    }
  }

  return diff
}

export function DiffViewer({ original, modified }: DiffViewerProps) {
  const [showUnchanged, setShowUnchanged] = useState(false)
  const diff = generateDiff(original, modified)

  const visibleLines = showUnchanged
    ? diff
    : diff.filter(line => line.type !== 'equal')

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-sm text-gray-600">
            Changes: {diff.filter(l => l.type !== 'equal').length}
          </span>
          <Button
            variant="outline"
           
            onClick={() => setShowUnchanged(!showUnchanged)}
          >
            {showUnchanged ? 'Hide' : 'Show'} Unchanged
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Original */}
        <div>
          <div className="flex items-center gap-2 mb-2">
            <div className="w-3 h-3 bg-gray-300 rounded-full"></div>
            <span className="text-sm font-medium text-gray-700">Original</span>
          </div>
          <div className="border border-gray-200 rounded-lg overflow-hidden">
            <pre className="bg-gray-50 p-4 text-sm max-h-96 overflow-y-auto">
              {original.split('\n').map((line, i) => (
                <div
                  key={`orig-${i}`}
                  className={`${
                    diff.find(d => d.type === 'delete' && d.lineNumber === i + 1)
                      ? 'bg-red-50'
                      : ''
                  }`}
                >
                  <span className="text-xs text-gray-400 mr-2 inline-block w-6">{i + 1}</span>
                  <span className={diff.find(d => d.type === 'delete' && d.lineNumber === i + 1) ? 'line-through text-gray-500' : ''}>
                    {line}
                  </span>
                </div>
              ))}
            </pre>
          </div>
        </div>

        {/* Modified */}
        <div>
          <div className="flex items-center gap-2 mb-2">
            <div className="w-3 h-3 bg-blue-500 rounded-full"></div>
            <span className="text-sm font-medium text-gray-700">Modified</span>
          </div>
          <div className="border border-gray-200 rounded-lg overflow-hidden">
            <pre className="bg-gray-50 p-4 text-sm max-h-96 overflow-y-auto">
              {modified.split('\n').map((line, i) => (
                <div
                  key={`mod-${i}`}
                  className={`${
                    diff.find(d => d.type === 'insert' && d.lineNumber === i + 1)
                      ? 'bg-green-50'
                      : ''
                  }`}
                >
                  <span className="text-xs text-gray-400 mr-2 inline-block w-6">{i + 1}</span>
                  <span className={diff.find(d => d.type === 'insert' && d.lineNumber === i + 1) ? 'text-green-700' : ''}>
                    {line}
                  </span>
                </div>
              ))}
            </pre>
          </div>
        </div>
      </div>

      {/* Side by side diff */}
      <div>
        <h4 className="text-sm font-medium text-gray-700 mb-2">Side by Side Comparison</h4>
        <div className="border border-gray-200 rounded-lg overflow-hidden">
          <div className="grid grid-cols-1 md:grid-cols-2">
            <div className="border-r border-gray-200">
              <div className="bg-gray-100 px-4 py-2 text-sm font-medium text-gray-700 border-b border-gray-200">
                Original
              </div>
              <pre className="p-4 text-sm max-h-64 overflow-y-auto">
                {diff.map((line, index) => (
                  <div
                    key={`side-${index}`}
                    className={`${line.type === 'delete' ? 'bg-red-50' : line.type === 'equal' ? 'bg-white' : 'bg-gray-50'}`}
                  >
                    <span className="text-xs text-gray-400 mr-2 inline-block w-6">{line.lineNumber}</span>
                    <span className={line.type === 'delete' ? 'line-through text-gray-500' : ''}>
                      {line.line}
                    </span>
                  </div>
                ))}
              </pre>
            </div>

            <div>
              <div className="bg-gray-100 px-4 py-2 text-sm font-medium text-gray-700 border-b border-gray-200">
                Modified
              </div>
              <pre className="p-4 text-sm max-h-64 overflow-y-auto">
                {diff.map((line, index) => (
                  <div
                    key={`side-${index}`}
                    className={`${line.type === 'insert' ? 'bg-green-50' : line.type === 'equal' ? 'bg-white' : 'bg-gray-50'}`}
                  >
                    <span className="text-xs text-gray-400 mr-2 inline-block w-6">{line.lineNumber}</span>
                    <span className={line.type === 'insert' ? 'text-green-700' : ''}>
                      {line.line}
                    </span>
                  </div>
                ))}
              </pre>
            </div>
          </div>
        </div>
      </div>

      {/* Summary */}
      <div className="bg-gray-50 rounded-lg p-4">
        <h4 className="text-sm font-medium text-gray-700 mb-2">Change Summary</h4>
        <div className="grid grid-cols-3 gap-4 text-sm">
          <div className="text-center">
            <div className="text-lg font-bold text-gray-700">
              {diff.filter(l => l.type === 'delete').length}
            </div>
            <div className="text-gray-500">Removed</div>
          </div>
          <div className="text-center">
            <div className="text-lg font-bold text-gray-700">
              {diff.filter(l => l.type === 'insert').length}
            </div>
            <div className="text-gray-500">Added</div>
          </div>
          <div className="text-center">
            <div className="text-lg font-bold text-gray-700">
              {diff.filter(l => l.type === 'equal').length}
            </div>
            <div className="text-gray-500">Unchanged</div>
          </div>
        </div>
      </div>
    </div>
  )
}