import { NextResponse } from 'next/server';
import { mockBenchmarks } from '@/lib/mock-data';

export async function GET(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  const { id } = await params;
  const benchmark = mockBenchmarks.find(b => b.id === id);
  
  if (!benchmark) {
    return NextResponse.json({ error: 'Benchmark not found' }, { status: 404 });
  }
  
  return NextResponse.json(benchmark);
}
