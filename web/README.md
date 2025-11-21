# GPS Tracker Web Application

A real-time GPS tracking application built with Next.js 15 and Google Maps.

## Features

- **Real-time GPS tracking**: Accepts location data from Android GPS tracker via HTTP POST
- **Live map visualization**: Shows current position with animated red circle
- **Historical path**: Displays last hour of location data with blue path lines
- **Accuracy visualization**: Uses GPS accuracy as circle radius
- **Interactive tooltips**: Hover over path points to see timestamps and relative time
- **Server-Sent Events**: Real-time updates without polling

## Setup

1. Install dependencies:
```bash
npm install
```

2. Configure Google Maps API key:
   - Get an API key from [Google Cloud Console](https://console.cloud.google.com/)
   - Enable the Maps JavaScript API
   - Copy `.env.local` and add your key:
```
NEXT_PUBLIC_GOOGLE_MAPS_API_KEY=your_actual_api_key_here
```

3. Run the development server:
```bash
npm run dev
```

4. Open [http://localhost:3000](http://localhost:3000) in your browser

## API Endpoints

### POST /api/location
Accepts GPS location data from Android device.

**Request body:**
```json
{
  "type": "location",
  "latitude": 53.27652744,
  "longitude": -6.47575287,
  "accuracy": 11.0,
  "timestamp": 1144426211000,
  "provider": "gps",
  "device_id": "gps-tracker-device"
}
```

### GET /api/locations
Returns all location data from the last hour.

### GET /api/updates
Server-Sent Events endpoint for real-time updates. The browser automatically connects and receives notifications when new GPS data arrives.

## How It Works

1. Android device sends GPS location via POST to `/api/location`
2. Server stores location in memory (last hour only)
3. Server notifies all connected clients via SSE
4. Browser automatically fetches latest data and updates map
5. Current position shown as animated red circle
6. Historical path shown as blue polyline with markers

## Technology Stack

- **Next.js 15**: React framework with App Router
- **TypeScript**: Type-safe development
- **Google Maps JavaScript API**: Map visualization
- **Server-Sent Events**: Real-time push notifications
- **In-memory storage**: Fast data access for recent locations

## Notes

- Location data is stored in memory only (resets on server restart)
- Only locations from the last hour are kept
- Multiple devices can send data (distinguished by device_id)
- Map automatically centers on latest position
