import { NextResponse } from 'next/server';
import { mockBenchmarks } from '@/lib/mock-data';

export async function GET() {
  return NextResponse.json(mockBenchmarks);
}
