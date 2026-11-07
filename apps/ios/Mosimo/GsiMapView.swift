import CoreImage
import MapKit
import SwiftUI

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
    var zoom: Double {
        guard let v = view, v.bounds.width > 0 else { return 5 }
        return log2(360 * Double(v.bounds.width) / 256 / v.region.span.longitudeDelta)
    }

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
private final class RegionCircle: MKCircle { var color = UIColor.systemBlue }
private final class RegionPolygon: MKPolygon { var color = UIColor.systemBlue }

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
        DispatchQueue.main.async { controller.ready = true }
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.parent = self
        let wanted = Dictionary(posts.compactMap { p in p.coordinate.map { (p.id, ($0, p)) } }, uniquingKeysWith: { a, _ in a })
        let existing = map.annotations.compactMap { $0 as? PostAnnotation }
        let stale = existing.filter { wanted[$0.post.id] == nil }
        if !stale.isEmpty { map.removeAnnotations(stale) }
        let have = Set(existing.map(\.post.id))
        let added = wanted.filter { !have.contains($0.key) }.map { PostAnnotation($0.value.1, $0.value.0) }
        if !added.isEmpty { map.addAnnotations(added) }

        let compose = map.annotations.compactMap { $0 as? ComposeAnnotation }.first
        switch (compose, composePoint) {
        case let (a?, p?): a.coordinate = p
        case let (a?, nil): map.removeAnnotation(a)
        case let (nil, p?): map.addAnnotation(ComposeAnnotation(p))
        default: break
        }

        let key = posts.map { "\($0.id)\($0.ext.regions)" }.joined()
        if key != context.coordinator.regionKey {
            context.coordinator.regionKey = key
            map.removeOverlays(map.overlays.filter { !($0 is GsiTileOverlay) })
            map.addOverlays(posts.flatMap(regionOverlays), level: .aboveLabels)
        }
    }

    private func regionOverlays(_ p: PostView) -> [MKOverlay] {
        let color = UIColor(p.category?.color ?? Mosimo.info)
        return p.ext.regions.compactMap { r in
            switch r {
            case let .circle(lat, lng, radius):
                let c = RegionCircle(center: CLLocationCoordinate2D(latitude: Double(lat) / 1e6, longitude: Double(lng) / 1e6), radius: Double(radius) / 1000)
                c.color = color
                return c
            case let .geohash(hash):
                guard let (s, w, n, e) = geohashBounds(hash) else { return nil }
                var pts = [CLLocationCoordinate2D(latitude: s, longitude: w), CLLocationCoordinate2D(latitude: s, longitude: e),
                           CLLocationCoordinate2D(latitude: n, longitude: e), CLLocationCoordinate2D(latitude: n, longitude: w)]
                let poly = RegionPolygon(coordinates: &pts, count: pts.count)
                poly.color = color
                return poly
            }
        }
    }

    final class Coordinator: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        var parent: GsiMapView
        var regionKey = ""
        init(_ parent: GsiMapView) { self.parent = parent }

        func mapView(_ map: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let tiles = overlay as? MKTileOverlay { return MKTileOverlayRenderer(tileOverlay: tiles) }
            func style(_ r: MKOverlayPathRenderer, _ color: UIColor) -> MKOverlayRenderer {
                r.fillColor = color.withAlphaComponent(0.16)
                r.strokeColor = color.withAlphaComponent(0.8)
                r.lineWidth = 1.5
                r.lineDashPattern = [4, 4]
                return r
            }
            if let c = overlay as? RegionCircle { return style(MKCircleRenderer(circle: c), c.color) }
            if let p = overlay as? RegionPolygon { return style(MKPolygonRenderer(polygon: p), p.color) }
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
                let members = cluster.memberAnnotations.compactMap { $0 as? PostAnnotation }.sorted { $0.post.createdAt > $1.post.createdAt }
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
            let members = cluster.memberAnnotations.compactMap { $0 as? PostAnnotation }
            guard let newest = members.max(by: { $0.post.createdAt < $1.post.createdAt }) else { return }
            post = newest.post; count = members.count
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
        accessibilityLabel = post.category?.label ?? "投稿"
    }
}

struct PinLabel: View {
    let post: PostView
    let count: Int
    var body: some View {
        let c = post.category
        let color = c?.color ?? Mosimo.info
        let first = post.body.split(separator: "\n", omittingEmptySubsequences: false).first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
        let label = first.isEmpty ? (c?.label ?? "") : first.count > 10 ? String(first.prefix(10)) + "…" : first
        VStack(spacing: 4) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: Category.symbol(c)).font(.system(size: 15, weight: .bold)).foregroundStyle(Mosimo.bgBase)
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
