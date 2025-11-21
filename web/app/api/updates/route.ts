import { NextRequest } from 'next/server';
import { locationStore } from '@/lib/locationStore';

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  const encoder = new TextEncoder();
  
  const stream = new ReadableStream({
    start(controller) {
      // Send initial connection message
      controller.enqueue(encoder.encode(': connected\n\n'));

      // Subscribe to location updates
      const unsubscribe = locationStore.subscribe(() => {
        const data = JSON.stringify({ updated: true });
        controller.enqueue(encoder.encode(`data: ${data}\n\n`));
      });

      // Keep connection alive with periodic heartbeat
      const heartbeatInterval = setInterval(() => {
        controller.enqueue(encoder.encode(': heartbeat\n\n'));
      }, 30000);

      // Cleanup on close
      request.signal.addEventListener('abort', () => {
        clearInterval(heartbeatInterval);
        unsubscribe();
        controller.close();
      });
    },
  });

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
    },
  });
}
