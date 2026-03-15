import { getBenchmarkStreamer } from '@/lib/benchmark-streamer';

export const dynamic = 'force-dynamic';

export async function GET() {
  const streamer = getBenchmarkStreamer();
  
  const stream = new ReadableStream({
    start(controller) {
      streamer.subscribe(controller);
      
      // Send initial connection message
      controller.enqueue(new TextEncoder().encode('data: {"type":"connected"}\n\n'));
      
      // Start simulation (remove this in production)
      streamer.simulateUpdates();
    },
    cancel() {
      // Cleanup is handled in the streamer
    }
  });

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
    },
  });
}
