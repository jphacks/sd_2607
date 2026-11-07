import CoreLocation
import Foundation

/// Foreground one-shot fixes via Core Location, plus a reverse-geocoded label.
@MainActor
final class LocationRepository: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var location: CLLocation?
    /// Reverse-geocoded label such as 「仙台市青葉区 川内」; nil offline or when geocoding fails.
    @Published private(set) var address: String?
    @Published private(set) var busy = false
    @Published private(set) var status: CLAuthorizationStatus
    private let manager = CLLocationManager()
    private let geocoder = CLGeocoder()

    override init() {
        status = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    var granted: Bool { status == .authorizedWhenInUse || status == .authorizedAlways }
    /// Not yet asked: the system prompt can still be shown (otherwise only Settings can grant).
    var askable: Bool { status == .notDetermined }

    func requestPermission() { manager.requestWhenInUseAuthorization() }

    func refresh() {
        guard granted, !busy else { return }
        // Show the last known fix at once; the fresh fix replaces it.
        if location == nil, let last = manager.location { update(last) }
        busy = true
        manager.requestLocation()
    }

    nonisolated func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        let s = m.authorizationStatus
        Task { @MainActor in
            self.status = s
            if self.granted { self.refresh() }
        }
    }
    nonisolated func locationManager(_ m: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last else { return }
        Task { @MainActor in self.busy = false; self.update(fix) }
    }
    nonisolated func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.busy = false }
    }

    private func update(_ fix: CLLocation) {
        location = fix
        geocoder.cancelGeocode()
        geocoder.reverseGeocodeLocation(fix, preferredLocale: Locale(identifier: "ja_JP")) { [weak self] marks, _ in
            let p = marks?.first
            let label = [p?.locality ?? p?.subAdministrativeArea, p?.subLocality ?? p?.thoroughfare].compactMap { $0 }.joined(separator: " ")
            Task { @MainActor in self?.address = label.isEmpty ? nil : label }
        }
    }
}
