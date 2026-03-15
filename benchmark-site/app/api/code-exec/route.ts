import { NextRequest, NextResponse } from 'next/server'

interface ExecuteRequest {
  code: string
  language: string
  timeout?: number
}

interface ExecuteResponse {
  output: string
  error?: string
  executionTime: number
  success: boolean
}

// Mock execution service - in production, this would use Docker containers or similar
async function executeCode(code: string, language: string): Promise<ExecuteResponse> {
  const startTime = Date.now()

  try {
    // Simulate code execution
    await new Promise(resolve => setTimeout(resolve, 1000))

    // Mock execution results based on language
    const responses: Record<string, ExecuteResponse> = {
      java: {
        output: 'Hello, JOTP!\nBenchmark completed successfully.\nThroughput: 1,000,000 ops/sec\n',
        executionTime: Date.now() - startTime,
        success: true
      },
      javascript: {
        output: 'Hello, JOTP!\nJavaScript execution successful.',
        executionTime: Date.now() - startTime,
        success: true
      },
      python: {
        output: 'Hello, JOTP!\nPython execution successful.',
        executionTime: Date.now() - startTime,
        success: true
      },
      bash: {
        output: 'Hello, JOTP!\nBash command executed successfully.',
        executionTime: Date.now() - startTime,
        success: true
      }
    }

    // Check for common errors in the code
    if (code.includes('import') && language !== 'java') {
      return {
        output: '',
        error: 'Import statements are only supported in Java for this demo.',
        executionTime: Date.now() - startTime,
        success: false
      }
    }

    if (code.includes('while(true)')) {
      return {
        output: '',
        error: 'Infinite loops are not allowed in the sandbox.',
        executionTime: Date.now() - startTime,
        success: false
      }
    }

    // Return mock response or actual execution result
    const response = responses[language] || responses.javascript

    return {
      ...response,
      executionTime: Date.now() - startTime
    }

  } catch (error) {
    return {
      output: '',
      error: error instanceof Error ? error.message : 'Unknown error',
      executionTime: Date.now() - startTime,
      success: false
    }
  }
}

export async function POST(request: NextRequest) {
  try {
    const body: ExecuteRequest = await request.json()
    const { code, language, timeout = 5000 } = body

    if (!code || !language) {
      return NextResponse.json(
        { error: 'Code and language are required' },
        { status: 400 }
      )
    }

    // Check code length to prevent abuse
    if (code.length > 10000) {
      return NextResponse.json(
        { error: 'Code too large (max 10,000 characters)' },
        { status: 400 }
      )
    }

    // Execute the code with timeout
    const executePromise = executeCode(code, language)
    const timeoutPromise = new Promise<ExecuteResponse>((_, reject) => {
      setTimeout(() => reject(new Error('Execution timeout')), timeout)
    })

    const result = await Promise.race([executePromise, timeoutPromise]) as ExecuteResponse

    return NextResponse.json(result)

  } catch (error) {
    console.error('Code execution error:', error)
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    )
  }
}