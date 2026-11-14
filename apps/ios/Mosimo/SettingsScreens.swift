import MosimoCore
import SwiftUI
import UIKit

struct SettingsScreen: View {
    @EnvironmentObject private var mesh: MeshCoordinator
    @EnvironmentObject private var nav: Navigator
    @ObservedObject private var alerts = HazardNotifier.shared
    @Environment(\.openURL) private var openURL

    var body: some View {
        let state = mesh.state
        let roots = state.trustRoots.map(\.root).filter(\.enabled)
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text("設定").font(.titleLarge).foregroundStyle(Mosimo.textPrimary).padding(.top, 16).padding(.bottom, 8)
                LocationCard()
                Header(text: "通知")
                if alerts.anyOn && alerts.status == .denied {
                    Text("通知が許可されていません。設定アプリで許可してください").font(.bodySmall).foregroundStyle(Mosimo.warning)
                }
                Group_ {
                    ForEach(Array(alertRows.enumerated()), id: \.offset) { i, row in
                        if i > 0 { Line() }
                        SwitchRow(icon: row.icon, tint: row.tint, title: row.title, subtitle: row.subtitle,
                                  isOn: Binding(get: { alerts.genres.contains(row.genre) }, set: { alerts.set(row.genre, $0) }),
                                  id: "settings.hazardAlerts.\(row.genre.rawValue)")
                    }
                }
                Header(text: "通信（もしもプロトコル）")
                Group_ {
                    SettingRow(icon: "checkmark.shield", tint: Mosimo.safe, title: "公式情報とみなす発信元",
                               subtitle: roots.map(\.name).joined(separator: " / ").nilIfEmpty ?? "信頼する root 鍵が未設定", value: "\(roots.count)件",
                               action: { nav.push(.trust) })
                    Line()
                    // Release shows only the usage-statistics switch there; the developer-facing switches and diagnostics stay DEBUG-only (App Review 2.1).
                    #if DEBUG
                    SettingRow(icon: "slider.horizontal.3", tint: Mosimo.textSecondary, title: "詳細設定", subtitle: "利用状況・オフライン中継・端末時計・リレー・診断", action: { nav.push(.advanced) })
                    #else
                    SettingRow(icon: "slider.horizontal.3", tint: Mosimo.textSecondary, title: "詳細設定", subtitle: "利用状況の送信", action: { nav.push(.advanced) })
                    #endif
                }
                Header(text: "あなたの情報")
                Group_ {
                    SettingRow(icon: "key", tint: Mosimo.primary, title: "署名鍵とID",
                               subtitle: state.ready ? "\(shortDid(state.did.nilIfEmpty, state.publicKey))・端末で安全に保管" : "準備中", action: { nav.push(.identity) })
                    Line()
                    SettingRow(icon: "nosign", tint: Mosimo.danger, title: "ブロックリスト", value: "\(state.blockedPubkeys.count)件", action: { nav.push(.blocked) })
                }
                Header(text: "このアプリについて")
                Group_ {
                    SettingRow(icon: "hand.raised", tint: Mosimo.textSecondary, title: "プライバシーポリシー", action: { nav.push(.policy) })
                    Line()
                    SettingRow(icon: "doc.text", tint: Mosimo.textSecondary, title: "利用規約", action: { nav.push(.terms) })
                    Line()
                    SettingRow(icon: "envelope", tint: Mosimo.textSecondary, title: "お問い合わせ", subtitle: contactAddress,
                               action: { openURL(URL(string: "mailto:\(contactAddress)")!) })
                    Line()
                    SettingRow(icon: "info.circle", tint: Mosimo.textSecondary, title: "バージョン",
                               value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                }
                Spacer().frame(height: 16)
            }
            .padding(.horizontal, 16)
        }
        .background(Mosimo.bgBase)
        .toolbar(.hidden, for: .navigationBar)
    }

    /// One switch per notification genre (HazardAlerts.due has the rules).
    private var alertRows: [(genre: HazardAlerts.Genre, icon: String, tint: Color, title: String, subtitle: String)] {
        let official = "公式情報。現在地が範囲内なら「警戒」以上、範囲や現在地が分からなければ「極めて重大」のみ"
        return [(.earthquake, "waveform.path.ecg", Mosimo.danger, "地震", official),
                (.weather, "cloud.rain.fill", Mosimo.warning, "気象警報・注意報", official),
                (.otherOfficial, "exclamationmark.triangle.fill", Mosimo.warning, "その他の公式情報（津波・火山など）", official),
                (.nearbyDanger, "person.wave.2", Mosimo.info, "近くの危険情報", "現在地から2km以内の「危険」の投稿（1時間以内・利用者の投稿で未確認）")]
    }

    private func unique(_ items: [String]) -> [String] { items.reduce(into: []) { if !$0.contains($1) { $0.append($1) } } }
}

