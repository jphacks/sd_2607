import CoreLocation
import MosimoCore
import SwiftUI

/// Shared by the timeline (full screen) and the map (bottom sheet). [onShowOnMap] is nil where already on the map. Scrolls itself.
struct PostDetail: View {
    let post: PostView
    var onShowOnMap: (() -> Void)?
    var onBlocked: () -> Void = {}
    @EnvironmentObject private var mesh: MeshCoordinator
    @EnvironmentObject private var location: LocationRepository
    @State private var confirmBlock = false
    @State private var confirmDelete = false
    @State private var reporting = false

    var body: some View {
        let state = mesh.state
        let annotations = (annotationsByTarget(state.posts)[post.id] ?? []).sorted { $0.createdAt < $1.createdAt }
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    KindLabel(post: post)
                    if let c = post.category { CategoryBadge(category: c) }
                }
                Text(post.isPublic ? post.content : post.body).font(.bodyLarge).foregroundStyle(Mosimo.textPrimary)
                    .textSelection(.enabled)
                VStack(alignment: .leading, spacing: 4) {
                    fact("時刻", dateTime(post.eventTime))
                    if post.isPublic { fact("転載", dateTime(post.createdAt)) }
                    if let d = post.distance(from: location.location) { fact("距離", distanceLabel(d) + "先") }
                    fact("経路", post.routeLabel)
                    fact(post.isPublic ? "転載者" : "投稿者", post.senderName ?? shortDid(post.did, post.pubkey))
                    if let validity = post.validityLabel { fact("情報の有効期間", validity) }
                    if post.isPublic {
                        BsafVerification(post: post)
                        if let audit = post.publicAuthorization { Text(audit).font(.labelSmall).foregroundStyle(Mosimo.textTertiary) }
                    } else {
                        HStack(spacing: 2) {
                            Image(systemName: "checkmark").font(.system(size: 11, weight: .bold))
                            Text(" 署名検証済み").font(.bodySmall)
                        }
                        .foregroundStyle(Mosimo.safe)
                    }
                    if post.ext.hasChain || post.chain != nil { SenderVerification(chain: post.chain) }
                }
                FlowLayout(spacing: 8) {
                    if let onShowOnMap, post.coordinate != nil {
                        Button("マップで見る", action: onShowOnMap).font(.labelLarge).foregroundStyle(Mosimo.primary)
                            .padding(.horizontal, 16).frame(height: 40).overlay(Capsule().stroke(Mosimo.borderStrong))
                    }
                    if post.pubkey != state.publicKey {
                        // Official (importer) posts are report-only: blocking the importer key would hide all official information.
                        if !post.isPublic {
                            Button("ブロック") { confirmBlock = true }.font(.labelLarge).foregroundStyle(Mosimo.danger).padding(.horizontal, 12).frame(height: 40)
                        }
                    } else if !post.volatile {
                        Button("削除を依頼") { confirmDelete = true }.font(.labelLarge).foregroundStyle(Mosimo.danger).padding(.horizontal, 12).frame(height: 40)
                    }
                    if post.pubkey != state.publicKey {
                        let done = state.reported.contains(post.id)
                        Button(done ? "報告済み" : "報告") { reporting = true }.font(.labelLarge).foregroundStyle(Mosimo.textSecondary)
                            .padding(.horizontal, 12).frame(height: 40).disabled(done || !state.ready)
                    }
                }
                Divider().overlay(Mosimo.border)
                if post.isPublic {
                    Text("確認した \(endorsers(annotations).count)人").font(.bodySmall).foregroundStyle(Mosimo.safe)
                } else {
                    AnnotationActions(target: post, annotations: annotations)
                }
                ForEach(annotations.filter { $0.ext.annotation?.relation != .endorsement }) { AnnotationItem(annotation: $0, target: post) }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .alert("この投稿者をブロックしますか？", isPresented: $confirmBlock) {
            Button("キャンセル", role: .cancel) {}
            Button("ブロック", role: .destructive) { mesh.block(post.pubkey); onBlocked() }
        } message: { Text("この発信鍵の投稿はすべて表示されず、中継もしません。相手の端末との通信は止めません。") }
        .sheet(isPresented: $reporting) {
            ReportSheet(post: post, onClose: { reporting = false }, onBlocked: onBlocked)
        }
        .alert("この投稿の削除を依頼しますか？", isPresented: $confirmDelete) {
            Button("キャンセル", role: .cancel) {}
            Button("削除を依頼", role: .destructive) { mesh.delete(post.id); onBlocked() }
        } message: { Text("削除要求を署名して配送し、この端末では表示・中継をやめます。既に受け取った端末やサーバーから確実に消えるとは限りません。") }
    }

    private func fact(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 0) {
            Text(label).font(.bodySmall).foregroundStyle(Mosimo.textTertiary).frame(width: 72, alignment: .leading)
            Text(value).font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
        }
    }
}

