import {
  Camera,
  GeoJSONSource,
  Layer,
  Map,
  type CameraRef,
  type GeoJSONSourceRef,
  type PressEventWithFeatures,
  type ViewStateChangeEvent,
} from "@maplibre/maplibre-react-native";
import Icon from "@react-native-vector-icons/ionicons";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useNavigation } from "@react-navigation/native";
import * as Location from "expo-location";
import { useDeferredValue, useEffect, useRef, useState, type ComponentProps } from "react";
import {
  Alert,
  Keyboard,
  Linking,
  Platform,
  Pressable,
  ScrollView,
  Share,
  TextInput,
  View,
  type NativeSyntheticEvent,
} from "react-native";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { Text } from "~/components/ui/text";
import {
  FacebookBrandIcon,
  InstagramBrandIcon,
  TelegramBrandIcon,
  XBrandIcon,
} from "~/components/BrandIcons";
import { useBtcMapPlace, useBtcMapPlaces } from "~/hooks/useBtcMap";
import { useCitySearch } from "~/hooks/useCitySearch";
import { useTheme } from "~/hooks/useTheme";
import {
  cityDisplayLabel,
  citySecondaryLabel,
  normalizeSearchText,
  searchCities,
  type CitySearchEntry,
} from "~/lib/citySearch";
import logger from "~/lib/log";
import { mmkv } from "~/lib/mmkv";
import { type BtcMapPlace, type BtcMapPlaceDetail } from "~/lib/btcMap";
import {
  type BtcMapViewport,
  distanceKm,
  formatDistance,
  getPlaceCategory,
  parseBtcMapViewport,
  PLACE_CATEGORIES,
  type PlaceCategory,
} from "~/lib/btcMapUtils";
import { COLORS } from "~/lib/styleConstants";
import type { HomeStackParamList } from "~/Navigators";

const log = logger("BtcMapScreen");
const LIGHT_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty";
const DARK_MAP_STYLE = "https://tiles.openfreemap.org/styles/dark";
const EMPTY_PLACES: BtcMapPlace[] = [];
const PLACE_DETAIL_SHEET_HEIGHT = 390;
const DEFAULT_VIEWPORT: BtcMapViewport = { center: [0, 20], zoom: 1.5 };
const VIEWPORT_STORAGE_KEY = "btc-map-viewport-v1";

type Coordinates = { latitude: number; longitude: number };
type ForegroundLocationResult =
  | { status: "granted"; coordinates: Coordinates }
  | { status: "denied" }
  | { status: "error"; error: unknown };
type PlaceFeatureProperties = {
  placeId: number;
  category: Exclude<PlaceCategory, "all">;
};

const categoryLabel = (place: BtcMapPlace) =>
  PLACE_CATEGORIES.find((category) => category.value === getPlaceCategory(place.icon))?.label ??
  "Place";

const dateLabel = (value: string) => {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(undefined, { month: "short", year: "numeric" }).format(date);
};

const isCurrentBoost = (place: BtcMapPlace) =>
  place.boosted_until !== undefined && new Date(place.boosted_until).getTime() > Date.now();

const openUrl = async (url: string) => {
  try {
    await Linking.openURL(url);
  } catch (error) {
    Alert.alert("Cannot open link", "No app is available to open this link.");
    throw error;
  }
};

const loadStoredBtcMapViewport = (): BtcMapViewport | undefined => {
  try {
    const serialized = mmkv.getString(VIEWPORT_STORAGE_KEY);
    if (!serialized) {
      return undefined;
    }
    const viewport = parseBtcMapViewport(JSON.parse(serialized) as unknown);
    if (!viewport) {
      mmkv.remove(VIEWPORT_STORAGE_KEY);
      log.w("Ignored an invalid saved BTC Map viewport");
    }
    return viewport;
  } catch (error) {
    log.w("Could not load the saved BTC Map viewport", [error]);
    return undefined;
  }
};

const saveBtcMapViewport = (viewport: BtcMapViewport) => {
  try {
    mmkv.set(VIEWPORT_STORAGE_KEY, JSON.stringify(viewport));
  } catch (error) {
    log.w("Could not save the BTC Map viewport", [error]);
  }
};

