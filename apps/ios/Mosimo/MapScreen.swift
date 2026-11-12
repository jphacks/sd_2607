import CoreLocation
import MosimoCore
import SwiftUI

private let nearbyMeters = 1000.0
/// Visible height of a pin sheet at its lowest stop: handle, header and about one row, leaving most of the map free.
private let pinLow: CGFloat = 128
private let sheetHandle: CGFloat = 20
private let sheetSpring = Animation.spring(response: 0.35, dampingFraction: 0.85)

/// Map screen state that survives tab switches (the view stays alive in RootView).
@MainActor
final class MapModel: ObservableObject {
    @Published var filter = Set<Category>()
    @Published var selected: String?
    /// Posts of the tapped pin stack shown in the panel, newest first; [selected] is the one opened from it.
    @Published var stack: [String] = []
    @Published var hereCollapsed = false
    var centered = false
    var handledFocus = 0

    @Published var composing = false
    @Published var picking = false
    @Published var category: Category?
    @Published var detail = ""
    /// Meters; 0 = no area.
    @Published var radius = 0
    @Published var hours = 6
    /// Point placed on the map; nil = use the current fix.
    @Published var picked: CLLocationCoordinate2D?
    @Published var sending = false
    private var sentAttempt: Int64 = 0
    private var sentId: String?

    func compose(_ preset: Category?) {
        category = preset; detail = ""; radius = 0; hours = 6; picked = nil; composing = true
    }

    func publish(_ mesh: MeshCoordinator, fix: CLLocation?, address: String?) {
        guard let c = category, let point = picked ?? fix?.coordinate else { return }
        let ends = Int64(Date().timeIntervalSince1970) + Int64(hours) * 3600
        let detailText = detail.trimmingCharacters(in: .whitespacesAndNewlines)
        let text = c.prefix + (detailText.isEmpty ? (picked == nil ? address ?? "" : "") : detailText)
        var ext = [[String: Cbor]]()
        // geo §1: #origin is where this device is; a point picked on the map is what the post is about, so it goes into #area.
        if picked == nil, let fix {
            ext.append(Extensions.origin(Origin(lat: micro(point.latitude), lon: micro(point.longitude),
                                                acc: fix.horizontalAccuracy >= 0 ? Int64((fix.horizontalAccuracy * 1000).rounded()) : nil)))
        }
        let areaMeters = radius > 0 ? radius : picked != nil ? 200 : 0
        if areaMeters > 0 { ext.append(Extensions.circle(lat: micro(point.latitude), lon: micro(point.longitude), r: Int64(areaMeters) * 1000)) }
        // validity is independent of the delivery deadline, which keeps the app default.
        ext.append(Extensions.validity(until: Instant(epochSecond: ends).description))
        sentAttempt = mesh.state.publishAttempt
        sentId = mesh.state.lastPublishedId
        sending = true
        mesh.publish(text, extensions: ext)
    }

    /// The coordinator bumps publishAttempt once per attempt; a new lastPublishedId means it was signed and stored.
    func published(_ state: MeshState) {
        guard sending, state.publishAttempt != sentAttempt else { return }
        sending = false
        if state.lastPublishedId != sentId { composing = false }
    }
}

private enum SheetMode { case here, pin, picking }

struct MapScreen: View {
    var underStatusBar = true
    @EnvironmentObject private var mesh: MeshCoordinator
    @EnvironmentObject private var location: LocationRepository
    @EnvironmentObject private var nav: Navigator
    @StateObject private var vm = MapModel()
    @StateObject private var map = MapController()
    @State private var sheetTop: CGFloat?
    /// Springs back with the same curve as [sheetTop]: an unanimated reset would snap the sheet to its pre-drag position on release.
    @GestureState(resetTransaction: Transaction(animation: sheetSpring)) private var drag: CGFloat = 0
    @State private var hereHeight: CGFloat = 0
    @State private var herePeek: CGFloat = 0
    @State private var shownMode: SheetMode?