private struct LocationCard: View {
    @EnvironmentObject private var location: LocationRepository
    @EnvironmentObject private var nav: Navigator

    var body: some View {
        let fix = location.granted ? location.location : nil
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                IconTile(icon: "mappin.and.ellipse", tint: Mosimo.info, size: 48)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text("あなたの位置").font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
                        if let fix {
                            TimelineView(.periodic(from: .now, by: 60)) { ctx in
                                Text(age(ctx.date.timeIntervalSince(fix.timestamp))).font(.bodySmall).foregroundStyle(Mosimo.textTertiary)
                            }
                        }
                    }
                    Text(!location.granted ? "位置情報の権限がありません"
                         : fix == nil ? (location.busy ? "取得中…" : "位置を取得できていません")
                         : location.address ?? coordsLabel(fix!.coordinate.latitude, fix!.coordinate.longitude))
                        .font(.titleMedium).foregroundStyle(Mosimo.textPrimary)
                    if let fix, fix.horizontalAccuracy >= 0 {
                        Text("精度 ±\(Int(fix.horizontalAccuracy.rounded()))m・投稿にはこの位置がそのまま付きます").font(.bodySmall).foregroundStyle(Mosimo.textTertiary)
                    }
                }
            }
            HStack(spacing: 8) {
                if location.granted {
                    ActionButton(icon: "location", label: "現在地を更新", enabled: !location.busy) { location.refresh() }
                } else {
                    ActionButton(icon: "location.circle", label: "権限を許可") {
                        if location.askable { location.requestPermission() } else { openAppSettings() }
                    }
                }
                ActionButton(icon: "map", label: "地図で確認") { nav.select(.map) }
            }
        }
        .padding(16)
        .surface()
        .onAppear { if location.granted && location.location == nil { location.refresh() } }
    }

    private func age(_ seconds: TimeInterval) -> String {
        let m = max(0, Int(seconds / 60))
        return m < 1 ? "たった今" : m < 60 ? "\(m)分前" : m < 1440 ? "\(m / 60)時間前" : "\(m / 1440)日前"
    }
}

private struct ActionButton: View {
    let icon: String
    let label: String
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 15))
                Text(label).font(.labelLarge).lineLimit(1)
            }
            .foregroundStyle(Mosimo.textPrimary).frame(maxWidth: .infinity).frame(height: 40)
            .background(Mosimo.bgSurface2, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Mosimo.borderStrong))
            .opacity(enabled ? 1 : 0.5)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

// Shared building blocks for the settings screens (Figma 13:599: grouped rows on surfaces).

extension View {
    func surface() -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
            .background(Mosimo.bgSurface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Mosimo.border))
    }
}

extension String { var nilIfEmpty: String? { isEmpty ? nil : self } }

/// A grouped surface of rows (named to avoid SwiftUI.Group).
private struct Group_<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View { VStack(spacing: 0) { content }.surface().clipShape(RoundedRectangle(cornerRadius: 16)) }
}

