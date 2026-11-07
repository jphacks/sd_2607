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
                    fact(post.isPublic ? "転載者" : "投稿者", shortDid(post.did, post.pubkey))
                    if let ends = post.ext.validityEndsAt { fact("有効期限", Instant.parse(ends).map { dateTime($0.epochSecond) } ?? ends) }
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
                }
                HStack(spacing: 8) {
                    if let onShowOnMap, post.coordinate != nil {
                        Button("マップで見る", action: onShowOnMap).font(.labelLarge).foregroundStyle(Mosimo.primary)
                            .padding(.horizontal, 16).frame(height: 40).overlay(Capsule().stroke(Mosimo.borderStrong))
                    }
                    if let did = post.did, did != state.did {
                        Button("ブロック") { confirmBlock = true }.font(.labelLarge).foregroundStyle(Mosimo.danger).padding(.horizontal, 12).frame(height: 40)
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
            Button("ブロック", role: .destructive) {
                if let did = post.did { mesh.block(did) }
                onBlocked()
            }
        } message: { Text("この人の投稿はすべて表示されなくなります。") }
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

/// 「確認した N」「補足する」「訂正を報告」 for a user post; publishes #annotation posts, with origin when a fix exists.
struct AnnotationActions: View {
    let target: PostView
    let annotations: [PostView]
    @EnvironmentObject private var mesh: MeshCoordinator
    @EnvironmentObject private var location: LocationRepository
    @State private var sent = false
    @State private var writing: Relation?
    @State private var text = ""

    var body: some View {
        let endorsed = endorsers(annotations)
        let mine = sent || endorsed.contains(mesh.state.did) || endorsed.contains(mesh.state.publicKey)
        HStack(spacing: 8) {
            action("確認した \(endorsed.count)", "checkmark", Mosimo.safe, enabled: !mine) {
                sent = true
                send(.endorsement, "確認しました：" + String(target.body.prefix(20)))
            }
            action("補足する", "pencil", Mosimo.textSecondary) { text = ""; writing = .supplement }
            action("訂正を報告", "flag", Mosimo.textSecondary) { text = ""; writing = .correction }
        }
        .alert(writing == .supplement ? "補足する" : "訂正を報告", isPresented: Binding(get: { writing != nil }, set: { if !$0 { writing = nil } })) {
            TextField("", text: Binding(get: { text }, set: { text = String($0.prefix(140)) }), axis: .vertical)
            Button("キャンセル", role: .cancel) { writing = nil }
            Button("送信") {
                let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
                if let r = writing, !body.isEmpty { send(r, body) }
                writing = nil
            }
        } message: { Text("\(text.count)/140") }
    }

    private func send(_ relation: Relation, _ body: String) {
        var ext = [Extensions.annotation(relation, targetEventId: target.id)]
        if let fix = location.location {
            ext.append(Extensions.origin(Origin(latitudeMicrodegrees: micro(fix.coordinate.latitude), longitudeMicrodegrees: micro(fix.coordinate.longitude),
                                                accuracyMillimeters: fix.horizontalAccuracy >= 0 ? Int64((fix.horizontalAccuracy * 1000).rounded()) : nil)))
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
