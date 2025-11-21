import { NextRequest } from 'next/server';
import { locationStore } from '@/lib/locationStore';

export async function GET(request: NextRequest) {
  const locations = locationStore.getLocations();
  
  return new Response(JSON.stringify(locations), {
    headers: {
      'Content-Type': 'application/json',
    },
  });
}
