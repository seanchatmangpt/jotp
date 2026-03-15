#!/usr/bin/env node

/**
 * Script to generate man pages for JOTP CLI commands
 * Usage: node generate-cli-docs.ts
 */

const fs = require('fs')
const path = require('path')

const commands = [
  {
    name: 'jotp-benchmark',
    description: 'Run JOTP benchmarks',
    options: [
      { flag: '--help', description: 'Show help' },
      { flag: '--version', description: 'Show version' },
      { flag: '-c, --config <file>', description: 'Configuration file' },
      { flag: '-o, --output <dir>', description: 'Output directory' },
      { flag: '-v, --verbose', description: 'Verbose output' },
    ],
    examples: [
      'jotp-benchmark',
      'jotp-benchmark --config benchmark.json',
      'jotp-benchmark --output ./results --verbose',
    ]
  },
  {
    name: 'jotp-docs',
    description: 'Generate documentation',
    options: [
      { flag: '--format <format>', description: 'Output format (html, pdf)' },
      { flag: '--output <dir>', description: 'Output directory' },
      { flag: '--include-examples', description: 'Include code examples' },
    ],
    examples: [
      'jotp-docs',
      'jotp-docs --format pdf --output ./docs',
      'jotp-docs --include-examples',
    ]
  },
  {
    name: 'jotp-test',
    description: 'Run JOTP tests',
    options: [
      { flag: '--pattern <pattern>', description: 'Test pattern' },
      { flag: '--parallel', description: 'Run tests in parallel' },
      { flag: '--coverage', description: 'Generate coverage report' },
    ],
    examples: [
      'jotp-test',
      'jotp-test --pattern "*Proc*Test"',
      'jotp-test --parallel --coverage',
    ]
  }
]

const outputDir = path.join(__dirname, '..', 'man')

// Create output directory if it doesn't exist
if (!fs.existsSync(outputDir)) {
  fs.mkdirSync(outputDir, { recursive: true })
}

commands.forEach(command => {
  const manPage = generateManPage(command)
  const filePath = path.join(outputDir, `${command.name}.1`)
  fs.writeFileSync(filePath, manPage)
  console.log(`Generated man page: ${command.name}.1`)
})

function generateManPage(command) {
  return `.TH "${command.name.toUpperCase()}" "1" "${new Date().toISOString().split('T')[0]}" "JOTP" "JOTP Manual"
.SH NAME
${command.name} \\- ${command.description}
.SH SYNOPSIS
.B ${command.name}
.RB [ OPTIONS ]
.SH DESCRIPTION
The ${command.name} command is part of the JOTP (Java OTP) framework for fault-tolerant systems.
.SH OPTIONS
${command.options.map(opt => `.TP
.B ${opt.flag}
${opt.description}`).join('\n')}
.SH EXAMPLES
${command.examples.map(ex => `.nf
.B ${ex}
.fi`).join('\n\n')}
.SH SEE ALSO
.BR jotp (1),
.BR jotp-benchmark (1),
.BR jotp-docs (1)
.SH AUTHOR
JOTP Team <team@jotp.dev>
.SH BUGS
Report bugs at https://github.com/seanchatmangpt/jotp/issues
`
}

console.log('Man pages generated successfully!')