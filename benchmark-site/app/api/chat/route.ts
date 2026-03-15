import { groq } from '@ai-sdk/groq'
import { streamText } from 'ai'
import { getGroqAPIKey } from '@/lib/api-keys'

export const maxDuration = 30

export async function POST(req: Request) {
  try {
    // Validate API key
    const apiKey = getGroqAPIKey()

    const { messages } = await req.json()

    const result = await streamText({
      model: groq('openai/gpt-oss-20b', {
        apiKey,
      }),
      messages,
      system: `You are a helpful assistant for JOTP (Java OTP) documentation.
Help users understand JOTP primitives, benchmarks, and fault tolerance patterns.
Be concise. Use code examples when helpful.`,
    })

    return result.toTextStreamResponse()
  } catch (error) {
    console.error('Chat error:', error)

    return new Response(
      JSON.stringify({
        error: 'Failed to process chat request',
        details: error instanceof Error ? error.message : 'Unknown error',
      }),
      {
        status: 500,
        headers: { 'Content-Type': 'application/json' },
      }
    )
  }
}
