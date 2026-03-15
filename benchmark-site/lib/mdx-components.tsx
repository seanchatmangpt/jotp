import { MDXComponents } from 'mdx/types'

export const MDXComponents: MDXComponents = {
  // Custom components for enhanced content
  CodeBlock: ({ children }) => (
    <div className="relative rounded-lg border border-gray-200 bg-gray-50 p-4">
      <pre className="overflow-x-auto text-sm">{children}</pre>
    </div>
  ),

  TutorialHint: ({ children }) => (
    <div className="my-4 rounded-lg bg-purple-50 border border-purple-200 p-4">
      <div className="flex items-start gap-2">
        <span className="text-purple-600">💡</span>
        <div>
          <p className="text-purple-800 font-medium">Tutorial Hint</p>
          <p className="text-purple-700 mt-1">{children}</p>
        </div>
      </div>
    </div>
  ),

  HowToTip: ({ children }) => (
    <div className="my-4 rounded-lg bg-blue-50 border border-blue-200 p-4">
      <div className="flex items-start gap-2">
        <span className="text-blue-600">📋</span>
        <div>
          <p className="text-blue-800 font-medium">How-To Tip</p>
          <p className="text-blue-700 mt-1">{children}</p>
        </div>
      </div>
    </div>
  ),

  ReferenceNote: ({ children }) => (
    <div className="my-4 rounded-lg bg-green-50 border border-green-200 p-4">
      <div className="flex items-start gap-2">
        <span className="text-green-600">📚</span>
        <div>
          <p className="text-green-800 font-medium">Reference Note</p>
          <p className="text-green-700 mt-1">{children}</p>
        </div>
      </div>
    </div>
  ),

  ExplanationNote: ({ children }) => (
    <div className="my-4 rounded-lg bg-orange-50 border border-orange-200 p-4">
      <div className="flex items-start gap-2">
        <span className="text-orange-600">🔍</span>
        <div>
          <p className="text-orange-800 font-medium">Explanation</p>
          <p className="text-orange-700 mt-1">{children}</p>
        </div>
      </div>
    </div>
  ),
}