const getForegroundLocation = async (): Promise<ForegroundLocationResult> => {
  try {
    const permission = await Location.requestForegroundPermissionsAsync();
    if (permission.status !== Location.PermissionStatus.GRANTED) {
      return { status: "denied" };
    }
    let position = await Location.getLastKnownPositionAsync({ maxAge: 5 * 60 * 1000 });
    if (!position) {
      position = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.Balanced,
      });
    }
    return {
      status: "granted",
      coordinates: {
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
      },
    };
  } catch (error) {
    return { status: "error", error };
  }
};

const searchPlaces = (places: ReadonlyArray<BtcMapPlace>, query: string, limit: number) =>
  query.length < 2
    ? []
    : places
        .flatMap((place) => {
          const name = normalizeSearchText(place.name);
          const score =
            name === query ? 0 : name.startsWith(query) ? 1 : name.includes(query) ? 2 : 3;
          return score === 3 ? [] : [{ place, score }];
        })
        .sort(
          (left, right) =>
            left.score - right.score || left.place.name.localeCompare(right.place.name),
        )
        .slice(0, limit)
        .map(({ place }) => place);

function DetailAction({
  icon,
  label,
  onPress,
}: {
  icon: ComponentProps<typeof Icon>["name"];
  label: string;
  onPress: () => void;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      className="min-w-20 items-center gap-1.5 rounded-2xl border border-border bg-secondary px-3 py-3"
      style={({ pressed }) => ({ opacity: pressed ? 0.7 : 1 })}
    >
      <Icon name={icon} size={20} color={colors.foreground} />
      <Text className="text-xs font-semibold">{label}</Text>
    </Pressable>
  );
}

function SearchResultRow({
  icon,
  label,
  detail,
  onPress,
}: {
  icon: ComponentProps<typeof Icon>["name"];
  label: string;
  detail: string;
  onPress: () => void;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${label}, ${detail}`}
      onPress={onPress}
      className="flex-row items-center gap-3 px-4 py-3"
      style={({ pressed }) => ({ opacity: pressed ? 0.65 : 1 })}
    >
      <View className="h-9 w-9 items-center justify-center rounded-full bg-secondary">
        <Icon name={icon} size={18} color={colors.foreground} />
      </View>
      <View className="flex-1">
        <Text className="font-semibold" numberOfLines={1}>
          {label}
        </Text>
        <Text className="mt-0.5 text-xs text-muted-foreground" numberOfLines={1}>
          {detail}
        </Text>
      </View>
      <Icon name="chevron-forward" size={16} color={colors.mutedForeground} />
    </Pressable>
  );
}

function PlaceDetailPanel({
  place,
  userLocation,
  onClose,
}: {
  place: BtcMapPlace;
  userLocation: Coordinates | undefined;
  onClose: () => void;
}) {
  const { colors } = useTheme();
  const detailQuery = useBtcMapPlace(place.id, place.comments);
  const detail: BtcMapPlaceDetail = detailQuery.data?.place ?? place;
  const distance = userLocation ? distanceKm(userLocation, place) : undefined;

  const openDirections = () => {
    const label = encodeURIComponent(place.name);
    const url =
      Platform.OS === "ios"
        ? `http://maps.apple.com/?daddr=${place.lat},${place.lon}&q=${label}`
        : `geo:${place.lat},${place.lon}?q=${place.lat},${place.lon}(${label})`;
    void openUrl(url).catch((error) => log.w("Could not open directions", [error]));
  };

  const sharePlace = () => {
    void Share.share({
      message: `${place.name}\nhttps://btcmap.org/merchant/${place.id}`,
    }).catch((error) => log.w("Could not share BTC Map place", [error]));
  };

  return (
    <View className="px-1 pb-2">
      <View className="flex-row items-start justify-between gap-4">
        <View className="flex-1">
          <View className="mb-1 flex-row flex-wrap items-center gap-2">
            <Text className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {categoryLabel(place)}
            </Text>
            {isCurrentBoost(place) && (
              <Text className="text-xs font-bold text-[#f7931a]">Featured</Text>
            )}
          </View>
          <Text className="text-xl font-bold" numberOfLines={2}>
            {detail.name}
          </Text>
          <View className="mt-1 flex-row flex-wrap items-center gap-x-2">
            {distance !== undefined && (
              <Text className="text-sm text-muted-foreground">{formatDistance(distance)}</Text>
            )}
            {detail.verified_at && (
              <Text className="text-sm text-muted-foreground">
                Verified {dateLabel(detail.verified_at)}
              </Text>
            )}
          </View>
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Close place details"
          onPress={onClose}
          className="h-9 w-9 items-center justify-center rounded-full bg-secondary"
        >
          <Icon name="close" size={20} color={colors.foreground} />
        </Pressable>
      </View>

      <View className="mt-4 flex-row gap-2">
        <DetailAction icon="navigate-outline" label="Directions" onPress={openDirections} />
        {detail.website && (
          <DetailAction
            icon="globe-outline"
            label="Website"
            onPress={() =>
              void openUrl(detail.website!).catch((error) =>
                log.w("Could not open merchant website", [error]),
              )
            }
          />
        )}
        {detail.phone && (
          <DetailAction
            icon="call-outline"
            label="Call"
            onPress={() =>
              void openUrl(`tel:${detail.phone}`).catch((error) =>
                log.w("Could not open phone app", [error]),
              )
            }
          />
        )}
        <DetailAction icon="share-outline" label="Share" onPress={sharePlace} />
      </View>

      {detailQuery.isLoading && (
        <View className="mt-4 flex-row items-center gap-2">
          <NoahActivityIndicator size="small" />
          <Text className="text-sm text-muted-foreground">Loading details…</Text>
        </View>
      )}

      {detailQuery.isError && (
        <Text className="mt-4 text-sm text-muted-foreground">
          Live details are unavailable. The bundled map still works offline.
        </Text>
      )}

      {detail.address && (
        <View className="mt-4 flex-row gap-3">
          <Icon name="location-outline" size={18} color={colors.mutedForeground} />
          <Text className="flex-1 text-sm leading-5">{detail.address}</Text>
        </View>
      )}
      {detail.opening_hours && (
        <View className="mt-3 flex-row gap-3">
          <Icon name="time-outline" size={18} color={colors.mutedForeground} />
          <Text className="flex-1 text-sm leading-5">{detail.opening_hours}</Text>
        </View>
      )}
      {detail.description && (
        <Text className="mt-4 text-sm leading-5 text-muted-foreground">{detail.description}</Text>
      )}

      {detailQuery.data && <PaymentMethods place={detailQuery.data.place} />}
      {detailQuery.data && <SocialLinks place={detailQuery.data.place} />}

      {detailQuery.data?.comments.map((comment) => (
        <View key={comment.id} className="mt-4 border-l-2 border-[#f7931a] pl-3">
          <Text className="text-sm leading-5">{comment.text}</Text>
          <Text className="mt-1 text-xs text-muted-foreground">
            {dateLabel(comment.created_at)}
          </Text>
        </View>
      ))}
    </View>
  );
}

