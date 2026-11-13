import CoreImage
import MapKit
import MosimoCore
import SwiftUI

/// Ordinary posts and their areas appear from this web-mercator zoom (a city or so); official alerts show at every zoom.
let postMinZoom = 10.0

/// Web-mercator zoom level of [v]'s current camera (MapKit has no zoom of its own; derived from the longitude span).
@MainActor func webZoom(_ v: MKMapView) -> Double {
    guard v.bounds.width > 0, v.region.span.longitudeDelta > 0 else { return 5 }
    return log2(360 * Double(v.bounds.width) / 256 / v.region.span.longitudeDelta)
}

/// GSI "pale" raster tiles turned dark like the Android MapLibre style (raster-brightness-min 0.55 / max 0.04, saturation -1).
final class GsiTileOverlay: MKTileOverlay {
    /// Viewed tiles stay on disk so the map keeps working offline (ambient cache, as Android's 200 MB MapLibre cache).
    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("gsi-tiles")
        c.urlCache = URLCache(memoryCapacity: 16 << 20, diskCapacity: 200 << 20, directory: dir)
        c.requestCachePolicy = .returnCacheDataElseLoad
        return URLSession(configuration: c)
    }()
    // Operate on the tile's own sRGB values, not linearized ones, so the matrix matches MapLibre's raster paint.
    private static let context = CIContext(options: [.workingColorSpace: NSNull()])

    init() {
        super.init(urlTemplate: "https://cyberjapandata.gsi.go.jp/xyz/pale/{z}/{x}/{y}.png")
        canReplaceMapContent = true
        minimumZ = 5
        maximumZ = 18
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        Self.session.dataTask(with: url(forTilePath: path)) { data, _, error in
            guard let data, let image = CIImage(data: data) else { return result(nil, error) }
            result(Self.darken(image) ?? data, nil)
        }.resume()
    }

    // ponytail: calibration knob; tune brightness (0.55) / slope (-0.51) if roads read too faint on device.
    static func darken(_ image: CIImage) -> Data? {
        let gray = image.applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 0])
        let dark = gray.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": CIVector(x: -0.51, y: 0, z: 0, w: 0), "inputGVector": CIVector(x: 0, y: -0.51, z: 0, w: 0),
            "inputBVector": CIVector(x: 0, y: 0, z: -0.51, w: 0), "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
            "inputBiasVector": CIVector(x: 0.55, y: 0.55, z: 0.55, w: 0),
        ])
        return context.pngRepresentation(of: dark, format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!)
    }
}

/// Camera access for screen overlays and buttons.
@MainActor
final class MapController: ObservableObject {
    weak var view: MKMapView?
    @Published var ready = false

    var center: CLLocationCoordinate2D? { view?.centerCoordinate }

    /// Web-mercator zoom level of the current camera.
    var zoom: Double { view.map(webZoom) ?? 5 }

    /// [bottomFraction] of the view is treated as covered (by a sheet), so the point lands in the visible part.
    func moveTo(_ c: CLLocationCoordinate2D, zoom target: Double? = nil, bottomFraction: Double = 0) {
        guard let v = view, v.bounds.width > 0 else { return }
        let z = target ?? max(zoom, 14)
        let lng = 360 / pow(2, z) * Double(v.bounds.width) / 256
        let lat = lng * Double(v.bounds.height / v.bounds.width) * cos(c.latitude * .pi / 180)
        let center = CLLocationCoordinate2D(latitude: c.latitude - lat * bottomFraction / 2, longitude: c.longitude)
        v.setRegion(MKCoordinateRegion(center: center, span: MKCoordinateSpan(latitudeDelta: lat, longitudeDelta: lng)), animated: true)
    }
    func setCenter(_ c: CLLocationCoordinate2D) { view?.setCenter(c, animated: true) }
}

