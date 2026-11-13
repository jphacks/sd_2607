import CoreLocation
import MosimoCore
import SwiftUI

struct TimelineScreen: View {
    @EnvironmentObject private var mesh: MeshCoordinator
    @EnvironmentObject private var location: LocationRepository
    @EnvironmentObject private var nav: Navigator
    @State private var publicOnly = false
    /// Previous open time (epoch s). ponytail: compares author created_at, not local arrival; skewed clocks miscount.
    @State private var since: Int64 = {
        let now = Int64(Date().timeIntervalSince1970)
        let previous = UserDefaults.standard.object(forKey: "timelineOpenedAt") as? Int64 ?? now
        UserDefaults.standard.set(now, forKey: "timelineOpenedAt")
        return previous
    }()

    var body: some View {
        let state = mesh.state
        // Newest first by event time, annotations with a present target folded away.
        let all = standalone(state.posts).sorted { $0.eventTime > $1.eventTime }
        let annotations = annotationsByTarget(state.posts)
        let cards = publicOnly ? all.filter(\.isPublic) : all
        VStack(alignment: .leading, spacing: 0) {
            Text("タイムライン").font(.titleLarge).foregroundStyle(Mosimo.textPrimary).padding(.horizontal, 16).padding(.top, 16)
            MosimoChip(label: "公式情報のみ", selected: publicOnly) { publicOnly.toggle() }
                .padding(.horizontal, 16).padding(.vertical, 8)
            if state.clockConfidence != .trusted {
                Banner(title: "時計未確認のため検証できません", action: "信頼する", color: Mosimo.warning) { mesh.setClockConfidence(.trusted) }
            }
            ScrollViewReader { scroller in
                if let at = state.lastReceivedAtMillis {
                    TimelineView(.periodic(from: .now, by: 30)) { context in
                        let minutes = (Int64(context.date.timeIntervalSince1970 * 1000) - at) / 60_000
                        let fresh = all.filter { $0.createdAt > since }.count
                        Banner(title: "\(state.lastReceivedOnline ? "オンライン" : "メッシュ経由")で\(minutes < 1 ? "たった今" : "\(minutes)分前に")更新",
                               action: fresh > 0 ? "新着 \(fresh)件" : nil) {
                            if let id = cards.first?.id { withAnimation { scroller.scrollTo(id, anchor: .top) } }
                        }
                    }
                }
                ScrollView {
                    if cards.isEmpty {
                        EmptyState(title: state.publicBusy ? "読み込み中…" : publicOnly ? "公的情報はまだありません" : "まだ投稿がありません",
                                   detail: state.publicBusy ? nil : "下に引くと公的情報を取得します")
                            .frame(minHeight: 300)
                    } else {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            ForEach(Array(cards.enumerated()), id: \.element.id) { i, post in
                                let day = Calendar.current.startOfDay(for: Date(timeIntervalSince1970: TimeInterval(post.eventTime)))
                                if i == 0 || day != Calendar.current.startOfDay(for: Date(timeIntervalSince1970: TimeInterval(cards[i - 1].eventTime))) {
                                    Text(dayLabel(day)).font(.labelMedium.bold()).foregroundStyle(Mosimo.textSecondary)
                                        .padding(.horizontal, 12).padding(.vertical, 4).background(Mosimo.bgSurface2, in: Capsule())
                                        .padding(.vertical, 8)
                                }
                                HStack(alignment: .top, spacing: 0) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(hhmm(post.eventTime)).font(.number(13)).foregroundStyle(Mosimo.textSecondary)
                                        Rectangle().fill(Mosimo.border).frame(width: 1).frame(maxHeight: .infinity).padding(.leading, 18)
                                    }
                                    .frame(width: 52, alignment: .leading)
                                    PostCard(post: post, annotations: annotations[post.id] ?? [], here: location.location)
                                }
                                .padding(.bottom, 12)
                                .id(post.id)
                            }
                        }
                        .padding(.horizontal, 16).padding(.bottom, 16)
                    }
                }
                .accessibilityIdentifier("timeline.list")
                .refreshable { await refresh() }
            }
        }
        .background(Mosimo.bgBase)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { location.refresh() }
    }

    /// Pull-to-refresh: public information over the PDS relay; returns once the request settles (bounded).
    private func refresh() async {
        mesh.requestPublicInformation()
        try? await Task.sleep(for: .milliseconds(500))
        for _ in 0..<60 where mesh.state.publicBusy { try? await Task.sleep(for: .milliseconds(500)) }
    }
}

private struct PostCard: View {
    let post: PostView
    let annotations: [PostView]
    let here: CLLocation?
    @EnvironmentObject private var nav: Navigator

    var body: some View {
        let text = (post.isPublic ? post.content : post.body).trimmingCharacters(in: .whitespacesAndNewlines)
        let title = text.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let rest = text.contains("\n") ? String(text[text.index(after: text.firstIndex(of: "\n")!)...]).trimmingCharacters(in: .whitespacesAndNewlines) : ""
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                KindLabel(post: post)
                Spacer()
                if let c = post.category { CategoryBadge(category: c) }
            }
            if let hazard = post.ext.hazard { HazardSummary(hazard: hazard) }
            Text(title).font(.titleMedium).foregroundStyle(Mosimo.textPrimary)
            if !rest.isEmpty { Text(rest).font(.bodyMedium).foregroundStyle(Mosimo.textSecondary).lineLimit(3) }
            HStack(spacing: 0) {
                if post.isPublic { BsafVerification(post: post) }
                else {
                    Text(([post.senderName ?? shortDid(post.did, post.pubkey)] + (post.distance(from: here).map { [distanceLabel($0) + "先"] } ?? [])).joined(separator: " · "))
                        .font(.bodySmall).foregroundStyle(Mosimo.textTertiary).lineLimit(1)
                }
                Text(" · \(post.routeLabel)").font(.bodySmall).foregroundStyle(Mosimo.textTertiary).fixedSize()
                Spacer(minLength: 4)
                if post.isPublic { link("詳細 >") { nav.push(.post(post.id)) } }
                else if post.coordinate != nil { link("マップで見る >") { nav.showOnMap(post.id) } }
            }
            if !post.isPublic { AnnotationActions(target: post, annotations: annotations) }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Mosimo.bgSurface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Mosimo.border))
        .contentShape(Rectangle())
        .onTapGesture { nav.push(.post(post.id)) }
    }

    private func link(_ label: String, _ action: @escaping () -> Void) -> some View {
        Button(label, action: action).font(.labelMedium.bold()).foregroundStyle(Mosimo.info).padding(4).buttonStyle(.plain)
    }
}