function SocialLinks({ place }: { place: BtcMapPlaceDetail }) {
  const { colors } = useTheme();
  const links: Array<{
    label: string;
    icon: "x" | "facebook" | "instagram" | "telegram";
    url: string | undefined;
  }> = [
    { label: "X", icon: "x", url: place.twitter },
    { label: "Facebook", icon: "facebook", url: place.facebook },
    { label: "Instagram", icon: "instagram", url: place.instagram },
    { label: "Telegram", icon: "telegram", url: place.telegram },
  ];
  const availableLinks = links.filter(
    (link): link is typeof link & { url: string } => link.url !== undefined,
  );

  if (availableLinks.length === 0) {
    return null;
  }

  return (
    <View className="mt-4">
      <Text className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Follow
      </Text>
      <View className="flex-row gap-2">
        {availableLinks.map((link) => (
          <Pressable
            key={link.label}
            accessibilityRole="link"
            accessibilityLabel={`Open ${link.label}`}
            onPress={() =>
              void openUrl(link.url).catch((error) =>
                log.w("Could not open merchant social profile", [error]),
              )
            }
            className="h-11 w-11 items-center justify-center rounded-full border border-border bg-secondary"
            style={({ pressed }) => ({ opacity: pressed ? 0.7 : 1 })}
          >
            {link.icon === "x" && <XBrandIcon size={18} color={colors.foreground} />}
            {link.icon === "facebook" && <FacebookBrandIcon size={20} />}
            {link.icon === "instagram" && <InstagramBrandIcon size={20} />}
            {link.icon === "telegram" && <TelegramBrandIcon size={20} />}
          </Pressable>
        ))}
      </View>
    </View>
  );
}