    var body: some View {
        let state = mesh.state
        let all = mapPosts(state.posts)
        let shown = vm.filter.isEmpty ? all : all.filter { $0.category.map(vm.filter.contains) ?? false }
        let selected = vm.selected.flatMap { id in state.posts.first { $0.id == id } }
        let stacked = vm.stack.compactMap { id in state.posts.first { $0.id == id } }
        let panel = selected != nil || !stacked.isEmpty
        let mode: SheetMode = vm.picking ? .picking : panel ? .pin : .here
        let fix = location.location
        let point = vm.picked ?? fix?.coordinate

        GeometryReader { geo in
            // h ends above the floating tab bar; the map and the sheet's surface run on under it.
            let h = geo.size.height
            let inset = geo.safeAreaInsets.bottom
            let top = clamp((sheetTop ?? h) + drag, mode, h)
            ZStack(alignment: .top) {
                GsiMapView(posts: shown, controller: map, composePoint: vm.composing ? point : nil, picking: vm.picking,
                           onSelect: { open($0, panel: panel, h: h) }, onTap: { if vm.picking { map.setCenter($0) } })
                    .ignoresSafeArea(edges: underStatusBar ? [.top] : [])
                if vm.picking {
                    Image(systemName: "mappin").font(.system(size: 36, weight: .bold)).foregroundStyle(Mosimo.info)
                        .offset(y: -18).frame(maxWidth: .infinity, maxHeight: .infinity)
                        .allowsHitTesting(false)
                }
                topOverlay(state: state, all: all, shown: shown)
                // Locate button and picking bar ride on the sheet's top edge, never above mid-screen.
                VStack(spacing: 0) {
                    Spacer()
                    bottomControls(fix: fix)
                }
                .frame(height: max(top, h / 2))
                sheet(mode: mode, h: h, inset: inset, top: top, all: all, fix: fix, selected: selected, stacked: stacked)
            }
            .clipped()
            .ignoresSafeArea(.container, edges: .bottom)
            .onChange(of: modeKey(mode, h)) { _, _ in settle(mode, h) }
            .onAppear { settle(mode, h) }
        }
        .background(Mosimo.bgBase)
        .sheet(isPresented: $vm.composing) { Composer(vm: vm, onPick: pick) }
        .onChange(of: mesh.state.publishAttempt) { _, _ in vm.published(mesh.state) }
        .onChange(of: location.location) { _, f in
            guard let f, !vm.centered, map.ready else { return }
            vm.centered = true
            map.moveTo(f.coordinate, zoom: 14)
        }
        .onChange(of: map.ready) { _, ready in
            guard ready, let f = location.location, !vm.centered else { return }
            vm.centered = true
            map.moveTo(f.coordinate, zoom: 14)
        }
        .onChange(of: nav.focusToken) { _, _ in focus() }
        .onChange(of: mesh.state.posts) { _, _ in
            focus()
            // Drops a panel whose post left the list (e.g. its author was blocked).
            if let id = vm.selected, !mesh.state.posts.contains(where: { $0.id == id }), !mesh.state.posts.isEmpty { vm.selected = nil }
        }
        .onChange(of: vm.composing) { _, composing in
            if composing, let p = vm.picked ?? location.location?.coordinate { map.moveTo(p, zoom: 15, bottomFraction: 0.75) }
        }
        .onAppear { location.refresh() }
    }

    // MARK: - Overlays