private struct Line: View { var body: some View { Rectangle().fill(Mosimo.border).frame(height: 1) } }

private struct Header: View {
    let text: String
    var body: some View { Text(text).font(.bodySmall.bold()).foregroundStyle(Mosimo.textSecondary).padding(.leading, 4).padding(.top, 12) }
}

private struct IconTile: View {
    let icon: String
    let tint: Color
    var size: CGFloat = 36
    var body: some View {
        Image(systemName: icon).font(.system(size: size / 2 - 2)).foregroundStyle(tint)
            .frame(width: size, height: size).background(tint.opacity(0.16), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct SettingRow<Trailing: View>: View {
    var icon: String?
    var tint: Color
    let title: String
    var subtitle: String?
    var value: String?
    var trailing: Trailing
    var action: (() -> Void)?

    init(icon: String?, tint: Color, title: String, subtitle: String? = nil, value: String? = nil,
         @ViewBuilder trailing: () -> Trailing = { EmptyView() }, action: (() -> Void)? = nil) {
        self.icon = icon; self.tint = tint; self.title = title; self.subtitle = subtitle; self.value = value
        self.trailing = trailing(); self.action = action
    }

    var body: some View {
        let row = HStack(spacing: 12) {
            if let icon { IconTile(icon: icon, tint: tint) }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 16, weight: .bold)).foregroundStyle(Mosimo.textPrimary)
                if let subtitle { Text(subtitle).font(.bodySmall).foregroundStyle(Mosimo.textSecondary).textSelection(.enabled) }
            }
            Spacer(minLength: 4)
            if let value { Text(value).font(.bodyMedium).foregroundStyle(Mosimo.textSecondary) }
            trailing
            if action != nil && Trailing.self == EmptyView.self { Image(systemName: "chevron.right").foregroundStyle(Mosimo.textTertiary) }
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        if let action { Button(action: action) { row }.buttonStyle(.plain) } else { row }
    }
}

private struct SwitchRow: View {
    let icon: String?
    let tint: Color
    let title: String
    let subtitle: String?
    @Binding var isOn: Bool
    var enabled = true
    var id: String? = nil
    var body: some View {
        SettingRow(icon: icon, tint: tint, title: title, subtitle: subtitle, trailing: {
            Toggle(title, isOn: $isOn).labelsHidden().tint(Mosimo.primary).disabled(!enabled).accessibilityIdentifier(id ?? title)
        })
    }
}

/// Sub-screens have no bottom bar, so they carry the system back bar.
private struct SubScreen<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) { content }
                .padding(.horizontal, 16).padding(.bottom, 16)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Mosimo.bgBase)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Mosimo.bgBase, for: .navigationBar)
    }
}

private struct NoneText: View {
    var text = "なし"
    var body: some View { Text(text).font(.bodyMedium).foregroundStyle(Mosimo.textSecondary).padding(16).frame(maxWidth: .infinity, alignment: .leading) }
}

