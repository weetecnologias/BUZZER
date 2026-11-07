import { Platform } from 'react-native';
import * as Location from 'expo-location';
import { api } from '@buzzer/core';

export type Nearest = {
  latitude: number;
  longitude: number;
  in_service_area: boolean;
  city_id?: string;
  city_name?: string;
  city_distance_km?: number;
  locality_id?: string;
  locality_name?: string;
  pincode?: string;
};

export type CurrentLocation = Nearest & { street?: string; landmark?: string; postalCode?: string; accuracyMeters?: number };

export class LocationError extends Error {}

/**
 * Reads the device position (GPS / network on phones, browser geolocation on web), then:
 *  - on iOS/Android, reverse-geocodes it to street + postal code with the platform geocoder;
 *  - on every platform, asks the backend for the nearest BUZZER city + locality + pincode.
 */
export async function getCurrentLocation(): Promise<CurrentLocation> {
  const perm = await Location.requestForegroundPermissionsAsync();
  if (perm.status !== 'granted') {
    throw new LocationError('Location permission was not granted. Allow location access, or enter the address manually.');
  }
  let pos: Location.LocationObject;
  try {
    pos = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.High });
  } catch {
    throw new LocationError('Could not get your location. Turn on location services and try again.');
  }
  const { latitude, longitude, accuracy } = pos.coords;
  const nearest = await api.get<Nearest>(`/api/v1/locations/nearest?lat=${latitude}&lng=${longitude}`);

  let street: string | undefined;
  let landmark: string | undefined;
  let postalCode: string | undefined;
  if (Platform.OS !== 'web') {
    try {
      const [addr] = await Location.reverseGeocodeAsync({ latitude, longitude });
      if (addr) {
        street = [addr.streetNumber, addr.street, addr.district ?? addr.subregion].filter(Boolean).join(', ') || undefined;
        landmark = addr.name && addr.name !== addr.street ? addr.name : undefined;
        postalCode = addr.postalCode ?? undefined;
      }
    } catch {
      /* platform geocoder unavailable - the nearest-locality lookup still fills city/locality/pincode */
    }
  }
  return { ...nearest, latitude, longitude, street, landmark, postalCode, accuracyMeters: accuracy ?? undefined };
}
