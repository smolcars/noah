import SwiftUI
@preconcurrency import MapLibre

struct BtcMapCanvas: UIViewRepresentable {
    let model: BtcMapModel
    var compactDetail = false
    var onTap: () -> Void
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeCoordinator() -> Coordinator { Coordinator(model: model, onTap: onTap) }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: styleURL)
        map.automaticallyAdjustsContentInset = false
        map.minimumZoomLevel = 1
        map.maximumZoomLevel = 19
        map.setCenter(CLLocationCoordinate2D(latitude: model.viewport.center[1], longitude: model.viewport.center[0]), zoomLevel: model.viewport.zoom, animated: false)
        map.delegate = context.coordinator
        map.accessibilityLabel = "Bitcoin merchant map"
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        tap.cancelsTouchesInView = false
        map.addGestureRecognizer(tap)
        context.coordinator.map = map
        context.coordinator.currentStyle = styleURL
        return map
    }

    private var styleURL: URL {
        URL(string: "https://tiles.openfreemap.org/styles/\(colorScheme == .dark ? "dark" : "liberty")")!
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.onTap = onTap
        let insets = UIEdgeInsets(top: compactDetail ? 120 : 0, left: 0, bottom: compactDetail ? 390 : 0, right: 0)
        if map.contentInset != insets { map.contentInset = insets }
        if coordinator.currentStyle != styleURL || coordinator.styleRevision != model.styleRevision {
            coordinator.styleRevision = model.styleRevision
            coordinator.currentStyle = styleURL
            map.styleURL = styleURL
        }
        coordinator.updateSources()
        if coordinator.cameraRevision != model.cameraRevision {
            coordinator.cameraRevision = model.cameraRevision
            map.setCenter(CLLocationCoordinate2D(latitude: model.viewport.center[1], longitude: model.viewport.center[0]),
                          zoomLevel: model.viewport.zoom, animated: !reduceMotion)
        }
    }

    static func dismantleUIView(_ map: MLNMapView, coordinator: Coordinator) { map.delegate = nil }

    @MainActor final class Coordinator: NSObject, @preconcurrency MLNMapViewDelegate {
        let model: BtcMapModel
        var onTap: () -> Void
        weak var map: MLNMapView?
        var currentStyle: URL?
        var cameraRevision = 0
        var styleRevision = 0
        private var revision = -1
        private var lastLocation: [Double]?
        private let orange = UIColor(red: 247/255, green: 147/255, blue: 26/255, alpha: 1)

        init(model: BtcMapModel, onTap: @escaping () -> Void) { self.model = model; self.onTap = onTap }

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            Task { @MainActor [model] in model.basemapUnavailable = mapView.styleURL?.isFileURL == true }
            if let background = style.layer(withIdentifier: "offline-background") as? MLNBackgroundStyleLayer {
                background.backgroundColor = NSExpression(forConstantValue: mapView.traitCollection.userInterfaceStyle == .dark ? UIColor.secondarySystemBackground.resolvedColor(with: UITraitCollection(userInterfaceStyle: .dark)) : UIColor.secondarySystemBackground.resolvedColor(with: UITraitCollection(userInterfaceStyle: .light)))
            }
            let source = MLNShapeSource(identifier: "btc-map-places", shape: nil,
                                        options: [.clustered: true, .clusterRadius: 46, .maximumZoomLevelForClustering: 14])
            style.addSource(source)
            let clusters = MLNCircleStyleLayer(identifier: "btc-map-clusters", source: source)
            clusters.predicate = NSPredicate(format: "point_count != nil")
            clusters.circleColor = NSExpression(forConstantValue: orange)
            clusters.circleRadius = NSExpression(format: "TERNARY(point_count >= 100, 27, TERNARY(point_count >= 20, 22, 18))")
            clusters.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            clusters.circleStrokeWidth = NSExpression(forConstantValue: 2)
            style.addLayer(clusters)
            let count = MLNSymbolStyleLayer(identifier: "btc-map-cluster-count", source: source)
            count.predicate = clusters.predicate
            count.text = NSExpression(forKeyPath: "point_count_abbreviated")
            count.textFontNames = NSExpression(forConstantValue: ["Noto Sans Regular"])
            count.textFontSize = NSExpression(forConstantValue: 12)
            count.textColor = NSExpression(forConstantValue: UIColor.white)
            count.textAllowsOverlap = NSExpression(forConstantValue: true)
            style.addLayer(count)
            let points = MLNCircleStyleLayer(identifier: "btc-map-points", source: source)
            points.predicate = NSPredicate(format: "point_count == nil")
            points.circleColor = NSExpression(forConstantValue: orange)
            points.circleRadius = NSExpression(forConstantValue: 7)
            points.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            points.circleStrokeWidth = NSExpression(forConstantValue: 2)
            style.addLayer(points)
            let location = MLNShapeSource(identifier: "btc-map-location", shape: nil, options: nil)
            style.addSource(location)
            for (name, radius, opacity) in [("halo", 16, 0.2), ("dot", 7, 1.0)] {
                let layer = MLNCircleStyleLayer(identifier: "btc-map-location-\(name)", source: location)
                layer.circleColor = NSExpression(forConstantValue: UIColor.systemBlue)
                layer.circleRadius = NSExpression(forConstantValue: radius)
                layer.circleOpacity = NSExpression(forConstantValue: opacity)
                if name == "dot" {
                    layer.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
                    layer.circleStrokeWidth = NSExpression(forConstantValue: 2)
                }
                style.addLayer(layer)
            }
            revision = -1
            lastLocation = nil
            updateSources()
        }

        func mapViewDidFailLoadingMap(_ mapView: MLNMapView, withError error: Error) {
            guard mapView.style == nil, mapView.styleURL?.isFileURL != true else { return }
            mapView.styleURL = Bundle.main.url(forResource: "btc-map-offline", withExtension: "json")
        }

        func updateSources() {
            guard let style = map?.style, let source = style.source(withIdentifier: "btc-map-places") as? MLNShapeSource else { return }
            if revision != model.revision {
                revision = model.revision
                let features = model.filtered.compactMap { place -> MLNPointFeature? in
                    guard (-90...90).contains(place.lat), (-180...180).contains(place.lon) else { return nil }
                    let feature = MLNPointFeature()
                    feature.coordinate = CLLocationCoordinate2D(latitude: place.lat, longitude: place.lon)
                    feature.identifier = NSNumber(value: place.id)
                    feature.attributes = ["placeId": place.id, "category": place.category.rawValue]
                    return feature
                }
                source.shape = MLNShapeCollectionFeature(shapes: features)
            }
            if lastLocation != model.userLocation {
                lastLocation = model.userLocation
                let feature = model.userLocation.map { coordinates in
                    let point = MLNPointFeature()
                    point.coordinate = CLLocationCoordinate2D(latitude: coordinates[1], longitude: coordinates[0])
                    return point
                }
                (style.source(withIdentifier: "btc-map-location") as? MLNShapeSource)?.shape = feature
            }
        }

        func mapView(_ mapView: MLNMapView, regionDidChangeWith reason: MLNCameraChangeReason, animated: Bool) {
            let viewport = BtcMapViewport(center: [mapView.centerCoordinate.longitude, mapView.centerCoordinate.latitude], zoom: mapView.zoomLevel)
            // Defer observable writes out of UIViewRepresentable's update pass.
            let userInteraction = !reason.intersection([.gesturePan, .gesturePinch, .gestureRotate, .gestureZoomIn, .gestureZoomOut, .gestureOneFingerZoom]).isEmpty
            let bounds = mapView.visibleCoordinateBounds
            Task { @MainActor [model] in
                model.visibleBounds = BtcMapBounds(west: bounds.sw.longitude, south: bounds.sw.latitude,
                                                  east: bounds.ne.longitude, north: bounds.ne.latitude)
                model.record(viewport, userInteraction: userInteraction)
            }
        }

        @objc func tapped(_ tap: UITapGestureRecognizer) {
            guard tap.state == .ended, let map else { return }
            onTap()
            let point = tap.location(in: map)
            let features = map.visibleFeatures(in: CGRect(x: point.x - 22, y: point.y - 22, width: 44, height: 44),
                                               styleLayerIdentifiers: ["btc-map-points", "btc-map-clusters"])
            guard let feature = features.first else { return }
            if let cluster = feature as? MLNPointFeatureCluster,
               let source = map.style?.source(withIdentifier: "btc-map-places") as? MLNShapeSource {
                let zoom = min(19, source.zoomLevel(forExpanding: cluster))
                model.move(to: BtcMapViewport(center: [cluster.coordinate.longitude, cluster.coordinate.latitude], zoom: zoom))
            } else if let id = feature.attribute(forKey: "placeId") as? NSNumber,
                      let place = model.places.first(where: { $0.id == id.intValue }) {
                model.select(place)
            }
        }
    }
}
