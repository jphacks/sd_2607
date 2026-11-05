import SwiftUI

@main
struct MosimoApp: App {
    @StateObject private var mesh = MeshCoordinator.shared
    @StateObject private var location = LocationRepository()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(mesh)
                .environmentObject(location)
                .preferredColorScheme(.dark)
                .onChange(of: phase) { _, p in mesh.setOnlineForeground(p == .active) }
        }
    }
}