private struct AnnotationItem: View {
    let annotation: PostView
    let target: PostView
    var body: some View {
        let (label, color): (String, Color) = annotation.ext.annotation?.relation == .supplement ? ("補足", Mosimo.info)
            : (annotation.did ?? annotation.pubkey) == (target.did ?? target.pubkey) ? ("投稿者本人による訂正", Mosimo.warning)
            : ("他の人からの訂正報告", Mosimo.warning)
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.labelMedium.bold()).foregroundStyle(color)
            Text(annotation.content).font(.bodyMedium).foregroundStyle(Mosimo.textPrimary)
            Text("\(shortDid(annotation.did, annotation.pubkey)) · \(dateTime(annotation.createdAt))").font(.labelSmall).foregroundStyle(Mosimo.textTertiary)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(Mosimo.bgSurface2, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// 「確認した N」「補足する」「訂正を投稿」 for a user post; publishes #annotation posts, with origin when a fix exists.
struct AnnotationActions: View {
    let target: PostView
    let annotations: [PostView]
    @EnvironmentObject private var mesh: MeshCoordinator
    @EnvironmentObject private var location: LocationRepository
    @AppStorage("termsAccepted") private var termsAccepted = false
    @State private var sent = false
    @State private var writing: Relation?
    @State private var text = ""
    @State private var askTerms = false
    @State private var pending: (() -> Void)?

    var body: some View {
        let endorsed = endorsers(annotations)
        let mine = sent || endorsed.contains(mesh.state.did) || endorsed.contains(mesh.state.publicKey)
        HStack(spacing: 8) {
            action("確認した \(endorsed.count)", "checkmark", Mosimo.safe, enabled: !mine) {
                agreed {
                    sent = true
                    send(.endorsement, "確認しました：" + String(target.body.prefix(20)))
                }
            }
            action("補足する", "pencil", Mosimo.textSecondary) { agreed { text = ""; writing = .supplement } }
            action("訂正を投稿", "flag", Mosimo.textSecondary) { agreed { text = ""; writing = .correction } }
        }
        // The pending action runs after the sheet is gone; the sheet has its own host so the alert below is free to present.
        .background {
            Color.clear.sheet(isPresented: $askTerms, onDismiss: {
                if termsAccepted { pending?() }
                pending = nil
            }) { TermsAgreement(onClose: { askTerms = false }, onAgree: { askTerms = false }) }
        }
        .alert(writing == .supplement ? "補足する" : "訂正を投稿", isPresented: Binding(get: { writing != nil }, set: { if !$0 { writing = nil } })) {
            TextField("", text: Binding(get: { text }, set: { text = String($0.prefix(140)) }), axis: .vertical)
            Button("キャンセル", role: .cancel) { writing = nil }
            Button("送信") {
                let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
                if let r = writing, !body.isEmpty { send(r, body) }
                writing = nil
            }
        } message: { Text("\(text.count)/140") }
    }

    /// Runs [run] now, or once the first-post terms are agreed.
    private func agreed(_ run: @escaping () -> Void) {
        if termsAccepted { run() } else { pending = run; askTerms = true }
    }

    private func send(_ relation: Relation, _ body: String) {
        guard let annotation = try? Extensions.annotation(relation, targetEventId: target.id) else { return }
        var ext = [annotation]
        if let fix = location.location {
            ext.append(Extensions.origin(Origin(lat: micro(fix.coordinate.latitude), lon: micro(fix.coordinate.longitude),
                                                acc: fix.horizontalAccuracy >= 0 ? Int64((fix.horizontalAccuracy * 1000).rounded()) : nil)))
        }
        mesh.publish(body, extensions: ext)
    }

    private func action(_ label: String, _ icon: String, _ color: Color, enabled: Bool = true, onTap: @escaping () -> Void) -> some View {
        Button(action: onTap) {
            HStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 11, weight: .bold))
                Text(label).font(.labelMedium)
            }
            .foregroundStyle(color).padding(.horizontal, 10).padding(.vertical, 6)
            .background(Mosimo.bgSurface2, in: RoundedRectangle(cornerRadius: 8))
            .opacity(enabled ? 1 : 0.6)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// Full-screen post detail pushed from the timeline.
struct PostScreen: View {
    let id: String
    @EnvironmentObject private var mesh: MeshCoordinator
    @EnvironmentObject private var nav: Navigator
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let post = mesh.state.posts.first(where: { $0.id == id }) {
                PostDetail(post: post, onShowOnMap: { nav.showOnMap(post.id) }, onBlocked: { dismiss() })
            } else {
                EmptyState(title: "この投稿は見つかりません")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Mosimo.bgBase)
        .navigationTitle("投稿")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Mosimo.bgBase, for: .navigationBar)
    }
}