function PaymentMethods({ place }: { place: BtcMapPlaceDetail }) {
  const methods = [
    place["osm:payment:lightning"] === "yes" ? "Lightning" : undefined,
    place["osm:payment:lightning_contactless"] === "yes" ? "Contactless" : undefined,
    place["osm:payment:onchain"] === "yes" ? "On-chain" : undefined,
    place.payment_provider,
  ].filter((method): method is string => Boolean(method));

  if (methods.length === 0 && !place.required_app_url) {
    return null;
  }

  return (
    <View className="mt-4">
      <Text className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Pay with
      </Text>
      <View className="flex-row flex-wrap gap-2">
        {methods.map((method) => (
          <View key={method} className="rounded-full bg-[#f7931a]/15 px-3 py-1.5">
            <Text className="text-xs font-semibold text-[#d97400]">{method}</Text>
          </View>
        ))}
      </View>
      {place.required_app_url && (
        <Pressable
          accessibilityRole="link"
          onPress={() =>
            void openUrl(place.required_app_url!).catch((error) =>
              log.w("Could not open required payment app", [error]),
            )
          }
        >
          <Text className="mt-3 text-sm font-semibold text-[#d97400]">
            This place may require another payment app
          </Text>
        </Pressable>
      )}
    </View>
  );
}

function NearbyPlaces({
  places,
  location,
  bottom,
  onSelect,
}: {
  places: BtcMapPlace[];
  location: Coordinates;
  bottom: number;
  onSelect: (place: BtcMapPlace) => void;
}) {
  const nearby = places
    .map((place) => ({ place, distance: distanceKm(location, place) }))
    .sort((left, right) => left.distance - right.distance)
    .slice(0, 8);

  return (
    <View className="absolute left-0 right-0" style={{ bottom }}>
      <Text className="mb-2 ml-4 text-sm font-bold text-white" style={{ textShadowRadius: 4 }}>
        Nearby
      </Text>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerClassName="gap-2 px-3"
      >
        {nearby.map(({ place, distance }) => (
          <Pressable
            key={place.id}
            accessibilityRole="button"
            accessibilityLabel={`Open ${place.name}`}
            onPress={() => onSelect(place)}
            className="w-52 rounded-2xl border border-border bg-background px-4 py-3"
            style={({ pressed }) => ({ opacity: pressed ? 0.75 : 1 })}
          >
            <Text className="font-bold" numberOfLines={1}>
              {place.name}
            </Text>
            <Text className="mt-1 text-xs text-muted-foreground">
              {categoryLabel(place)} · {formatDistance(distance)}
            </Text>
          </Pressable>
        ))}
      </ScrollView>
    </View>
  );
}

