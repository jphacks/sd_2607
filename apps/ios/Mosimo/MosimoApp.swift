import SwiftUI

@main
struct MosimoApp: App {
    @StateObject private var mesh = MeshCoordinator.shared
    @StateObject private var location = LocationRepository()
    @Environment(\.scenePhase) private var phase

    /// The notification delegate must be set before launch finishes, or a tap that launched the app is lost.
    init() { _ = HazardNotifier.shared }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(mesh)
                .environmentObject(location)
                .preferredColorScheme(.dark)
                .onChange(of: phase) { _, p in
                    mesh.setOnlineForeground(p == .active)
                    if p == .active { HazardNotifier.shared.refreshStatus() }
                }
        }
    }
}
