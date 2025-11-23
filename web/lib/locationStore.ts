// In-memory storage for location data
export interface LocationData {
  type: string;
  latitude: number;
  longitude: number;
  accuracy: number;
  timestamp: number;
  provider: string;
  device_id: string;
  battery_level?: number;
}

class LocationStore {
  private locations: LocationData[] = [];
  private subscribers: Set<() => void> = new Set();

  addLocation(location: LocationData) {
    this.locations.push(location);
    this.cleanOldLocations();
    this.notifySubscribers();
  }

  private cleanOldLocations() {
    const oneHourAgo = Date.now() - 60 * 60 * 1000;
    this.locations = this.locations.filter(loc => loc.timestamp > oneHourAgo);
  }

  getLocations(): LocationData[] {
    this.cleanOldLocations();
    return [...this.locations];
  }

  subscribe(callback: () => void) {
    this.subscribers.add(callback);
    return () => this.subscribers.delete(callback);
  }

  private notifySubscribers() {
    this.subscribers.forEach(callback => callback());
  }
}

export const locationStore = new LocationStore();
