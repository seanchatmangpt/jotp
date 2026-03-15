'use client'

import { useState } from 'react'
import { DocumentArrowDownIcon, XMarkIcon } from '@heroicons/react/24/outline'
import { Button } from '@radix-ui/themes'
import { Dialog, Transition } from '@headlessui/react'
import { Fragment } from 'react'

interface ExportMenuProps {
  title: string
  content: string
  author?: string
  className?: string
}

export function ExportMenu({ title, content, author, className }: ExportMenuProps) {
  const [isOpen, setIsOpen] = useState(false)

  const handleExport = async (format: 'pdf' | 'epub') => {
    try {
      const response = await fetch(`/api/export/${format}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          content,
          title,
          author,
        }),
      })

      if (!response.ok) throw new Error('Export failed')

      // Create blob and download
      const blob = await response.blob()
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.style.display = 'none'
      a.href = url
      a.download = `${title.replace(/[^a-z0-9]/gi, '_').toLowerCase()}.${format}`
      document.body.appendChild(a)
      a.click()
      window.URL.revokeObjectURL(url)
      document.body.removeChild(a)

      setIsOpen(false)
    } catch (error) {
      console.error('Export error:', error)
      alert('Failed to export. Please try again.')
    }
  }

  return (
    <>
      <Button
        variant="ghost"
       
        onClick={() => setIsOpen(true)}
        className={`relative ${className}`}
      >
        <DocumentArrowDownIcon className="w-4 h-4 mr-2" />
        Export
      </Button>

      <Transition show={isOpen} as={Fragment}>
        <Dialog as="div" className="relative z-50" onClose={setIsOpen}>
          <div className="fixed inset-0 bg-black bg-opacity-25" aria-hidden="true" />

          <div className="fixed inset-0 overflow-y-auto">
            <div className="flex min-h-full items-center justify-center p-4">
              <Dialog.Panel className="relative w-full max-w-lg transform overflow-hidden rounded-lg bg-white shadow-xl transition-all">
                <div className="border-b border-gray-200 px-6 py-4">
                  <div className="flex items-center justify-between">
                    <Dialog.Title className="text-lg font-semibold text-gray-900">
                      Export Documentation
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
                  <div className="mb-4">
                    <p className="text-sm text-gray-600">
                      Export "{title}" in your preferred format:
                    </p>
                  </div>

                  <div className="space-y-3">
                    <div
                      className="border border-gray-200 rounded-lg p-4 hover:border-blue-300 cursor-pointer transition-colors"
                      onClick={() => handleExport('pdf')}
                    >
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-3">
                          <div className="w-10 h-10 bg-red-50 rounded-lg flex items-center justify-center">
                            <DocumentArrowDownIcon className="w-5 h-5 text-red-600" />
                          </div>
                          <div>
                            <h3 className="font-medium text-gray-900">PDF</h3>
                            <p className="text-sm text-gray-500">
                              Print-friendly format for offline reading
                            </p>
                          </div>
                        </div>
                        <span className="text-xs text-gray-500">
                          Click to export
                        </span>
                      </div>
                    </div>

                    <div
                      className="border border-gray-200 rounded-lg p-4 hover:border-blue-300 cursor-pointer transition-colors"
                      onClick={() => handleExport('epub')}
                    >
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-3">
                          <div className="w-10 h-10 bg-blue-50 rounded-lg flex items-center justify-center">
                            <DocumentArrowDownIcon className="w-5 h-5 text-blue-600" />
                          </div>
                          <div>
                            <h3 className="font-medium text-gray-900">EPUB</h3>
                            <p className="text-sm text-gray-500">
                              E-book format for e-readers
                            </p>
                          </div>
                        </div>
                        <span className="text-xs text-gray-500">
                          Click to export
                        </span>
                      </div>
                    </div>
                  </div>

                  <div className="mt-6 p-4 bg-gray-50 rounded-lg">
                    <p className="text-sm text-gray-600">
                      <strong>Note:</strong> Large documents may take a few moments to process.
                    </p>
                  </div>
                </div>

                <div className="border-t border-gray-200 px-6 py-4">
                  <Button
                    variant="outline"
                    onClick={() => setIsOpen(false)}
                    className="w-full"
                  >
                    Cancel
                  </Button>
                </div>
              </Dialog.Panel>
            </div>
          </div>
        </Dialog>
      </Transition>
    </>
  )
}