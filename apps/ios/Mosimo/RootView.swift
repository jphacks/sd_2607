import SwiftUI

struct RootView: View {
    @EnvironmentObject private var mesh: MeshCoordinator
    @EnvironmentObject private var location: LocationRepository
    @StateObject private var nav = Navigator()
    @AppStorage("permissionsAsked") private var permissionsAsked = false
    @State private var toast: String?

    var body: some View {
        let relayBlocked = nav.atTabRoot && !mesh.state.running && !mesh.state.bluetoothAllowed
        VStack(spacing: 0) {
            if relayBlocked {
                Banner(title: "中継が止まっています", detail: "近くのデバイスへの権限が必要です", action: "許可する", color: Mosimo.warning, onAction: openAppSettings)
            }
            // System tab bar (Liquid Glass on iOS 26); TabView keeps every tab alive, so the map keeps its camera and lists their scroll.
            TabView(selection: $nav.tab) {
                NavigationStack(path: $nav.timelinePath) {
                    TimelineScreen().navigationDestination(for: Route.self, destination: destination)
                }
                .tabItem { Label("タイムライン", systemImage: "list.bullet") }.tag(Tab.timeline)
                MapScreen(underStatusBar: !relayBlocked)
                    .tabItem { Label("状況マップ", systemImage: "mappin.circle") }.tag(Tab.map)
                NavigationStack(path: $nav.settingsPath) {
                    SettingsScreen().navigationDestination(for: Route.self, destination: destination)
                }
                .tabItem { Label("設定", systemImage: "gearshape") }.tag(Tab.settings)
            }
        }
        .background(Mosimo.bgBase.ignoresSafeArea())
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast).font(.bodyMedium).foregroundStyle(Mosimo.textPrimary)
                    .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                    .background(Mosimo.bgElevated, in: RoundedRectangle(cornerRadius: 8))
                    .padding(.horizontal, 16).padding(.bottom, 80)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .environmentObject(nav)
        .tint(Mosimo.primary)
        .task(id: mesh.state.error) {
            guard let error = mesh.state.error else { return }
            withAnimation { toast = error }
            mesh.clearError()
            try? await Task.sleep(for: .seconds(4))
            withAnimation { toast = nil }
        }
        .task { firstLaunch() }
    }

    /// First launch asks for Bluetooth (via the coordinator) then location; later launches resume relaying unless switched off.
    private func firstLaunch() {
        if mesh.relayEnabled { mesh.start() }
        if !permissionsAsked || location.askable {
            permissionsAsked = true
            if location.askable { location.requestPermission() }
        }
        location.refresh()
    }

    @ViewBuilder private func destination(_ route: Route) -> some View {
        switch route {
        case .post(let id): PostScreen(id: id)
        case .trust: TrustScreen()
        case .advanced: AdvancedScreen()
        case .identity: IdentityScreen()
        case .blocked: BlockedScreen()
        case .terms: TermsScreen()
        }
    }
}