final class PostAnnotation: NSObject, MKAnnotation {
    let post: PostView
    let coordinate: CLLocationCoordinate2D
    init(_ post: PostView, _ c: CLLocationCoordinate2D) { self.post = post; coordinate = c }
}
/// 「この位置に投稿されます」 marker while composing.
final class ComposeAnnotation: NSObject, MKAnnotation {
    dynamic var coordinate: CLLocationCoordinate2D
    init(_ c: CLLocationCoordinate2D) { coordinate = c }
}
/// Area overlays: circles are a filled ring with an outline; geohash cells are one fill without edges ([RegionFill])
/// plus one outline of the union's perimeter ([RegionLine]). [alert] gives a solid outline and a stronger fill.
private protocol RegionStyle: AnyObject { var color: UIColor { get set }; var alert: Bool { get set } }
private final class RegionCircle: MKCircle, RegionStyle { var color = UIColor.systemBlue; var alert = false }
private final class RegionFill: MKMultiPolygon, RegionStyle { var color = UIColor.systemBlue; var alert = false }
private final class RegionLine: MKMultiPolyline, RegionStyle { var color = UIColor.systemBlue; var alert = false }

struct GsiMapView: UIViewRepresentable {
    let posts: [PostView]
    let controller: MapController
    var composePoint: CLLocationCoordinate2D?
    var picking = false
    var onSelect: ([String]) -> Void
    var onTap: (CLLocationCoordinate2D) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.overrideUserInterfaceStyle = .dark
        map.backgroundColor = UIColor(Mosimo.bgBase)
        map.isRotateEnabled = false
        map.isPitchEnabled = false
        map.showsCompass = false
        map.pointOfInterestFilter = .excludingAll
        map.showsUserLocation = true
        map.tintColor = UIColor(Mosimo.info)
        // Zoomed out as far as this, a phone shows the whole of Japan; further out is just ocean.
        map.cameraZoomRange = MKMapView.CameraZoomRange(minCenterCoordinateDistance: 150, maxCenterCoordinateDistance: 5_000_000)
        map.addOverlay(GsiTileOverlay(), level: .aboveLabels)
        map.register(PinAnnotationView.self, forAnnotationViewWithReuseIdentifier: PinAnnotationView.reuse)
        map.register(PinAnnotationView.self, forAnnotationViewWithReuseIdentifier: MKMapViewDefaultClusterAnnotationViewReuseIdentifier)
        map.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 36.5, longitude: 138.0),
                                         span: MKCoordinateSpan(latitudeDelta: 18, longitudeDelta: 18)), animated: false)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        tap.cancelsTouchesInView = false
        tap.delegate = context.coordinator
        map.addGestureRecognizer(tap)
        controller.view = map
        context.coordinator.zoomedIn = webZoom(map) >= postMinZoom
        DispatchQueue.main.async { controller.ready = true }
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.sync(map)

        let compose = map.annotations.compactMap { $0 as? ComposeAnnotation }.first
        switch (compose, composePoint) {
        case let (a?, p?): a.coordinate = p
        case let (a?, nil): map.removeAnnotation(a)
        case let (nil, p?): map.addAnnotation(ComposeAnnotation(p))
        default: break
        }
    }

    fileprivate static func regionOverlays(_ p: PostView) -> [MKOverlay] {
        // An official hazard tints its area by severity; user claims keep the default colour.
        let color = UIColor(p.hazardAreaColor ?? p.category?.color ?? Mosimo.info)
        let alert = p.alert
        func styled<T: MKOverlay & RegionStyle>(_ o: T) -> T { o.color = color; o.alert = alert; return o }
        var circles = [MKOverlay](), hashes = [String]()
        for r in p.ext.regions {
            switch r {
            case let .circle(lat, lng, radius):
                circles.append(styled(RegionCircle(center: CLLocationCoordinate2D(latitude: Double(lat) / 1e6, longitude: Double(lng) / 1e6),
                                                   radius: Double(radius) / 1000)))
            case let .geohash(hash): hashes.append(hash)
            }
        }
        let cells = hashes.compactMap(geohashBounds).map { s, w, n, e in
            [GeoPoint(lat: s, lng: w), GeoPoint(lat: s, lng: e), GeoPoint(lat: n, lng: e), GeoPoint(lat: n, lng: w)]
        }
        guard !cells.isEmpty else { return circles }
        func coords(_ pts: [GeoPoint]) -> [CLLocationCoordinate2D] { pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng) } }
        // One multi-polygon fills every cell in a single path, so adjacent cells show no seam.
        let fill = styled(RegionFill(cells.map { c in var pts = coords(c); return MKPolygon(coordinates: &pts, count: pts.count) }))
        let outline = geohashOutline(hashes) ?? cells.map { $0 + [$0[0]] }
        let line = styled(RegionLine(outline.map { l in var pts = coords(l); return MKPolyline(coordinates: &pts, count: pts.count) }))
        return circles + [fill, line]
    }

    final class Coordinator: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        var parent: GsiMapView
        /// The camera is at [postMinZoom] or closer: ordinary posts and their areas are shown, not only alerts.
        var zoomedIn = false
        /// Region overlays on the map, keyed by post (id and the flags that change its look).
        private var shownRegions = [String: [MKOverlay]]()
        init(_ parent: GsiMapView) { self.parent = parent }

        private func key(_ p: PostView) -> String { "\(p.id)|\(p.alert)|\(p.officialHazard)" }

        /// Puts on the map the pins and areas of the posts visible at the current zoom, adding and removing only the difference.
        @MainActor func sync(_ map: MKMapView) {
            let visible = zoomedIn ? parent.posts : parent.posts.filter(\.alert)
            var wanted = [String: (CLLocationCoordinate2D, PostView)]()
            for p in visible where wanted[p.id] == nil { if let c = p.mapPoint { wanted[p.id] = (c, p) } }
            let existing = map.annotations.compactMap { $0 as? PostAnnotation }
            let stale = existing.filter { a in wanted[a.post.id].map { key($0.1) != key(a.post) } ?? true }
            if !stale.isEmpty { map.removeAnnotations(stale) }
            let have = Set(existing.map(\.post.id)).subtracting(stale.map(\.post.id))
            let added = wanted.filter { !have.contains($0.key) }.map { PostAnnotation($0.value.1, $0.value.0) }
            if !added.isEmpty { map.addAnnotations(added) }

            let posts = Dictionary(visible.map { (key($0), $0) }, uniquingKeysWith: { a, _ in a })
            for (k, overlays) in shownRegions where posts[k] == nil {
                map.removeOverlays(overlays)
                shownRegions[k] = nil
            }
            for (k, p) in posts where shownRegions[k] == nil {
                let overlays = GsiMapView.regionOverlays(p)
                shownRegions[k] = overlays
                if !overlays.isEmpty { map.addOverlays(overlays, level: .aboveLabels) }
            }
        }

        func mapViewDidChangeVisibleRegion(_ map: MKMapView) {
            let z = webZoom(map) >= postMinZoom
            guard z != zoomedIn else { return }
            zoomedIn = z
            sync(map)
        }

        func mapView(_ map: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let tiles = overlay as? MKTileOverlay { return MKTileOverlayRenderer(tileOverlay: tiles) }
            // Official alerts: solid outline, slightly stronger fill; ordinary posts: dashed.
            func style(_ r: MKOverlayPathRenderer, _ o: RegionStyle, fill: Bool, stroke: Bool) -> MKOverlayRenderer {
                r.fillColor = fill ? o.color.withAlphaComponent(o.alert ? 0.22 : 0.16) : nil
                r.strokeColor = stroke ? o.color.withAlphaComponent(o.alert ? 0.9 : 0.8) : nil
                r.lineWidth = stroke ? (o.alert ? 2 : 1.5) : 0
                r.lineDashPattern = stroke && !o.alert ? [4, 4] : nil
                return r
            }
            if let c = overlay as? RegionCircle { return style(MKCircleRenderer(circle: c), c, fill: true, stroke: true) }
            if let f = overlay as? RegionFill { return style(MKMultiPolygonRenderer(multiPolygon: f), f, fill: true, stroke: false) }
            if let l = overlay as? RegionLine { return style(MKMultiPolylineRenderer(multiPolyline: l), l, fill: false, stroke: true) }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ map: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            if annotation is MKUserLocation { return nil }
            if let a = annotation as? ComposeAnnotation {
                let v = MKAnnotationView(annotation: a, reuseIdentifier: nil)
                v.image = renderImage(ComposeLabel())
                v.centerOffset = CGPoint(x: 0, y: -(v.image?.size.height ?? 0) / 2 - 14)
                v.displayPriority = .required
                return v
            }
            return map.dequeueReusableAnnotationView(withIdentifier: annotation is MKClusterAnnotation
                ? MKMapViewDefaultClusterAnnotationViewReuseIdentifier : PinAnnotationView.reuse, for: annotation)
        }

        func mapView(_ map: MKMapView, didSelect annotation: MKAnnotation) {
            map.deselectAnnotation(annotation, animated: false)
            if parent.picking { return }
            if let cluster = annotation as? MKClusterAnnotation {
                let members = cluster.memberAnnotations.compactMap { $0 as? PostAnnotation }.sorted { pinOrder($0.post, $1.post) }
                parent.onSelect(members.map(\.post.id))
            } else if let a = annotation as? PostAnnotation {
                parent.onSelect([a.post.id])
            }
        }

        @objc func tapped(_ g: UITapGestureRecognizer) {
            guard let map = g.view as? MKMapView else { return }
            parent.onTap(map.convert(g.location(in: map), toCoordinateFrom: map))
        }
        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    }
}