struct TrustScreen: View {
    @EnvironmentObject private var mesh: MeshCoordinator
    var body: some View {
        let state = mesh.state
        let roots = state.trustRoots, sources = mesh.trustSources()
        SubScreen(title: "公式情報とみなす発信元") {
            Text("信頼の起点はこの端末の設定だけで決まります。受信したデータから追加されることはありません。").font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
            Header(text: "信頼する root 鍵 \(roots.filter(\.root.enabled).count)")
            if roots.isEmpty { Group_ { NoneText(text: "なし（公的情報の投入は無効）") } }
            ForEach(roots, id: \.root.pubkeyHex) { entry in
                let root = entry.root
                Group_ {
                    SwitchRow(icon: "checkmark.shield", tint: root.enabled ? Mosimo.safe : Mosimo.textTertiary, title: root.name,
                              subtitle: "\(originLabel(entry.origin))・鍵 \(root.pubkeyHex.prefix(16))…",
                              isOn: Binding(get: { root.enabled }, set: { mesh.setRootEnabled(root.pubkeyHex, $0) }), id: "trust.root.\(root.pubkeyHex.prefix(16))")
                    Line()
                    HStack(spacing: 8) {
                        detail("許可する役割: \(root.roles.sorted().joined(separator: "・").nilIfEmpty ?? "なし")")
                        if entry.origin == .user {
                            Button("削除") { mesh.removeRoot(root.pubkeyHex) }.font(.labelLarge).foregroundStyle(Mosimo.danger).padding(.trailing, 16)
                        }
                    }
                }
            }
            AddRootSection()
            Header(text: "取り込みを選択した Bot・フィードと種別")
            if sources.isEmpty { Group_ { NoneText() } }
            ForEach(sources) { source in
                Group_ {
                    SettingRow(icon: "arrow.down.circle", tint: Mosimo.info, title: source.name, value: "\(source.types.count)種別")
                    Line()
                    detail(source.types.map(bsafLabel).joined(separator: "・"))
                }
            }
            Header(text: "失効情報")
            Group_ {
                SettingRow(icon: "nosign", tint: Mosimo.textSecondary, title: "保持している失効通知", value: "\(state.revocationCount)件")
                if !state.revocationChecked.isEmpty {
                    Line()
                    detail(state.revocationChecked.sorted { $0.key < $1.key }.map { "\($0.key): \(dateTime($0.value)) に確認" }.joined(separator: "\n"))
                }
            }
        }
    }

    private func detail(_ text: String) -> some View {
        Text(text).font(.bodyMedium).foregroundStyle(Mosimo.textSecondary).padding(16).frame(maxWidth: .infinity, alignment: .leading)
    }

    private func originLabel(_ o: PublicIngress.RootOrigin) -> String {
        switch o {
        case .builtin: return "アプリに同梱"
        case .test: return "テスト投稿用（開発版のみ）"
        case .user: return "自分で追加"
        }
    }
}

/// Adds a root by its 64-hex public key. Only what the user types here becomes a trust anchor (attest §3).
private struct AddRootSection: View {
    @EnvironmentObject private var mesh: MeshCoordinator
    @State private var key = ""
    @State private var name = ""
    @State private var error = false

    var body: some View {
        Header(text: "root 鍵を追加")
        Group_ {
            VStack(alignment: .leading, spacing: 8) {
                Text("信頼する発行元から公式の経路で受け取った公開鍵（64桁の16進数）だけを追加してください。追加した root から署名された転載・発表は公式情報として表示されます。")
                    .font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
                TextField("公開鍵", text: $key)
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .padding(12).overlay(RoundedRectangle(cornerRadius: 8).stroke(error ? Mosimo.danger : Mosimo.borderStrong))
                    .onChange(of: key) { _, _ in error = false }
                    .accessibilityIdentifier("trust.addRoot.key")
                if error {
                    Text("64桁の16進数の公開鍵を入力してください").font(.bodySmall).foregroundStyle(Mosimo.danger)
                }
                TextField("名前（例: ○○市）", text: $name)
                    .padding(12).overlay(RoundedRectangle(cornerRadius: 8).stroke(Mosimo.borderStrong))
                HStack {
                    Spacer()
                    Button("追加する") { if mesh.addRoot(key, name) { key = ""; name = "" } else { error = true } }
                        .font(.labelLarge).disabled(key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("trust.addRoot.add")
                }
            }
            .padding(16)
        }
    }
}

struct TermsScreen: View {
    var body: some View { SubScreen(title: "利用規約") { TermsText().padding(.top, 8) } }
}

struct PolicyScreen: View {
    var body: some View { SubScreen(title: "プライバシーポリシー") { PolicyText().padding(.top, 8) } }
}

struct AdvancedScreen: View {
    @EnvironmentObject private var mesh: MeshCoordinator
    @State private var relay = ""
    /// Privacy policy §3: anonymous usage statistics, on unless the user turns them off. Nothing is sent yet.
    @AppStorage(UsageStats.key) private var usageStats = true

