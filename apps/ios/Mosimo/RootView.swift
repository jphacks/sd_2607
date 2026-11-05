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
            // Every tab stays alive so the map keeps its camera and lists keep their scroll position.
            ZStack {
                tabView(.timeline) {
                    NavigationStack(path: $nav.timelinePath) {
                        TimelineScreen().navigationDestination(for: Route.self, destination: destination)
                    }
                }
                tabView(.map) { MapScreen(underStatusBar: !relayBlocked) }
                tabView(.settings) {
                    NavigationStack(path: $nav.settingsPath) {
                        SettingsScreen().navigationDestination(for: Route.self, destination: destination)
                    }
                }
            }
            .frame(maxHeight: .infinity)
            if nav.atTabRoot { BottomBar() }
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

    @ViewBuilder private func tabView<C: View>(_ tab: Tab, @ViewBuilder content: () -> C) -> some View {
        content()
            .opacity(nav.tab == tab ? 1 : 0)
            .allowsHitTesting(nav.tab == tab)
            .accessibilityHidden(nav.tab != tab)
    }

    @ViewBuilder private func destination(_ route: Route) -> some View {
        switch route {
        case .post(let id): PostScreen(id: id)
        case .trust: TrustScreen()
        case .advanced: AdvancedScreen()
        case .identity: IdentityScreen()
        case .blocked: BlockedScreen()
        }
    }
}

private struct BottomBar: View {
    @EnvironmentObject private var nav: Navigator
    var body: some View {
        HStack(spacing: 0) {
            item("タイムライン", "list.bullet", .timeline, id: "tab.timeline")
            // Center tab is the raised yellow circle (Figma Nav Bar 3:178).
            let active = nav.tab == .map
            Button { nav.select(.map) } label: {
                VStack(spacing: 0) {
                    Image(systemName: "mappin.circle.fill").font(.system(size: 24)).foregroundStyle(Mosimo.onPrimary)
                        .frame(width: 52, height: 52).background(Mosimo.primary, in: Circle())
                        .offset(y: -10)
                    Text("状況マップ").font(.labelSmall.weight(active ? .bold : .medium))
                        .foregroundStyle(active ? Mosimo.primary : Mosimo.textTertiary).offset(y: -6)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("tab.map")
            item("設定", "gearshape", .settings, id: "tab.settings")
        }
        .frame(height: 64)
        .background(Mosimo.bgSurface.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(Mosimo.border).frame(height: 1) }
    }

    private func item(_ label: String, _ icon: String, _ tab: Tab, id: String) -> some View {
        let color = nav.tab == tab ? Mosimo.textPrimary : Mosimo.textTertiary
        return Button { nav.select(tab) } label: {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 20))
                Text(label).font(.labelSmall.weight(nav.tab == tab ? .bold : .medium))
            }
            .foregroundStyle(color).frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }
}