/// Icon circle in the category colour over a dark label pill (Figma 5:233); the icon centre is the anchor.
final class PinAnnotationView: MKAnnotationView {
    static let reuse = "post"

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        configure()
    }
    required init?(coder: NSCoder) { fatalError() }

    override var annotation: MKAnnotation? { didSet { configure() } }

    private func configure() {
        let post: PostView, count: Int
        if let cluster = annotation as? MKClusterAnnotation {
            // Official alerts win the top of a stack (most severe, then newest); otherwise the newest post.
            let members = cluster.memberAnnotations.compactMap { $0 as? PostAnnotation }
            guard let top = members.min(by: { pinOrder($0.post, $1.post) }) else { return }
            post = top.post; count = members.count
            clusteringIdentifier = nil
        } else if let a = annotation as? PostAnnotation {
            post = a.post; count = 1
            clusteringIdentifier = "post"
        } else { return }
        displayPriority = .required
        collisionMode = .circle
        image = renderImage(PinLabel(post: post, count: count))
        // Anchor on the 32pt icon circle's centre (16pt from the top, plus the badge headroom).
        centerOffset = CGPoint(x: 0, y: (image?.size.height ?? 0) / 2 - 22)
        accessibilityLabel = post.hazardTitle ?? post.category?.label ?? "投稿"
    }
}

