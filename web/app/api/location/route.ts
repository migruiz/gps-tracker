import { NextRequest, NextResponse } from 'next/server';
import { locationStore, LocationData } from '@/lib/locationStore';

export async function POST(request: NextRequest) {
  try {
    const data: LocationData = await request.json();
    
    // Validate required fields
    if (!data.latitude || !data.longitude || !data.device_id) {
      return NextResponse.json(
        { error: 'Missing required fields' },
        { status: 400 }
      );
    }

    // Force timestamp to now
    data.timestamp = Date.now();

    // Store the location
    locationStore.addLocation(data);

    return NextResponse.json({ success: true }, { status: 200 });
  } catch (error) {
    console.error('Error processing location:', error);
    return NextResponse.json(
      { error: 'Invalid request' },
      { status: 400 }
    );
  }
}
