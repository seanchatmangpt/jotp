import { NextRequest, NextResponse } from 'next/server'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  timestamp: Date
}

interface ChatRequest {
  messages: ChatMessage[]
  context?: string
}

// Mock AI response generator - in production, this would use Claude API or similar
async function generateAIResponse(messages: ChatMessage[], context?: string): Promise<string> {
  const userQuestion = messages[messages.length - 1].content

  // Simple keyword-based responses for demonstration
  const lowerQuestion = userQuestion.toLowerCase()

  if (lowerQuestion.includes('how to') || lowerQuestion.includes('how-to')) {
    return "I'd be happy to help with a how-to guide! Could you specify what you'd like to do? For example:\n\n- How to write a benchmark\n- How to configure supervisors\n- How to analyze results\n\nEach guide provides step-by-step instructions with code examples."
  }

  if (lowerQuestion.includes('tutorial') || lowerQuestion.includes('learn')) {
    return "JOTP offers several tutorials to get you started:\n\n1. **Getting Started** - Learn the fundamentals and setup\n2. **First Benchmark** - Write your first benchmark from scratch\n3. **Observability Integration** - Add monitoring to your benchmarks\n\nWould you like to start with a specific tutorial?"
  }

  if (lowerQuestion.includes('supervisor')) {
    return "Supervisors are key to JOTP's fault tolerance. Here's a quick overview:\n\n```java\nSupervisor supervisor = Supervisor.create(\n  Supervisor.Strategy.ONE_FOR_ONE,\n  List.of(\n    ChildSpec.builder(Process::class)\n      .restartType(RestartType.PERMANENT)\n      .build()\n  )\n);\n```\n\nSupervisors automatically restart failed processes according to configurable strategies."
  }

  if (lowerQuestion.includes('proc') || lowerQuestion.includes('process')) {
    return "JOTP processes (Proc) are lightweight virtual-thread-based entities:\n\n```java\nProcRef<String, String> proc = Proc.spawn(\n  \"initial state\",\n  (state, msg) -> {\n    // Process logic here\n    return Pair.of(updatedState, response);\n  },\n  null\n);\n```\n\nThey support message passing, linking, monitoring, and more."
  }

  if (lowerQuestion.includes('benchmark') || lowerQuestion.includes('performance')) {
    return "JOTP provides comprehensive benchmarking tools:\n\n- Throughput testing (messages/sec)\n- Latency measurement (response times)\n- Memory usage tracking\n- CPU utilization monitoring\n\nThe benchmark runner supports warmup periods, multiple iterations, and statistical analysis."
  }

  if (lowerQuestion.includes('difference') || lowerQuestion.includes('vs')) {
    return "JOTP vs Erlang/OTP:\n\n**JOTP Advantages:**\n- Full type safety\n- Java ecosystem access\n- Modern Java 26 features\n- IntelliJ/Eclipse support\n- Larger developer community\n\n**Erlang/OTP Advantages:**\n- Battle-tested decades\n- BEAM performance\n- Massive concurrency\n- Hot code swapping\n\nChoose JOTP for Java teams wanting OTP patterns."
  }

  // Default response
  return `I understand you're asking about: "${userQuestion}"\n\nJOTP documentation covers a wide range of topics including:\n\n- 🎓 Tutorials for beginners\n- 📋 How-to guides for common tasks\n- 📚 API reference documentation\n- 🔍 Explanations of concepts\n\nCould you be more specific about what you'd like to know? I can help with:\n\n1. Writing benchmarks\n2. Setting up supervision trees\n3. Understanding fault tolerance\n4. API usage examples\n5. Performance optimization`
}

export async function POST(request: NextRequest) {
  try {
    const body: ChatRequest = await request.json()
    const { messages, context } = body

    if (!messages || !Array.isArray(messages) || messages.length === 0) {
      return NextResponse.json({ error: 'Messages array is required' }, { status: 400 })
    }

    // Generate AI response
    const aiResponse = await generateAIResponse(messages, context)

    // Add to messages
    const updatedMessages = [...messages, {
      role: 'assistant' as const,
      content: aiResponse,
      timestamp: new Date()
    }]

    return NextResponse.json({
      response: aiResponse,
      messages: updatedMessages,
      timestamp: new Date()
    })

  } catch (error) {
    console.error('Chat API error:', error)
    return NextResponse.json({ error: 'Internal server error' }, { status: 500 })
  }
}