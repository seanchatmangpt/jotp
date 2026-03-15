/**
 * API Key Management Utilities
 *
 * Handles Groq and OpenAI API keys for the benchmark site.
 */

export function getGroqAPIKey(): string {
  const apiKey = process.env.GROQ_API_KEY;
  if (!apiKey) {
    throw new Error(
      'GROQ_API_KEY environment variable is not set. ' +
      'Please set it in your .env.local file. ' +
      'Get your API key from: https://console.groq.com/keys'
    );
  }
  return apiKey;
}

export function getOpenAIAPIKey(): string {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    throw new Error(
      'OPENAI_API_KEY environment variable is not set. ' +
      'Please set it in your .env.local file. ' +
      'Get your API key from: https://platform.openai.com/api-keys'
    );
  }
  return apiKey;
}

export function validateAPIKeys(): {
  hasGroq: boolean;
  hasOpenAI: boolean;
  missing: string[];
} {
  const missing: string[] = [];

  if (!process.env.GROQ_API_KEY) {
    missing.push('GROQ_API_KEY');
  }
  if (!process.env.OPENAI_API_KEY) {
    missing.push('OPENAI_API_KEY');
  }

  return {
    hasGroq: !!process.env.GROQ_API_KEY,
    hasOpenAI: !!process.env.OPENAI_API_KEY,
    missing,
  };
}