export default function BtcMapScreen() {
  const navigation = useNavigation<NativeStackNavigationProp<HomeStackParamList>>();
  const { colors, isDark } = useTheme();
  const insets = useSafeAreaInsets();
  const tabBarHeight = useBottomTabBarHeight();
  const cameraRef = useRef<CameraRef>(null);
  const placesSourceRef = useRef<GeoJSONSourceRef>(null);
  const snapshotQuery = useBtcMapPlaces();
  const cityIndexQuery = useCitySearch();
  const [initialViewport] = useState(loadStoredBtcMapViewport);
  const [mapViewport, setMapViewport] = useState(initialViewport ?? DEFAULT_VIEWPORT);
  const canPersistViewportRef = useRef(initialViewport !== undefined);
  const initialLocationAttemptRef = useRef(false);
  const [category, setCategory] = useState<PlaceCategory>("all");
  const [search, setSearch] = useState("");
  const deferredSearch = useDeferredValue(search);
  const normalizedSearch = normalizeSearchText(deferredSearch);
  const [isSearchFocused, setIsSearchFocused] = useState(false);
  const [selectedCity, setSelectedCity] = useState<CitySearchEntry>();
  const [selectedPlaceId, setSelectedPlaceId] = useState<number>();
  const [isDetailSheetOpen, setIsDetailSheetOpen] = useState(false);
  const [userLocation, setUserLocation] = useState<Coordinates>();
  const [isLocating, setIsLocating] = useState(false);

  useEffect(() => {
    if (initialViewport || !snapshotQuery.isSuccess || initialLocationAttemptRef.current) {
      return;
    }

    initialLocationAttemptRef.current = true;
    let cancelled = false;
    let finished = false;
    setIsLocating(true);
    void getForegroundLocation().then((result) => {
      if (cancelled) {
        return;
      }
      finished = true;
      setIsLocating(false);
      if (result.status === "error") {
        log.w("Could not get initial foreground location", [result.error]);
        return;
      }
      if (result.status !== "granted" || canPersistViewportRef.current) {
        return;
      }

      const viewport: BtcMapViewport = {
        center: [result.coordinates.longitude, result.coordinates.latitude],
        zoom: 13,
      };
      canPersistViewportRef.current = true;
      saveBtcMapViewport(viewport);
      setMapViewport(viewport);
      setUserLocation(result.coordinates);
      cameraRef.current?.easeTo({ ...viewport, duration: 700 });
    });

    return () => {
      cancelled = true;
      if (!finished) {
        initialLocationAttemptRef.current = false;
      }
    };
  }, [initialViewport, snapshotQuery.isSuccess]);

  const places = snapshotQuery.data?.places ?? EMPTY_PLACES;
  const matchingCategoryPlaces = places.filter(
    (place) => category === "all" || getPlaceCategory(place.icon) === category,
  );
  const isAutocompleteOpen =
    isSearchFocused && normalizedSearch.length >= 2 && selectedCity === undefined;
  const cityResults = searchCities(
    cityIndexQuery.data?.cities ?? [],
    normalizedSearch,
    3,
    mapViewport.center,
  );
  const merchantResults = searchPlaces(matchingCategoryPlaces, normalizedSearch, 3);
  const filteredPlaces = places.filter(
    (place) =>
      (category === "all" || getPlaceCategory(place.icon) === category) &&
      (selectedCity !== undefined ||
        normalizedSearch.length === 0 ||
        normalizeSearchText(place.name).includes(normalizedSearch)),
  );
  const placesGeoJson: GeoJSON.FeatureCollection<GeoJSON.Point, PlaceFeatureProperties> = {
    type: "FeatureCollection",
    features: filteredPlaces.map((place) => ({
      type: "Feature",
      id: place.id,
      geometry: { type: "Point", coordinates: [place.lon, place.lat] },
      properties: { placeId: place.id, category: getPlaceCategory(place.icon) },
    })),
  };
  const selectedPlace = places.find((place) => place.id === selectedPlaceId);
  const userLocationGeoJson: GeoJSON.FeatureCollection<GeoJSON.Point> = {
    type: "FeatureCollection",
    features: userLocation
      ? [
          {
            type: "Feature",
            geometry: {
              type: "Point",
              coordinates: [userLocation.longitude, userLocation.latitude],
            },
            properties: {},
          },
        ]
      : [],
  };

  const selectPlace = (place: BtcMapPlace) => {
    const viewport: BtcMapViewport = { center: [place.lon, place.lat], zoom: 15 };
    canPersistViewportRef.current = true;
    saveBtcMapViewport(viewport);
    setMapViewport(viewport);
    setIsSearchFocused(false);
    Keyboard.dismiss();
    setSelectedPlaceId(place.id);
    setIsDetailSheetOpen(true);
    cameraRef.current?.easeTo({ ...viewport, duration: 650 });
  };

  const handleRegionDidChange = (event: NativeSyntheticEvent<ViewStateChangeEvent>) => {
    if (!canPersistViewportRef.current && !event.nativeEvent.userInteraction) {
      return;
    }
    const viewport = parseBtcMapViewport({
      center: event.nativeEvent.center,
      zoom: event.nativeEvent.zoom,
    });
    if (!viewport) {
      return;
    }
    canPersistViewportRef.current = true;
    saveBtcMapViewport(viewport);
    setMapViewport(viewport);
  };

  const handlePlacePress = async (event: NativeSyntheticEvent<PressEventWithFeatures>) => {
    const feature = event.nativeEvent.features[0];
    if (!feature || feature.geometry.type !== "Point") {
      return;
    }
    const properties = feature.properties;
    const clusterId = properties?.cluster_id;
    if (typeof clusterId === "number") {
      const zoom = await placesSourceRef.current?.getClusterExpansionZoom(clusterId);
      if (zoom !== undefined) {
        const viewport = parseBtcMapViewport({
          center: feature.geometry.coordinates,
          zoom,
        });
        if (viewport) {
          canPersistViewportRef.current = true;
          saveBtcMapViewport(viewport);
          setMapViewport(viewport);
          cameraRef.current?.easeTo({ ...viewport, duration: 450 });
        }
      }
      return;
    }
    const placeId = properties?.placeId;
    const place =
      typeof placeId === "number" ? places.find((item) => item.id === placeId) : undefined;
    if (place) {
      selectPlace(place);
    }
  };

  const handleLocate = async () => {
    setIsLocating(true);
    const result = await getForegroundLocation();
    setIsLocating(false);
    if (result.status === "denied") {
      Alert.alert(
        "Location is off",
        "You can still browse the full map. Enable location to see nearby places.",
      );
      return;
    }
    if (result.status === "error") {
      log.w("Could not get foreground location", [result.error]);
      Alert.alert("Location unavailable", "Noah could not determine your current location.");
      return;
    }
    const viewport: BtcMapViewport = {
      center: [result.coordinates.longitude, result.coordinates.latitude],
      zoom: 13,
    };
    canPersistViewportRef.current = true;
    saveBtcMapViewport(viewport);
    setMapViewport(viewport);
    setSearch("");
    setSelectedCity(undefined);
    setUserLocation(result.coordinates);
    cameraRef.current?.easeTo({ ...viewport, duration: 700 });
  };

  const selectCity = (city: CitySearchEntry) => {
    const viewport: BtcMapViewport = { center: city.center, zoom: 11 };
    canPersistViewportRef.current = true;
    saveBtcMapViewport(viewport);
    setMapViewport(viewport);
    setSearch(cityDisplayLabel(city));
    setSelectedCity(city);
    setSelectedPlaceId(undefined);
    setUserLocation(undefined);
    setIsSearchFocused(false);
    Keyboard.dismiss();
    cameraRef.current?.easeTo({ ...viewport, duration: 700 });
  };

  if (snapshotQuery.isLoading) {
    return (
      <View className="flex-1 items-center justify-center bg-background">
        <NoahActivityIndicator />
        <Text className="mt-3 text-muted-foreground">Opening the bundled BTC Map…</Text>
      </View>
    );
  }

  if (snapshotQuery.isError) {
    return (
      <View className="flex-1 items-center justify-center bg-background px-8">
        <Text className="text-center text-xl font-bold">Could not open BTC Map</Text>
        <Text className="mt-2 text-center text-muted-foreground">
          The bundled places file could not be read.
        </Text>
        <Pressable
          accessibilityRole="button"
          onPress={() => void snapshotQuery.refetch()}
          className="mt-5 rounded-full bg-primary px-5 py-3"
        >
          <Text className="font-semibold text-primary-foreground">Try again</Text>
        </Pressable>
      </View>
    );
  }

  const panelBottom = tabBarHeight + 10;
  const attributionBottom = selectedPlace
    ? PLACE_DETAIL_SHEET_HEIGHT + 10
    : panelBottom + (userLocation ? 145 : 8);

  return (
    <View className="flex-1 bg-background">
      <Map
        mapStyle={isDark ? DARK_MAP_STYLE : LIGHT_MAP_STYLE}
        style={{ flex: 1 }}
        attribution={false}
        logo
        logoPosition={{ bottom: panelBottom + 4, left: 8 }}
        compass
        compassPosition={{ top: insets.top + 118, right: 14 }}
        touchPitch={false}
        onRegionDidChange={handleRegionDidChange}
        onPress={() => {
          setIsSearchFocused(false);
          Keyboard.dismiss();
        }}
      >
        <Camera
          ref={cameraRef}
          initialViewState={initialViewport ?? DEFAULT_VIEWPORT}
          minZoom={1}
          maxZoom={19}
        />
        <GeoJSONSource
          ref={placesSourceRef}
          id="btc-map-places"
          data={placesGeoJson}
          cluster
          clusterRadius={46}
          clusterMaxZoom={14}
          onPress={(event) => void handlePlacePress(event)}
        >
          <Layer
            id="btc-map-clusters"
            type="circle"
            filter={["has", "point_count"]}
            paint={{
              "circle-color": COLORS.BITCOIN_ORANGE,
              "circle-radius": ["step", ["get", "point_count"], 18, 20, 22, 100, 27],
              "circle-stroke-color": "#ffffff",
              "circle-stroke-width": 2,
            }}
          />
          <Layer
            id="btc-map-cluster-count"
            type="symbol"
            filter={["has", "point_count"]}
            layout={{
              "text-field": ["get", "point_count_abbreviated"],
              "text-font": ["Noto Sans Regular"],
              "text-size": 12,
            }}
            paint={{ "text-color": "#ffffff" }}
          />
          <Layer
            id="btc-map-points"
            type="circle"
            filter={["!", ["has", "point_count"]]}
            paint={{
              "circle-color": COLORS.BITCOIN_ORANGE,
              "circle-radius": 7,
              "circle-stroke-color": "#ffffff",
              "circle-stroke-width": 2,
            }}
          />
        </GeoJSONSource>
        <GeoJSONSource id="btc-map-user-location" data={userLocationGeoJson}>
          <Layer
            id="btc-map-user-location-halo"
            type="circle"
            paint={{ "circle-color": "#2f80ed", "circle-opacity": 0.2, "circle-radius": 16 }}
          />
          <Layer
            id="btc-map-user-location-dot"
            type="circle"
            paint={{
              "circle-color": "#2f80ed",
              "circle-radius": 7,
              "circle-stroke-color": "#ffffff",
              "circle-stroke-width": 2,
            }}
          />
        </GeoJSONSource>
      </Map>

      <View className="absolute left-3 right-3" style={{ top: insets.top + 8 }}>
        <View className="flex-row items-center gap-2">
          <NativeNoahBackButton onPress={() => navigation.goBack()} />
          <View className="h-12 flex-1 flex-row items-center rounded-full border border-border bg-background px-4">
            <Icon name="search" size={19} color={colors.mutedForeground} />
            <TextInput
              value={search}
              onChangeText={(value) => {
                setSearch(value);
                setSelectedCity(undefined);
              }}
              onFocus={() => setIsSearchFocused(true)}
              onSubmitEditing={() => {
                const city = cityResults[0];
                if (city) {
                  selectCity(city);
                }
              }}
              accessibilityLabel="Search places or cities"
              placeholder="Search places or cities"
              placeholderTextColor={colors.mutedForeground}
              className="ml-2 flex-1 text-base text-foreground"
              returnKeyType="search"
              autoCorrect={false}
            />
            {search.length > 0 && (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Clear search"
                onPress={() => {
                  setSearch("");
                  setSelectedCity(undefined);
                }}
              >
                <Icon name="close-circle" size={20} color={colors.mutedForeground} />
              </Pressable>
            )}
          </View>
        </View>
        {isAutocompleteOpen ? (
          <ScrollView
            keyboardShouldPersistTaps="handled"
            bounces={false}
            showsVerticalScrollIndicator={false}
            className="mt-2 max-h-80 rounded-3xl border border-border bg-background"
          >
            <Text className="px-4 pb-1 pt-4 text-[11px] font-bold uppercase tracking-wider text-muted-foreground">
              Locations
            </Text>
            {cityIndexQuery.isLoading && (
              <View className="flex-row items-center gap-2 px-4 py-3">
                <NoahActivityIndicator size="small" />
                <Text className="text-sm text-muted-foreground">Loading city search…</Text>
              </View>
            )}
            {cityResults.map((city) => (
              <SearchResultRow
                key={city.id}
                icon="location-outline"
                label={city.name}
                detail={citySecondaryLabel(city)}
                onPress={() => selectCity(city)}
              />
            ))}

            {merchantResults.length > 0 && (
              <>
                <View className="mx-4 h-px bg-border" />
                <Text className="px-4 pb-1 pt-4 text-[11px] font-bold uppercase tracking-wider text-muted-foreground">
                  Bitcoin places
                </Text>
                {merchantResults.map((place) => (
                  <SearchResultRow
                    key={place.id}
                    icon="storefront-outline"
                    label={place.name}
                    detail={categoryLabel(place)}
                    onPress={() => selectPlace(place)}
                  />
                ))}
              </>
            )}

            {!cityIndexQuery.isLoading &&
              cityResults.length === 0 &&
              merchantResults.length === 0 && (
                <Text className="px-4 pb-5 pt-2 text-sm text-muted-foreground">
                  No matching cities or Bitcoin places
                </Text>
              )}
          </ScrollView>
        ) : (
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerClassName="mt-2 gap-2 pr-4"
          >
            {PLACE_CATEGORIES.map((item) => {
              const selected = category === item.value;
              return (
                <Pressable
                  key={item.value}
                  accessibilityRole="button"
                  accessibilityState={{ selected }}
                  onPress={() => setCategory(item.value)}
                  className={`rounded-full border px-4 py-2 ${
                    selected ? "border-[#f7931a] bg-[#f7931a]" : "border-border bg-background"
                  }`}
                >
                  <Text className={`text-sm font-semibold ${selected ? "text-white" : ""}`}>
                    {item.label}
                  </Text>
                </Pressable>
              );
            })}
          </ScrollView>
        )}
      </View>

      {!isAutocompleteOpen && (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Find places near me"
          disabled={isLocating}
          onPress={() => void handleLocate()}
          className="absolute right-3 h-12 w-12 items-center justify-center rounded-full border border-border bg-background"
          style={{ top: insets.top + 112, opacity: isLocating ? 0.6 : 1 }}
        >
          {isLocating ? (
            <NoahActivityIndicator size="small" />
          ) : (
            <Icon name="locate" size={22} color={COLORS.BITCOIN_ORANGE} />
          )}
        </Pressable>
      )}

      <View
        pointerEvents="box-none"
        className="absolute right-3 flex-row items-center gap-1 rounded-full bg-black/60 px-2.5 py-1.5"
        style={{ bottom: attributionBottom }}
      >
        <Pressable
          accessibilityRole="link"
          onPress={() =>
            void openUrl("https://www.openstreetmap.org/copyright").catch((error) =>
              log.w("Could not open map attribution", [error]),
            )
          }
        >
          <Text className="text-[10px] text-white">© OpenStreetMap</Text>
        </Pressable>
        <Text className="text-[10px] text-white">·</Text>
        <Pressable
          accessibilityRole="link"
          onPress={() =>
            void openUrl("https://openmaptiles.org").catch((error) =>
              log.w("Could not open OpenMapTiles attribution", [error]),
            )
          }
        >
          <Text className="text-[10px] text-white">© OpenMapTiles</Text>
        </Pressable>
        <Text className="text-[10px] text-white">·</Text>
        <Pressable
          accessibilityRole="link"
          onPress={() =>
            void openUrl("https://btcmap.org").catch((error) =>
              log.w("Could not open BTC Map attribution", [error]),
            )
          }
        >
          <Text className="text-[10px] text-white">BTC Map</Text>
        </Pressable>
        <Text className="text-[10px] text-white">·</Text>
        <Pressable
          accessibilityRole="link"
          onPress={() =>
            void openUrl("https://www.geonames.org").catch((error) =>
              log.w("Could not open GeoNames attribution", [error]),
            )
          }
        >
          <Text className="text-[10px] text-white">GeoNames</Text>
        </Pressable>
      </View>

      {!selectedPlace && userLocation && (
        <NearbyPlaces
          places={filteredPlaces}
          location={userLocation}
          bottom={panelBottom}
          onSelect={selectPlace}
        />
      )}

      {!selectedPlace && !userLocation && (
        <View
          className="absolute left-3 rounded-full bg-black/65 px-3 py-2"
          style={{ bottom: panelBottom }}
        >
          <Text className="text-xs font-semibold text-white">
            {filteredPlaces.length.toLocaleString()} places
            {snapshotQuery.isSyncing ? " · Updating…" : ""}
          </Text>
        </View>
      )}

      {selectedPlace && (
        <AppBottomSheet
          isOpen={isDetailSheetOpen}
          onClose={() => setIsDetailSheetOpen(false)}
          onDismiss={() => setSelectedPlaceId(undefined)}
          detents={[0, PLACE_DETAIL_SHEET_HEIGHT]}
          scrimColor="rgba(0, 0, 0, 0.18)"
          scrollable
          liquidGlass
        >
          <PlaceDetailPanel
            place={selectedPlace}
            userLocation={userLocation}
            onClose={() => setIsDetailSheetOpen(false)}
          />
        </AppBottomSheet>
      )}
    </View>
  );
}