struct EmptyState: View {
    let title: String
    var detail: String? = nil
    var body: some View {
        VStack(spacing: 4) {
            Text(title).font(.titleMedium).foregroundStyle(Mosimo.textSecondary)
            if let detail { Text(detail).font(.bodySmall).foregroundStyle(Mosimo.textTertiary) }
        }
        .padding(32).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// report §1: NIP-56 report types with the labels shown in both apps.
let reportTypes: [(type: String, label: String)] = [
    ("spam", "スパム・宣伝"), ("profanity", "不快な表現・嫌がらせ"), ("illegal", "違法な内容"), ("impersonation", "なりすまし"),
    ("nudity", "性的な内容"), ("malware", "有害なリンク"), ("other", "その他（虚偽の情報・個人情報など）"),
]

/// Publishes a signed report (report §1). It travels like a post; each receiver decides what to do with it.
private struct ReportSheet: View {
    let post: PostView
    let onClose: () -> Void
    let onBlocked: () -> Void
    @EnvironmentObject private var mesh: MeshCoordinator
    @State private var type: String?
    @State private var comment = ""
    @State private var block = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("この投稿を報告").font(.titleLarge).foregroundStyle(Mosimo.textPrimary)
                Spacer()
                Button(action: onClose) { Image(systemName: "xmark").foregroundStyle(Mosimo.textSecondary).frame(width: 44, height: 44) }
                    .accessibilityLabel("閉じる")
            }
            Text("報告はあなたの鍵で署名された公開情報として、周辺の端末やサーバーに届きます。報告をどう扱うかは、受け取った各アプリ・サーバーが判断します。")
                .font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(reportTypes, id: \.type) { item in
                        Button { type = item.type } label: {
                            HStack {
                                Image(systemName: type == item.type ? "largecircle.fill.circle" : "circle")
                                    .foregroundStyle(type == item.type ? Mosimo.primary : Mosimo.textTertiary)
                                Text(item.label).font(.bodyMedium).foregroundStyle(Mosimo.textPrimary)
                                Spacer()
                            }
                            .padding(.vertical, 10).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    TextField("補足（任意・公開されます）", text: Binding(get: { comment }, set: { comment = String($0.prefix(90)) }), axis: .vertical)
                        .lineLimit(2...4).padding(12).overlay(RoundedRectangle(cornerRadius: 8).stroke(Mosimo.borderStrong)).padding(.top, 8)
                    // Official (importer) posts are report-only: blocking the importer key would hide all official information.
                    if !post.isPublic {
                        Toggle("この投稿者をブロックする", isOn: $block).font(.bodyMedium).tint(Mosimo.primary).padding(.top, 12)
                    }
                }
            }
            Button {
                guard let type else { return }
                mesh.report(post.id, type: type, comment: comment, block: block)
                onClose()
                if block { onBlocked() }
            } label: {
                Text("報告する").font(.labelLarge).foregroundStyle(Mosimo.onPrimary).frame(maxWidth: .infinity).frame(height: 52)
                    .background(type == nil ? Mosimo.textTertiary : Mosimo.danger, in: RoundedRectangle(cornerRadius: 26))
            }
            .disabled(type == nil)
        }
        .padding(16)
        .background(Mosimo.bgSurface.ignoresSafeArea())
        .presentationDetents([.large])
    }
}