    @ViewBuilder private func topOverlay(state: MeshState, all: [PostView], shown: [PostView]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if !location.granted {
                Banner(title: "位置情報の権限がありません", detail: "地図は使えます。投稿時は地図で位置を指定します", action: "許可する", color: Mosimo.warning) {
                    if location.askable { location.requestPermission() } else { openAppSettings() }
                }
            }
            if vm.picking {
                Banner(title: "投稿する位置を選ぶ", detail: "地図を動かすかタップして、中央の印を合わせてください")
            } else {
                Connectivity(state: state)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Category.allCases) { c in
                            MosimoChip(label: c.label, selected: vm.filter.contains(c)) {
                                if vm.filter.contains(c) { vm.filter.remove(c) } else { vm.filter.insert(c) }
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }
                Legend().padding(.horizontal, 16).padding(.vertical, 4)
                if !state.posts.isEmpty && all.isEmpty { Hint(text: "位置付きの投稿はまだありません") }
                else if !all.isEmpty && shown.isEmpty { Hint(text: "この種類の投稿はありません") }
            }
        }
    }

    @ViewBuilder private func bottomControls(fix: CLLocation?) -> some View {
        VStack(spacing: 0) {
            HStack(alignment: .bottom) {
                Text("地理院タイル").font(.system(size: 10)).foregroundStyle(Mosimo.textSecondary)
                    .padding(.horizontal, 4).padding(.vertical, 1).background(Mosimo.bgBase.opacity(0.6))
                Spacer()
                Button {
                    if !location.granted { location.askable ? location.requestPermission() : openAppSettings() }
                    if let f = fix { map.moveTo(f.coordinate, zoom: 15) }
                    location.refresh()
                } label: {
                    Group {
                        if location.busy { ProgressView().tint(Mosimo.info) }
                        else { Image(systemName: "location.fill").font(.system(size: 16)).foregroundStyle(Mosimo.textPrimary) }
                    }
                    .frame(width: 40, height: 40).background(Mosimo.bgSurface, in: RoundedRectangle(cornerRadius: 12))
                    .shadow(radius: 3)
                }
                .accessibilityLabel("現在地")
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            if vm.picking {
                HStack(spacing: 12) {
                    Button { endPick(false) } label: {
                        Text("キャンセル").font(.labelLarge).foregroundStyle(Mosimo.textPrimary).frame(maxWidth: .infinity).frame(height: 52)
                            .overlay(RoundedRectangle(cornerRadius: 26).stroke(Mosimo.borderStrong))
                    }
                    Button { endPick(true) } label: {
                        Text("この位置にする").font(.labelLarge).foregroundStyle(Mosimo.onPrimary).frame(maxWidth: .infinity).frame(height: 52)
                            .background(Mosimo.primary, in: RoundedRectangle(cornerRadius: 26))
                    }
                    .accessibilityIdentifier("compose.pickConfirm")
                }
                .padding(16).background(Mosimo.bgSurface)
            }
        }
    }

    // MARK: - Sheet

    @ViewBuilder private func sheet(mode: SheetMode, h: CGFloat, inset: CGFloat, top: CGFloat, all: [PostView], fix: CLLocation?, selected: PostView?, stacked: [PostView]) -> some View {
        let handleDrag = DragGesture(minimumDistance: 4, coordinateSpace: .global)
            .updating($drag) { v, s, _ in s = v.translation.height }
            .onEnded { v in fling(mode, h, from: (sheetTop ?? h) + v.translation.height, predicted: (sheetTop ?? h) + v.predictedEndTranslation.height) }
        VStack(spacing: 0) {
            Capsule().fill(Mosimo.borderStrong).frame(width: 36, height: 4).padding(.top, 10).padding(.bottom, 6)
                .frame(maxWidth: .infinity).contentShape(Rectangle())
                .gesture(handleDrag, including: mode == .pin ? .all : .none)
                .onTapGesture { toggle(mode, h) }
                .accessibilityLabel("広げる・たたむ").accessibilityAddTraits(.isButton)
            if mode == .pin {
                PostPanel(selected: selected, stack: stacked, onSelect: { vm.selected = $0 }, onBack: { vm.selected = nil }, headerDrag: handleDrag)
                    .frame(height: h * 0.9 - sheetHandle)
            } else if mode == .here {
                HereSheet(fix: fix, address: location.address, granted: location.granted, busy: location.busy,
                          nearby: all.filter { ($0.distance(from: fix) ?? .infinity) <= nearbyMeters }.count,
                          collapsed: abs(top - (h - herePeek)) < 2, onExpand: { animate(to: h - hereHeight) },
                          onPeek: { herePeek = $0 + sheetHandle }, onCompose: vm.compose)
            }
        }
        .background(GeometryReader { g in Color.clear.onAppear { if mode == .here { hereHeight = g.size.height } }
            .onChange(of: g.size.height) { _, v in if mode == .here { hereHeight = v; settle(mode, h) } } })
        .padding(.bottom, inset)
        .background(Mosimo.bgSurface, in: UnevenRoundedRectangle(topLeadingRadius: 20, topTrailingRadius: 20))
        .shadow(color: .black.opacity(0.4), radius: 8)
        .gesture(handleDrag, including: mode == .here ? .all : .subviews)
        .offset(y: top)
        .opacity(mode == .picking ? 0 : 1)
    }

    private func stops(_ mode: SheetMode, _ h: CGFloat) -> [CGFloat] {
        switch mode {
        case .pin: return [h - pinLow, h * 0.55, h * 0.1]
        case .here: return hereHeight > 0 ? [h - herePeek, h - hereHeight] : [h]
        case .picking: return [h]
        }
    }
    private func clamp(_ y: CGFloat, _ mode: SheetMode, _ h: CGFloat) -> CGFloat {
        let s = stops(mode, h)
        return min(max(y, s.min() ?? 0), mode == .pin ? h : (s.max() ?? h))
    }
    private func modeKey(_ mode: SheetMode, _ h: CGFloat) -> String { "\(mode)-\(Int(h))-\(Int(hereHeight))-\(Int(herePeek))" }

    private func settle(_ mode: SheetMode, _ h: CGFloat) {
        let target: CGFloat
        switch mode {
        case .pin: target = h * 0.55
        case .here: target = hereHeight > 0 ? (vm.hereCollapsed ? h - herePeek : h - hereHeight) : h
        case .picking: target = h
        }
        if shownMode == mode, let top = sheetTop, stops(mode, h).contains(where: { abs($0 - top) < 1 }) { return }
        shownMode = mode
        animate(to: target)
    }
    private func animate(to y: CGFloat) { withAnimation(sheetSpring) { sheetTop = y } }

    /// A fast fling goes to the stop it is heading for, a slow release to the nearest; pin sheets close below their lowest stop.
    private func fling(_ mode: SheetMode, _ h: CGFloat, from y: CGFloat, predicted: CGFloat) {
        var s = stops(mode, h)
        if mode == .pin { s.append(h) }
        let target = s.min { abs($0 - predicted) < abs($1 - predicted) } ?? y
        if mode == .pin && target == h { closePanel(); return }
        if mode == .here { vm.hereCollapsed = abs(target - (h - herePeek)) < 1 }
        animate(to: target)
    }
    private func toggle(_ mode: SheetMode, _ h: CGFloat) {
        let s = stops(mode, h).sorted()
        guard let top = sheetTop, s.count > 1 else { return }
        let next: CGFloat
        if mode == .pin { next = abs(top - h * 0.55) < 1 ? h * 0.1 : h * 0.55 }
        else { next = abs(top - s.first!) < 1 ? s.last! : s.first! }
        if mode == .here { vm.hereCollapsed = abs(next - (h - herePeek)) < 1 }
        animate(to: next)
    }

    private func closePanel() { withAnimation { vm.stack = []; vm.selected = nil } }

    private func open(_ ids: [String], panel: Bool, h: CGFloat) {
        vm.stack = ids
        vm.selected = ids.count == 1 ? ids[0] : nil
        if panel { animate(to: h * 0.55) }
    }

    private func focus() {
        guard nav.focusToken != vm.handledFocus, let id = nav.mapFocus, map.ready,
              let post = mesh.state.posts.first(where: { $0.id == id }) else { return }
        vm.handledFocus = nav.focusToken
        if let c = post.coordinate { vm.centered = true; map.moveTo(c, zoom: 15, bottomFraction: 0.5) }
        vm.stack = [id]; vm.selected = id
    }

    private func pick() {
        let point = vm.picked ?? location.location?.coordinate
        vm.composing = false
        vm.picking = true
        // Always re-centre: the crosshair is the view centre, which must equal the camera target.
        if let p = point ?? map.center { map.moveTo(p, zoom: point != nil ? 15 : map.zoom) }
    }
    private func endPick(_ confirm: Bool) {
        if confirm, let c = map.center { vm.picked = c }
        vm.picking = false
        vm.composing = true
    }
}

