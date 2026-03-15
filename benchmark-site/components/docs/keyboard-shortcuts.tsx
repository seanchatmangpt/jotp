'use client'

import { useState, useEffect } from 'react'
import { Squares2X2Icon, XMarkIcon } from '@heroicons/react/24/outline'
import { Dialog, Transition } from '@headlessui/react'
import { Fragment } from 'react'
import { Button } from '@radix-ui/themes'

interface KeyboardShortcutsProps {
  className?: string
}

const shortcuts = [
  { key: '⌘K', action: 'Open search', category: 'Navigation' },
  { key: '⌘S', action: 'Save page', category: 'General' },
  { key: '⌘D', action: 'Bookmark page', category: 'General' },
  { key: '⌘/', action: 'Toggle shortcuts', category: 'General' },
  { key: 'G T', action: 'Go to tutorials', category: 'Navigation' },
  { key: 'G H', action: 'Go to how-to guides', category: 'Navigation' },
  { key: 'G R', action: 'Go to reference', category: 'Navigation' },
  { key: 'G E', action: 'Go to explanations', category: 'Navigation' },
  { key: '⌘F', action: 'Find on page', category: 'General' },
  { key: '⌘R', action: 'Reload page', category: 'General' },
]

export function KeyboardShortcuts({ className }: KeyboardShortcutsProps) {
  const [isOpen, setIsOpen] = useState(false)

  const handleKeyDown = (e: KeyboardEvent) => {
    // Cmd/Ctrl + / to toggle shortcuts
    if ((e.metaKey || e.ctrlKey) && e.key === '/') {
      e.preventDefault()
      setIsOpen(prev => !prev)
    }
    // Escape to close
    if (e.key === 'Escape') {
      setIsOpen(false)
    }
  }

  useEffect(() => {
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [])

  return (
    <>
      <Button
        variant="ghost"
       
        onClick={() => setIsOpen(true)}
        className={`relative ${className}`}
      >
        <Squares2X2Icon className="w-4 h-4 mr-2" />
        Shortcuts
        <kbd className="ml-auto text-xs text-gray-500">⌘/</kbd>
      </Button>

      <Transition show={isOpen} as={Fragment}>
        <Dialog as="div" className="relative z-50" onClose={setIsOpen}>
          <div className="fixed inset-0 bg-black bg-opacity-25" aria-hidden="true" />

          <div className="fixed inset-0 overflow-y-auto">
            <div className="flex min-h-full items-center justify-center p-4">
              <Dialog.Panel className="relative w-full max-w-2xl transform overflow-hidden rounded-lg bg-white shadow-xl transition-all">
                <div className="border-b border-gray-200 px-6 py-4">
                  <div className="flex items-center justify-between">
                    <Dialog.Title className="text-lg font-semibold text-gray-900">
                      Keyboard Shortcuts
                    </Dialog.Title>
                    <Button
                      variant="ghost"
                     
                      onClick={() => setIsOpen(false)}
                      className="p-1"
                    >
                      <XMarkIcon className="w-5 h-5" />
                    </Button>
                  </div>
                </div>

                <div className="p-6">
                  <div className="space-y-4">
                    {Object.entries(
                      shortcuts.reduce((acc, shortcut) => {
                        if (!acc[shortcut.category]) {
                          acc[shortcut.category] = []
                        }
                        acc[shortcut.category].push(shortcut)
                        return acc
                      }, {} as Record<string, typeof shortcuts>)
                    ).map(([category, items]) => (
                      <div key={category}>
                        <h3 className="text-sm font-medium text-gray-900 mb-3">
                          {category}
                        </h3>
                        <div className="space-y-2">
                          {items.map((shortcut, index) => (
                            <div
                              key={index}
                              className="flex items-center justify-between py-2 px-3 bg-gray-50 rounded-md"
                            >
                              <div className="flex items-center gap-3">
                                <kbd className="px-2 py-1 text-sm font-mono bg-white border border-gray-200 rounded">
                                  {shortcut.key.split(' ').map((k, i) => (
                                    <span key={i}>
                                      {k}
                                      {i < shortcut.key.split(' ').length - 1 && <span className="mx-1 text-gray-400">+</span>}
                                    </span>
                                  ))}
                                </kbd>
                                <span className="text-sm text-gray-700">
                                  {shortcut.action}
                                </span>
                              </div>
                            </div>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="border-t border-gray-200 px-6 py-4">
                  <p className="text-sm text-gray-500 text-center">
                    Tip: You can customize these shortcuts in your profile settings
                  </p>
                </div>
              </Dialog.Panel>
            </div>
          </div>
        </Dialog>
      </Transition>
    </>
  )
}