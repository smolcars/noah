import type { BtcMapPlace } from "~/lib/btcMap";

export type PlaceCategory = "all" | "food" | "shop" | "stay" | "atm" | "services";

export const PLACE_CATEGORIES: ReadonlyArray<{ value: PlaceCategory; label: string }> = [
  { value: "all", label: "All" },
  { value: "food", label: "Food & drink" },
  { value: "shop", label: "Shops" },
  { value: "stay", label: "Stay" },
  { value: "atm", label: "ATM" },
  { value: "services", label: "Services" },
];

const FOOD_ICON_PARTS = [
  "restaurant",
  "cafe",
  "dining",
  "bar",
  "bakery",
  "icecream",
  "liquor",
  "pizza",
  "wine",
] as const;
const SHOP_ICON_PARTS = ["storefront", "grocery", "mall", "florist", "gift", "hardware"] as const;
const STAY_ICON_PARTS = ["hotel", "chalet", "camp", "luggage", "bed"] as const;
const ATM_ICONS = new Set(["local_atm", "currency_exchange", "attach_money"]);

export const getPlaceCategory = (icon: string): Exclude<PlaceCategory, "all"> => {
  if (ATM_ICONS.has(icon)) {
    return "atm";
  }
  if (FOOD_ICON_PARTS.some((part) => icon.includes(part))) {
    return "food";
  }
  if (SHOP_ICON_PARTS.some((part) => icon.includes(part))) {
    return "shop";
  }
  if (STAY_ICON_PARTS.some((part) => icon.includes(part))) {
    return "stay";
  }
  return "services";
};

export const distanceKm = (
  from: { latitude: number; longitude: number },
  place: Pick<BtcMapPlace, "lat" | "lon">,
) => {
  const radians = (degrees: number) => (degrees * Math.PI) / 180;
  const latitudeDelta = radians(place.lat - from.latitude);
  const longitudeDelta = radians(place.lon - from.longitude);
  const startLatitude = radians(from.latitude);
  const endLatitude = radians(place.lat);
  const a =
    Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(startLatitude) * Math.cos(endLatitude) * Math.sin(longitudeDelta / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
};

export const formatDistance = (kilometers: number) =>
  kilometers < 1
    ? `${Math.max(1, Math.round(kilometers * 1000))} m`
    : `${kilometers.toFixed(1)} km`;
