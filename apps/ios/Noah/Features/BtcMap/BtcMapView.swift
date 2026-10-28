import SwiftUI

struct BtcMapView: View {
    @Environment(\.locale) private var locale

    @State private var model = BtcMapModel()
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var searchFocused: Bool
    @State private var wideLayout = false
    @State private var showPlaces = false
    @State private var placeFromList: BtcMapPlace?
    @State private var locatingTask: Task<Void, Never>?
    @ScaledMetric(relativeTo: .body) private var minimumPaneWidth: CGFloat = 260

    private struct CitySearchRequest: Equatable { let query: String; let center: [Double]; let focused: Bool }

    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: 0) {
                mapStack(height: geometry.size.height)
                if wideLayout, let selected = model.selected {
                    Divider()
                    BtcMapDetailView(place: selected, location: model.userLocation, repository: model.repository) {
                        model.selected = nil
                    }
                    .frame(width: min(380, geometry.size.width * 0.42))
                    .accessibilityIdentifier("map-place-pane")
                }
            }
            .onChange(of: AdaptiveLayout.expanded(width: geometry.size.width, minimumPaneWidth: minimumPaneWidth), initial: true) { _, expanded in
                wideLayout = expanded
            }
            .sheet(item: Binding(get: { wideLayout ? nil : model.selected }, set: { if !wideLayout { model.selected = $0 } })) { place in
                BtcMapDetailView(place: place, location: model.userLocation, repository: model.repository) { model.selected = nil }
                    .presentationDetents([.height(390), .large])
                    .presentationDragIndicator(.visible)
                    .presentationBackgroundInteraction(.enabled(upThrough: .height(390)))
            }
        }
        .sheet(isPresented: $showPlaces, onDismiss: {
            if let placeFromList { model.select(placeFromList); self.placeFromList = nil }
        }) {
            let places = model.placesInView
            NavigationStack {
                List(places) { place in
                    Button {
                        placeFromList = place
                        showPlaces = false
                    } label: {
                        Label { Text("\(place.name), \(L10n.text(place.category.label, locale: locale.identifier))") } icon: {
                            Image(systemName: place.category.symbol)
                        }
                    }
                }
                .overlay { if places.isEmpty { ContentUnavailableView("No places in view", systemImage: "mappin.slash") } }
                .navigationTitle("Places in view")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showPlaces = false } } }
            }
        }
        .navigationTitle("Bitcoin Map")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Places in view", systemImage: "list.bullet") {
                    searchFocused = false
                    showPlaces = true
                }
                .accessibilityIdentifier("btc-map-places-in-view")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("My location", systemImage: "location") {
                    searchFocused = false
                    locatingTask = Task { await model.locate() }
                }
                .disabled(model.isLocating)
            }
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { searchFocused = false }
            }
        }
        .tint(NoahColor.brand)
        .task { await model.load() }
        .task(id: model.isLoading) { await model.initialLocation() }
        .task(id: CitySearchRequest(query: model.query, center: model.viewport.center, focused: searchFocused)) { await model.searchCities() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.refresh() } }
        }
        .onDisappear { locatingTask?.cancel() }
        .alert("Location unavailable", isPresented: Binding(get: { model.alert != nil }, set: { if !$0 { model.alert = nil } })) {
            Button("OK", role: .cancel) { model.alert = nil }
        } message: { Text(LocalizedStringKey(model.alert ?? "")) }
    }

    private func mapStack(height: CGFloat) -> some View {
        ZStack(alignment: .top) {
            BtcMapCanvas(model: model, compactDetail: !wideLayout && model.selected != nil) { searchFocused = false }
                .ignoresSafeArea(edges: .bottom)
            VStack(spacing: 10) {
                searchControls(maxHeight: height * 0.45)
                    .readableWidth()
                Spacer(minLength: 0)
                if !searchFocused { bottomControls.readableWidth() }
            }
            .padding(12)
            if model.isLoading {
                ProgressView("Opening the bundled BTC Map…")
                    .padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                    .frame(maxHeight: .infinity)
            } else if let error = model.loadError {
                ContentUnavailableView {
                    Label("Could not open BTC Map", systemImage: "map")
                } description: { Text(LocalizedStringKey(error)) } actions: {
                    Button("Retry") { Task { await model.load() } }
                }
                .background(NoahColor.background)
            }
        }
    }

    private func searchControls(maxHeight: Double) -> some View {
        VStack(spacing: 10) {
            searchField

            if searchFocused && CitySearch.normalize(model.query).count >= 2 && model.selectedCity == nil {
                results(maxHeight: maxHeight)
            } else {
                categories
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField(
                "Search places or cities",
                text: Binding(get: { model.query }, set: { model.updateQuery($0) }),
                prompt: Text("Search places or cities").foregroundStyle(NoahColor.mutedForeground))
                .focused($searchFocused).autocorrectionDisabled().submitLabel(.search)
                .onSubmit {
                    if let city = model.cities.first { model.select(city); searchFocused = false }
                }
                .accessibilityIdentifier("btc-map-search")
            if !model.query.isEmpty {
                Button("Clear search", systemImage: "xmark.circle.fill") {
                    model.selectedCity = nil
                    model.query = ""
                }.labelStyle(.iconOnly).frame(minWidth: 44, minHeight: 44)
            }
        }
        .padding(.leading, 16).padding(.trailing, 4).frame(minHeight: 48)
        .glassCompat(in: Capsule())
    }

    private func results(maxHeight: Double) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("Locations").font(NoahFont.headline).padding()
                if model.cityError { Text("City search is unavailable.").font(NoahFont.footnote).padding(.horizontal) }
                ForEach(model.cities) { city in
                    resultRow(city.name, detail: city.secondaryLabel, symbol: "location") {
                        model.select(city); searchFocused = false
                    }
                }
                if !model.merchants.isEmpty {
                    Divider().padding(.horizontal)
                    Text("Bitcoin places").font(NoahFont.headline).padding()
                    ForEach(model.merchants) { place in
                        resultRow(place.name, detail: place.category.label, symbol: place.category.symbol) {
                            model.select(place); searchFocused = false
                        }
                    }
                }
                if model.cities.isEmpty && model.merchants.isEmpty {
                    Text("No matching cities or Bitcoin places").font(NoahFont.callout).padding()
                }
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .frame(maxHeight: maxHeight)
        .background(NoahColor.background, in: RoundedRectangle(cornerRadius: 16))
    }

    private var categories: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(PlaceCategory.allCases, id: \.self) { category in
                    Button { model.category = category } label: {
                        Label(LocalizedStringKey(category.label), systemImage: category.symbol)
                            .font(NoahFont.footnote)
                            .padding(.horizontal, 14).frame(minHeight: 44)
                            .foregroundStyle(model.category == category ? Color.black : NoahColor.foreground)
                            .background(model.category == category ? NoahColor.brand : Color.clear, in: Capsule())
                            .glassCompat(in: Capsule(), interactive: true)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(model.category == category ? .isSelected : [])
                }
            }
        }
    }

    private func resultRow(_ name: String, detail: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: symbol).frame(width: 24)
                VStack(alignment: .leading, spacing: 4) {
                    Text(LocalizedStringKey(name)).font(NoahFont.headline)
                    Text(LocalizedStringKey(detail)).font(NoahFont.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.footnote)
            }.padding().contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private var bottomControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            if model.basemapUnavailable {
                Button("Basemap unavailable · Retry") { model.styleRevision += 1 }
                    .font(NoahFont.footnote).padding(10).background(NoahColor.background, in: Capsule())
            }
            if model.selected == nil, let location = model.userLocation, !model.nearby.isEmpty {
                nearby(location)
            } else {
                HStack {
                    Text(L10n.plural("{_gt_value_4} places{_gt_value_3}", count: model.filtered.count, "", locale: locale.identifier)).font(NoahFont.footnote)
                    if model.isSyncing { ProgressView().controlSize(.small).accessibilityLabel("Syncing…") }
                    if model.isLocating { ProgressView().controlSize(.small).accessibilityLabel("Locating…") }
                    if model.syncUnavailable {
                        Button("Offline · Retry") { Task { await model.refresh() } }.font(NoahFont.footnote)
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 8).glassCompat(in: Capsule())
            }
            attribution
        }
    }

    private func nearby(_ location: [Double]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.nearby) { place in
                    Button { model.select(place) } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(place.name).font(NoahFont.headline).lineLimit(1)
                            Text(
                                "\(L10n.text(place.category.label, locale: locale.identifier)) · \(BtcMapDistance.format(BtcMapDistance.kilometers(from: location, to: place.center), locale: locale))")
                                .font(NoahFont.footnote).foregroundStyle(.secondary)
                        }
                        .frame(width: 220, alignment: .leading).padding(14)
                        .background(NoahColor.background, in: RoundedRectangle(cornerRadius: 16))
                    }.buttonStyle(.plain)
                }
            }
        }
    }

    private var attribution: some View {
        HStack(spacing: 6) {
            Link(#"© OpenStreetMap"#, destination: URL(literal: "https://www.openstreetmap.org/copyright"))
            Link(#"© OpenMapTiles"#, destination: URL(literal: "https://openmaptiles.org"))
            Link(#"BTC Map"#, destination: URL(literal: "https://btcmap.org"))
            Link(#"GeoNames"#, destination: URL(literal: "https://www.geonames.org"))
        }
        .font(.caption2).foregroundStyle(NoahColor.foreground)
        .padding(8).background(NoahColor.background, in: RoundedRectangle(cornerRadius: 8))
        .frame(maxWidth: .infinity, alignment: .trailing)
        // Keep MapLibre's attribution button unobscured at the lower left.
        .padding(.bottom, 28)
    }
}
