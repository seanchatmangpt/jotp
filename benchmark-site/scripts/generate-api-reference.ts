#!/usr/bin/env node

/**
 * Script to generate API reference documentation from Javadoc comments
 * Usage: node generate-api-reference.ts
 */

const fs = require('fs')
const path = require('path')

// Mock Java source files - in production, this would scan the actual source
const mockJavaFiles = [
  {
    file: 'Proc.java',
    classes: [
      {
        name: 'Proc',
        description: 'Core process primitive for JOTP',
        methods: [
          {
            name: 'spawn',
            signature: '<S, M> ProcRef<S, M> spawn(S initialState, BiFunction<S, M, Pair<S, M>> handler, Object[] args)',
            description: 'Spawns a new JOTP process with initial state and message handler',
            parameters: [
              { name: 'initialState', type: 'S', description: 'Initial state of the process' },
              { name: 'handler', type: 'BiFunction<S, M, Pair<S, M>>', description: 'Message handler function' },
              { name: 'args', type: 'Object[]', description: 'Additional arguments' }
            ]
          },
          {
            name: 'ask',
            signature: '<S, M> M ask(M message, Duration timeout)',
            description: 'Send a synchronous message to the process',
            parameters: [
              { name: 'message', type: 'M', description: 'Message to send' },
              { name: 'timeout', type: 'Duration', description: 'Timeout for the operation' }
            ]
          }
        ]
      }
    ]
  },
  {
    file: 'Supervisor.java',
    classes: [
      {
        name: 'Supervisor',
        description: 'Supervision primitive for fault-tolerant systems',
        methods: [
          {
            name: 'create',
            signature: 'Supervisor create(Strategy strategy, List<ChildSpec> children)',
            description: 'Create a new supervisor with specified strategy and children',
            parameters: [
              { name: 'strategy', type: 'Strategy', description: 'Restart strategy (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE)' },
              { name: 'children', type: 'List<ChildSpec>', description: 'Child specifications' }
            ]
          }
        ]
      }
    ]
  }
]

const outputDir = path.join(__dirname, '..',', 'api-reference')

// Create output directory if it doesn't exist
if (!fs.existsSync(outputDir)) {
  fs.mkdirSync(outputDir, { recursive: true })
}

// Generate Markdown files
mockJavaFiles.forEach(sourceFile => {
  const markdown = generateApiMarkdown(sourceFile)
  const filePath = path.join(outputDir, `${sourceFile.file.replace('.java', '.md')}`)
  fs.writeFileSync(filePath, markdown)
  console.log(`Generated API doc: ${sourceFile.file}.md`)
})

// Generate index file
const indexContent = generateApiIndex(mockJavaFiles)
fs.writeFileSync(path.join(outputDir, 'index.md'), indexContent)

console.log('API reference generated successfully!')

function generateApiMarkdown(sourceFile) {
  return `# ${sourceFile.file}

${sourceFile.classes.map(cls => `
## ${cls.name}

${cls.description}

### Methods

${cls.methods.map(method => `
#### \`${method.signature}\`

${method.description}

**Parameters:**

${method.parameters.map(param => `
- \`${param.name}\` (\`${param.type}\`) - ${param.description}
`).join('\n')}

`).join('\n')}
`).join('\n')}`
}

function generateApiIndex(sourceFiles) {
  const toc = sourceFiles.map(file => `
## ${file.file}

${file.classes.map(cls => `- [${cls.name}](#${file.file.toLowerCase().replace('.java', '')}-${cls.name.toLowerCase().replace(/\s+/g, '-')}): ${cls.description}`).join('\n')}
`).join('\n')

  return `# JOTP API Reference

*This reference is automatically generated from Javadoc comments in the source code.*

${toc}

> Note: This is a mock generation. In production, this would parse actual Javadoc annotations from the source code.
`
}