/// Sheet content for a tapped pin: a stack lists its posts; one post shows its detail.
private struct PostPanel<G: Gesture>: View {
    let selected: PostView?
    let stack: [PostView]
    let onSelect: (String) -> Void
    let onBack: () -> Void
    let headerDrag: G
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                if selected != nil && stack.count > 1 {
                    Button(action: onBack) { Image(systemName: "arrow.left").foregroundStyle(Mosimo.textPrimary).frame(width: 44, height: 44) }
                        .accessibilityLabel("一覧に戻る")
                }
                Text(selected != nil ? "投稿" : "この地点の投稿 \(stack.count)件").font(.titleMedium).foregroundStyle(Mosimo.textPrimary)
                    .padding(.leading, selected != nil && stack.count > 1 ? 0 : 16).padding(.vertical, 12)
                Spacer()
            }
            .contentShape(Rectangle())
            .gesture(headerDrag)
            if let selected {
                PostDetail(post: selected, onShowOnMap: nil)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(stack) { p in StackRow(post: p) { onSelect(p.id) } }
                    }
                    .padding(.bottom, 8)
                }
            }
        }
    }
}

private struct StackRow: View {
    let post: PostView
    let onTap: () -> Void
    var body: some View {
        let c = post.category
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image(systemName: Category.symbol(c)).font(.system(size: 13, weight: .bold)).foregroundStyle(Mosimo.bgBase)
                    .frame(width: 28, height: 28).background(c?.color ?? Mosimo.info, in: Circle())
                VStack(alignment: .leading, spacing: 2) {
                    let first = post.body.split(separator: "\n", omittingEmptySubsequences: false).first.map(String.init) ?? ""
                    Text(first.trimmingCharacters(in: .whitespaces).isEmpty ? c?.label ?? "" : first).font(.bodyMedium)
                        .foregroundStyle(Mosimo.textPrimary).lineLimit(1)
                    Text("\(dateTime(post.createdAt))・\(shortDid(post.did, post.pubkey))").font(.bodySmall).foregroundStyle(Mosimo.textTertiary)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(Mosimo.textTertiary)
            }
            .padding(.horizontal, 16).padding(.vertical, 10).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Sheet content when no pin is open (Figma 5:233): where am I, how many posts nearby, quick-post chips. Peeks at the header.
private struct HereSheet: View {
    let fix: CLLocation?
    let address: String?
    let granted: Bool
    let busy: Bool
    let nearby: Int
    let collapsed: Bool
    let onExpand: () -> Void
    let onPeek: (CGFloat) -> Void
    let onCompose: (Category?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("今、ここの状況は？").font(.titleLarge).foregroundStyle(Mosimo.textPrimary)
                    HStack(spacing: 4) {
                        Image(systemName: "mappin").font(.system(size: 11)).foregroundStyle(Mosimo.textSecondary)
                        Text(whereLabel).font(.bodySmall).foregroundStyle(Mosimo.textSecondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                if fix != nil {
                    HStack(spacing: 4) {
                        Image(systemName: "person.3").font(.system(size: 12))
                        Text("周辺 \(nearby)件").font(.bodySmall)
                    }
                    .foregroundStyle(Mosimo.textSecondary).padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Mosimo.bgSurface2, in: RoundedRectangle(cornerRadius: 8))
                }
            }
            .background(GeometryReader { g in Color.clear.onAppear { onPeek(g.size.height + 16) }
                .onChange(of: g.size.height) { _, v in onPeek(v + 16) } })
            .contentShape(Rectangle())
            .onTapGesture { if collapsed { onExpand() } }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach([Category.safe, .caution, .danger, .shelter, .blocked]) { c in
                        MosimoChip(label: c.label, selected: false) { onCompose(c) }.accessibilityIdentifier("here.\(c.label)")
                    }
                }
            }
            Button { onCompose(nil) } label: {
                Label("この場所の状況を投稿する", systemImage: "mappin.and.ellipse").font(.labelLarge)
                    .foregroundStyle(Mosimo.onPrimary).frame(maxWidth: .infinity).frame(height: 52)
                    .background(Mosimo.primary, in: RoundedRectangle(cornerRadius: 26))
            }
            .accessibilityIdentifier("compose.open")
        }
        .padding(.horizontal, 16).padding(.bottom, 16)
    }

    private var whereLabel: String {
        if let fix {
            var parts = ["\(address ?? coordsLabel(fix.coordinate.latitude, fix.coordinate.longitude)) 付近"]
            if fix.horizontalAccuracy >= 0 { parts.append("精度 \(accuracyLabel(fix.horizontalAccuracy))") }
            return parts.joined(separator: "・")
        }
        if !granted { return "位置情報なし・地図で位置を指定して投稿できます" }
        return busy ? "現在地を取得中…" : "現在地を取得できません・地図で位置を指定できます"
    }
}

