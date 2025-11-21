'use client';

import { useEffect, useRef, useState } from 'react';
import { Loader } from '@googlemaps/js-api-loader';

interface LocationData {
  type: string;
  latitude: number;
  longitude: number;
  accuracy: number;
  timestamp: number;
  provider: string;
  device_id: string;
}

export default function Map() {
  const mapRef = useRef<HTMLDivElement>(null);
  const googleMapRef = useRef<google.maps.Map | null>(null);
  const currentMarkerRef = useRef<google.maps.Circle | null>(null);
  const pathLineRef = useRef<google.maps.Polyline | null>(null);
  const markersRef = useRef<google.maps.Marker[]>([]);
  const [locations, setLocations] = useState<LocationData[]>([]);
  const [isMapLoaded, setIsMapLoaded] = useState(false);

  // Fetch initial locations
  const fetchLocations = async () => {
    try {
      const response = await fetch('/api/locations');
      const data = await response.json();
      console.log('Fetched locations:', data);
      setLocations(data);
    } catch (error) {
      console.error('Error fetching locations:', error);
    }
  };

  // Initialize map
  useEffect(() => {
    const initMap = async () => {
      const apiKey = process.env.NEXT_PUBLIC_GOOGLE_MAPS_API_KEY;
      console.log('Initializing map with API key:', apiKey ? 'present' : 'missing');
      
      const loader = new Loader({
        apiKey: apiKey || '',
        version: 'weekly',
      });

      try {
        await loader.load();
        console.log('Google Maps loaded successfully');

        if (mapRef.current && !googleMapRef.current) {
          console.log('Creating map instance');
          googleMapRef.current = new google.maps.Map(mapRef.current, {
            center: { lat: 53.27652744, lng: -6.47575287 },
            zoom: 15,
            mapTypeId: 'roadmap',
            gestureHandling: 'greedy',
          });
          console.log('Map created successfully');
          setIsMapLoaded(true);
        }
      } catch (error) {
        console.error('Error loading Google Maps:', error);
      }
    };

    initMap();
    fetchLocations();
  }, []);

  // Set up Server-Sent Events for real-time updates
  useEffect(() => {
    const eventSource = new EventSource('/api/updates');

    eventSource.onmessage = (event) => {
      if (event.data) {
        try {
          const update = JSON.parse(event.data);
          if (update.updated) {
            fetchLocations();
          }
        } catch (error) {
          console.error('Error parsing SSE data:', error);
        }
      }
    };

    eventSource.onerror = (error) => {
      console.error('SSE error:', error);
    };

    return () => {
      eventSource.close();
    };
  }, []);

  // Update map when locations change
  useEffect(() => {
    console.log('Locations updated:', locations.length, 'points');
    if (!googleMapRef.current || locations.length === 0) {
      console.log('Skipping map update - map:', !!googleMapRef.current, 'locations:', locations.length);
      return;
    }

    const map = googleMapRef.current;
    const currentLocation = locations[locations.length - 1];
    console.log('Updating map with current location:', currentLocation);

    // Clear existing markers
    markersRef.current.forEach(marker => marker.setMap(null));
    markersRef.current = [];

    // Remove existing current marker
    if (currentMarkerRef.current) {
      currentMarkerRef.current.setMap(null);
    }

    // Remove existing path line
    if (pathLineRef.current) {
      pathLineRef.current.setMap(null);
    }

    // Create animated current position circle
    currentMarkerRef.current = new google.maps.Circle({
      strokeColor: '#FF0000',
      strokeOpacity: 0.8,
      strokeWeight: 2,
      fillColor: '#FF0000',
      fillOpacity: 0.35,
      map: map,
      center: { lat: currentLocation.latitude, lng: currentLocation.longitude },
      radius: currentLocation.accuracy,
    });

    // Animate the current marker
    let opacity = 0.35;
    let increasing = false;
    const animationInterval = setInterval(() => {
      if (increasing) {
        opacity += 0.01;
        if (opacity >= 0.6) increasing = false;
      } else {
        opacity -= 0.01;
        if (opacity <= 0.2) increasing = true;
      }
      currentMarkerRef.current?.setOptions({ fillOpacity: opacity });
    }, 50);

    // Store interval for cleanup
    (currentMarkerRef.current as any).animationInterval = animationInterval;

    // Create path with blue lines
    if (locations.length > 1) {
      const path = locations.map(loc => ({
        lat: loc.latitude,
        lng: loc.longitude,
      }));

      pathLineRef.current = new google.maps.Polyline({
        path: path,
        geodesic: true,
        strokeColor: '#0000FF',
        strokeOpacity: 0.8,
        strokeWeight: 3,
        map: map,
      });

      // Add markers at each point (except the last one which is the current position)
      locations.slice(0, -1).forEach((location, index) => {
        const marker = new google.maps.Marker({
          position: { lat: location.latitude, lng: location.longitude },
          map: map,
          icon: {
            path: google.maps.SymbolPath.CIRCLE,
            scale: 5,
            fillColor: '#0000FF',
            fillOpacity: 0.7,
            strokeColor: '#FFFFFF',
            strokeWeight: 1,
          },
        });

        // Create info window with timestamp
        const now = Date.now();
        const timeDiff = now - location.timestamp;
        const timeAgo = formatTimeAgo(timeDiff);
        const timestamp = new Date(location.timestamp).toLocaleString();

        const infoWindow = new google.maps.InfoWindow({
          content: `
            <div style="padding: 8px;">
              <strong>Time:</strong> ${timestamp}<br>
              <strong>Ago:</strong> ${timeAgo}<br>
              <strong>Accuracy:</strong> ${location.accuracy.toFixed(1)}m
            </div>
          `,
        });

        marker.addListener('mouseover', () => {
          infoWindow.open(map, marker);
        });

        marker.addListener('mouseout', () => {
          infoWindow.close();
        });

        markersRef.current.push(marker);
      });
    }

    // Center map on current location
    map.setCenter({ lat: currentLocation.latitude, lng: currentLocation.longitude });

    // Cleanup animation on unmount
    return () => {
      if ((currentMarkerRef.current as any)?.animationInterval) {
        clearInterval((currentMarkerRef.current as any).animationInterval);
      }
    };
  }, [locations]);

  const formatTimeAgo = (milliseconds: number): string => {
    const seconds = Math.floor(milliseconds / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);

    if (hours > 0) {
      return `${hours}h ${minutes % 60}m ago`;
    } else if (minutes > 0) {
      return `${minutes}m ${seconds % 60}s ago`;
    } else {
      return `${seconds}s ago`;
    }
  };

  return (
    <div style={{ width: '100%', height: '100%', position: 'relative' }}>
      {!isMapLoaded && (
        <div style={{
          position: 'absolute',
          top: '50%',
          left: '50%',
          transform: 'translate(-50%, -50%)',
          fontSize: '18px',
          color: '#666'
        }}>
          Loading map...
        </div>
      )}
      <div ref={mapRef} style={{ width: '100%', height: '100%' }} />
    </div>
  );
}