struct PinLabel: View {
    let post: PostView
    let count: Int
    var body: some View {
        let c = post.category
        let color = post.hazardColor ?? c?.color ?? Mosimo.info
        // A hazard reads as its title plus the first scale (「大雨 警戒レベル4」); ordinary posts as their first line.
        let hazard = post.ext.hazard
        let limit = hazard != nil ? 16 : 10
        let first = post.hazardTitle.map { t in ([t] + (hazard?.scales.first.map { [HazardLabels.scale($0)] } ?? [])).joined(separator: " ") }
            ?? post.body.split(separator: "\n", omittingEmptySubsequences: false).first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
        let label = first.isEmpty ? (c?.label ?? "") : first.count > limit ? String(first.prefix(limit)) + "…" : first
        VStack(spacing: 4) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: post.ext.hazard.map(hazardSymbol) ?? Category.symbol(c)).font(.system(size: 15, weight: .bold)).foregroundStyle(Mosimo.bgBase)
                    .frame(width: 32, height: 32).background(color, in: Circle())
                    .overlay(Circle().stroke(Mosimo.bgBase, lineWidth: 2))
                if count > 1 {
                    Text("\(count)").font(.labelSmall.bold()).foregroundStyle(Mosimo.bgBase).padding(.horizontal, 5)
                        .background(Mosimo.textPrimary, in: Capsule()).offset(x: 8, y: -6)
                }
            }
            .padding(.top, 6).padding(.horizontal, 10)
            if !label.isEmpty {
                Text(label).font(.labelSmall).foregroundStyle(Mosimo.textPrimary).lineLimit(1)
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(Mosimo.bgBase.opacity(0.88), in: RoundedRectangle(cornerRadius: 6))
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(color.opacity(0.5), lineWidth: 1))
            }
        }
    }
}

private struct ComposeLabel: View {
    var body: some View {
        Text("この位置に投稿されます").font(.bodySmall).foregroundStyle(Mosimo.info)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(Mosimo.bgBase.opacity(0.85), in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Mosimo.info, lineWidth: 1))
    }
}

@MainActor func renderImage<V: View>(_ view: V) -> UIImage? {
    let r = ImageRenderer(content: view)
    r.scale = UIScreen.main.scale
    return r.uiImage
}