private struct Connectivity: View {
    let state: MeshState
    var body: some View {
        let mesh = "メッシュ接続中 \(state.neighbors.count)台"
        if state.network && state.onlineActive {
            HStack(spacing: 8) { Pill(text: "オンライン", color: Mosimo.safe); Pill(text: mesh, color: Mosimo.info) }
                .padding(.horizontal, 16).padding(.vertical, 8)
        } else {
            HStack(spacing: 8) {
                Image(systemName: "wifi.slash").foregroundStyle(Mosimo.info)
                VStack(alignment: .leading, spacing: 2) {
                    Text("オフライン中でも投稿・受信できます").font(.titleMedium).foregroundStyle(Mosimo.textPrimary)
                    Text("近くの端末へ中継して届けます").font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
                }
                Spacer(minLength: 4)
                Pill(text: mesh, color: Mosimo.info)
            }
            .padding(12)
            .background(Mosimo.bgBase.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
            .background(Mosimo.info.opacity(0.18), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Mosimo.info.opacity(0.4)))
            .padding(.horizontal, 16).padding(.vertical, 8)
        }
    }
}

private struct Hint: View {
    let text: String
    var body: some View {
        Text(text).font(.bodySmall).foregroundStyle(Mosimo.textSecondary).padding(.horizontal, 10).padding(.vertical, 4)
            .background(Mosimo.bgSurface.opacity(0.9), in: RoundedRectangle(cornerRadius: 8))
            .padding(.horizontal, 16).padding(.vertical, 4)
    }
}

private struct Legend: View {
    @State private var open = false
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 2) {
                Text("凡例").font(.labelSmall).foregroundStyle(Mosimo.textSecondary)
                Image(systemName: open ? "chevron.up" : "chevron.down").font(.system(size: 10)).foregroundStyle(Mosimo.textSecondary)
            }
            if open {
                ForEach([(Mosimo.danger, "危険"), (Mosimo.warning, "注意"), (Mosimo.safe, "安全・避難所"), (Mosimo.info, "自分・周辺端末")], id: \.1) { c, l in
                    HStack(spacing: 6) {
                        Circle().fill(c).frame(width: 8, height: 8)
                        Text(l).font(.labelSmall).foregroundStyle(Mosimo.textPrimary)
                    }
                }
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(Mosimo.bgSurface.opacity(0.9), in: RoundedRectangle(cornerRadius: 8))
        .onTapGesture { open.toggle() }
        .accessibilityLabel(open ? "凡例を閉じる" : "凡例を開く")
        .accessibilityAddTraits(.isButton)
    }
}