    var body: some View {
        let state = mesh.state
        SubScreen(title: "詳細設定") {
            Header(text: "利用状況")
            Group_ {
                SwitchRow(icon: "chart.bar", tint: Mosimo.info, title: "利用状況の統計を送信",
                          subtitle: "個人を識別しない利用状況を送信し、アプリの改善と統計に使います", isOn: $usageStats, id: "settings.usageStats")
            }
            #if DEBUG
            Header(text: "通信")
            Group_ {
                SwitchRow(icon: "arrow.left.arrow.right", tint: Mosimo.info, title: "オフライン中継を有効にする", subtitle: "圏外時に近くの端末と情報を交換",
                          isOn: Binding(get: { mesh.state.running }, set: { on in
                              mesh.relayEnabled = on
                              if on { mesh.start() } else { mesh.stop() }
                          }), enabled: state.ready || state.running, id: "settings.relay")
                Line()
                SwitchRow(icon: "clock", tint: Mosimo.info, title: "端末時計を信頼", subtitle: "公的情報の検証に必要",
                          isOn: Binding(get: { mesh.state.clockConfidence == .trusted }, set: { mesh.setClockConfidence($0 ? .trusted : .unknown) }))
            }
            Header(text: "オンライン搬出先リレー")
            Group_ {
                ForEach(Array(state.relays.enumerated()), id: \.element) { i, url in
                    if i > 0 { Line() }
                    SettingRow(icon: nil, tint: Mosimo.info, title: url, subtitle: (state.queued[url] ?? 0) > 0 ? "未送信 \(state.queued[url]!)件" : nil, trailing: {
                        Button("削除") { mesh.removeRelay(url) }.font(.labelLarge).foregroundStyle(Mosimo.danger).disabled(!state.ready)
                    })
                }
                if state.relays.isEmpty { NoneText() }
            }
            HStack(spacing: 8) {
                TextField("wss://… または ws://…", text: $relay)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    .padding(12).overlay(RoundedRectangle(cornerRadius: 8).stroke(Mosimo.borderStrong))
                Button("追加") { mesh.addRelay(relay); relay = "" }.font(.labelLarge)
                    .disabled(!state.ready || relay.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Group_ {
                SwitchRow(icon: "icloud.and.arrow.up", tint: Mosimo.info, title: "オンライン搬出", subtitle: exportStatus(state),
                          isOn: Binding(get: { mesh.state.onlineEnabled }, set: { mesh.setOnlineEnabled($0) }), enabled: state.ready)
            }
            Header(text: "表示")
            Group_ {
                SwitchRow(icon: "eye.slash", tint: Mosimo.info, title: "配送期限を過ぎた投稿を非表示",
                          subtitle: "配送期限を過ぎた投稿を一覧・地図に出さない（端末時計を信頼しているときのみ）",
                          isOn: Binding(get: { mesh.state.hideExpired }, set: { mesh.setHideExpired($0) }))
            }
            Header(text: "近隣端末 \(state.neighbors.count)")
            Group_ {
                ForEach(Array(state.neighbors.enumerated()), id: \.element.id) { i, peer in
                    if i > 0 { Line() }
                    SettingRow(icon: "iphone.radiowaves.left.and.right", tint: Mosimo.info, title: "node \(peer.name)（未認証）", subtitle: "\(peer.endpoint) / \(peer.status)")
                }
                if state.neighbors.isEmpty { NoneText() }
            }
            Header(text: "発信者・公的情報")
            Group_ {
                VStack(alignment: .leading, spacing: 6) {
                    Text(state.registration).foregroundStyle(Mosimo.textPrimary)
                    if !state.pdsConfigured {
                        Text("PDS 未設定のため PDS・Web には公開されません（データ消去で再作成）").foregroundStyle(Mosimo.warning)
                    }
                    Text(state.publicStatus).foregroundStyle(Mosimo.textSecondary)
                    ForEach(Array(state.publicResults.enumerated()), id: \.offset) { Text($0.element).foregroundStyle(Mosimo.textSecondary) }
                }
                .font(.bodySmall).textSelection(.enabled).padding(16).frame(maxWidth: .infinity, alignment: .leading)
            }
            if !state.clockSkews.isEmpty {
                Header(text: "近隣端末の時刻ヒント（未検証）")
                Group_ {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(state.clockSkews, id: \.peer) { s in
                            Text("\(s.peer.prefix(12)): \(s.deltaSeconds >= 0 ? "+" : "")\(s.deltaSeconds)秒（申告 confidence \(s.confidence)）")
                                .foregroundStyle(Mosimo.textSecondary)
                        }
                        Text("相手の自己申告であり、時計の同期・証明には使いません。").font(.labelSmall).foregroundStyle(Mosimo.textTertiary)
                    }
                    .font(.bodySmall).padding(16).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            Header(text: "診断")
            Group_ {
                Text(state.diagnostics.suffix(16).joined(separator: "\n").nilIfEmpty ?? "なし")
                    .font(.system(size: 12, design: .monospaced)).foregroundStyle(Mosimo.textSecondary).textSelection(.enabled)
                    .padding(16).frame(maxWidth: .infinity, alignment: .leading)
            }
            #endif
        }
    }

    private func exportStatus(_ s: MeshState) -> String {
        if !s.onlineEnabled { return "停止中・搬出待ちは保持" }
        if !s.onlineActive { return "中継開始または画面表示で接続" }
        if !s.network { return "ネットワークなし・接続されたらすぐに送信" }
        if let relay = s.sendingRelay { return "接続中: \(relay)" }
        if s.retryInSeconds > 0 { return "接続失敗・\(s.retryInSeconds)秒後に再試行" }
        return "接続待ち"
    }
}

struct IdentityScreen: View {
    @EnvironmentObject private var mesh: MeshCoordinator
    var body: some View {
        let state = mesh.state
        SubScreen(title: "署名鍵とID") {
            Group_ {
                ForEach(Array([("DID", state.did), ("Nostr 公開鍵", state.publicKey)].enumerated()), id: \.offset) { i, item in
                    if i > 0 { Line() }
                    SettingRow(icon: nil, tint: Mosimo.textSecondary, title: item.0, subtitle: item.1.nilIfEmpty ?? "準備中", trailing: {
                        Button { UIPasteboard.general.string = item.1 } label: {
                            Image(systemName: "doc.on.doc").foregroundStyle(Mosimo.textSecondary).frame(width: 44, height: 44)
                        }
                        .disabled(item.1.isEmpty)
                        .accessibilityLabel("コピー")
                    })
                }
            }
        }
    }
}

struct BlockedScreen: View {
    @EnvironmentObject private var mesh: MeshCoordinator
    var body: some View {
        let state = mesh.state
        SubScreen(title: "ブロックリスト") {
            Text("発信鍵（Nostr 公開鍵）単位で非表示にし、中継もしません。相手の端末との通信は遮断しません。").font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
            if state.blockedPubkeys.isEmpty {
                Text("ブロック中の投稿者はいません").font(.bodyMedium).foregroundStyle(Mosimo.textTertiary).padding(.vertical, 24)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Group_ {
                    ForEach(Array(state.blockedPubkeys.enumerated()), id: \.element) { i, key in
                        if i > 0 { Line() }
                        SettingRow(icon: "nosign", tint: Mosimo.danger, title: "鍵 \(key.prefix(16))…", trailing: {
                            Button("解除") { mesh.unblock(key) }.font(.labelLarge).disabled(!state.ready)
                        })
                    }
                }
            }
        }
    }
}

/// Privacy policy §3 switch, shared with a future usage reporter (none sends yet).
enum UsageStats {
    static let key = "usageStats"
    static var enabled: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? true }
}
