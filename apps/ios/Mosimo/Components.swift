import MosimoCore
import SwiftUI
import UIKit

/// Full-width notice row with one action; shared by permission and status banners.
struct Banner: View {
    let title: String
    var detail: String? = nil
    var action: String? = nil
    var color: Color = Mosimo.info
    var onAction: () -> Void = {}

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.titleMedium).foregroundStyle(Mosimo.textPrimary)
                if let detail { Text(detail).font(.bodySmall).foregroundStyle(Mosimo.textSecondary) }
            }
            Spacer(minLength: 8)
            if let action { Button(action, action: onAction).font(.labelLarge).foregroundStyle(color) }
        }
        .padding(12)
        .background(color.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(color.opacity(0.4), lineWidth: 1))
        .padding(.horizontal, 16).padding(.vertical, 8)
    }
}

/// Selected = primary yellow (the only place besides the CTA where it appears).
struct MosimoChip: View {
    let label: String
    let selected: Bool
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
            Text(label).font(.labelMedium)
                .foregroundStyle(selected ? Mosimo.onPrimary : Mosimo.textPrimary)
                .padding(.horizontal, 12).frame(height: 32)
                .background(selected ? Mosimo.primary : Mosimo.bgSurface.opacity(0.92), in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? Mosimo.primary : Mosimo.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct Pill: View {
    let text: String
    let color: Color
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(text).font(.labelSmall).foregroundStyle(Mosimo.textPrimary)
        }
        .padding(.horizontal, 10).padding(.vertical, 4)
        .background(Mosimo.bgBase.opacity(0.85), in: Capsule())
        .overlay(Capsule().stroke(color.opacity(0.5), lineWidth: 1))
    }
}

struct CategoryBadge: View {
    let category: Category
    var body: some View {
        Text(category.label).font(.labelMedium.bold()).foregroundStyle(category.color)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(category.color.opacity(0.16), in: RoundedRectangle(cornerRadius: 6))
    }
}

/// Tinted square icon + label heading a card: hazard badge, BSAF type or 「周辺の投稿」.
struct KindLabel: View {
    let post: PostView
    var body: some View {
        if let hazard = post.ext.hazard { HazardBadge(post: post, hazard: hazard) } else { plain }
    }
    private var plain: some View {
        let (icon, color, label) = post.isPublic ? ("info.circle", post.priorityColor, post.publicLabel)
            : ("mappin.and.ellipse", Mosimo.info, "周辺の投稿")
        return HStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 14)).foregroundStyle(color)
                .frame(width: 26, height: 26).background(color.opacity(0.16), in: RoundedRectangle(cornerRadius: 6))
            Text(label).font(.labelLarge).foregroundStyle(color)
        }
    }
}

/// importer §3.2: who re-posted (small, the importer's `organization.orgName`) apart from the original source (large: the feed's
/// source name, or a Bot re-post's BSAF `source:` with the Bot account it came from). Never claims the content is 公式.
/// [compact] (lists): one line, no links; the detail links the source to the original page and the Bot to its profile.
struct SourceAttribution: View {
    let post: PostView
    var compact = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        // The importer may be named whenever its chain is confirmed now and authorized; it re-posts, it is not the source.
        let importer = post.importerAuthorized ? post.chain?.name : nil
        let others = (post.group?.members.count ?? 1) - 1
        if compact {
            HStack(spacing: 0) {
                Text(post.originalSource).font(.labelLarge).foregroundStyle(Mosimo.textSecondary).lineLimit(1)
                Text(" · " + (importer.map { "転載: \($0)" } ?? "転載者未確認") + (others > 0 ? " · ほか\(others)件" : ""))
                    .font(.labelSmall).foregroundStyle(Mosimo.textTertiary).lineLimit(1)
            }
        } else {
            VStack(alignment: .leading, spacing: 2) {
                if let importer {
                    HStack(spacing: 2) {
                        Image(systemName: "checkmark").font(.system(size: 9, weight: .bold)).foregroundStyle(Mosimo.safe)
                        Text(" 転載: \(importer)").font(.labelSmall).foregroundStyle(Mosimo.textTertiary)
                    }
                } else {
                    Text("転載された情報・転載者の認可を確認できません").font(.labelSmall).foregroundStyle(Mosimo.textTertiary)
                }
                if let feed = post.ext.feed {
                    source(feedName(feed.id, feed.origin), post.ext.sourceWebUrl)
                } else if let bsaf = post.ext.bsaf {
                    source(bsafSourceName(bsaf.source), bsaf.originalWebUrl)
                    if let did = bsaf.botDid {
                        link("BSAF \(bsafAccount(did)) から取得", Bsaf.profileUrl(did), font: .bodySmall)
                        Text(did).font(.system(size: 9)).foregroundStyle(Mosimo.textTertiary).textSelection(.enabled)
                    }
                } else {
                    source(post.originalSource, nil)
                }
            }
        }
    }

    /// The original source, large; a link to the original page when there is one.
    private func source(_ name: String, _ url: URL?) -> some View { link(name, url, font: .titleMedium) }
    @ViewBuilder private func link(_ text: String, _ url: URL?, font: Font) -> some View {
        if let url {
            Button { openURL(url) } label: {
                HStack(spacing: 4) {
                    Text(text).font(font)
                    Image(systemName: "arrow.up.right.square").font(.system(size: 11))
                }
                .foregroundStyle(Mosimo.info)
            }
            .buttonStyle(.plain)
        } else {
            Text(text).font(font).foregroundStyle(Mosimo.textSecondary)
        }
    }
}

/// Sender confirmation (attest.chain §4): what the attestation chain proves about the key, never the truth of the post.
struct SenderVerification: View {
    let chain: PublicIngress.ChainView?
    var body: some View {
        let named = chain.flatMap { $0.identity ? $0.name : nil }
        let (icon, color, text): (String, Color, String) = {
            guard let c = chain else { return ("info.circle", Mosimo.textTertiary, "発信者未確認（署名チェーンなし）") }
            if c.revocation == .revoked { return ("info.circle", Mosimo.danger, "この発信鍵は失効しています") }
            if c.confirmedNow {
                if let named { return ("checkmark.seal", Mosimo.safe, "発信者確認済み: \(named)" + (c.trustLevel.map { "（確認度 \($0)）" } ?? "")) }
                return ("checkmark.seal", Mosimo.textSecondary, "鍵は確認済み（組織名の表示は認められていません）")
            }
            let suffix = named.map { ": \($0)" } ?? ""
            switch c.state {
            case .verified where c.revocation == .revokedLater: return ("info.circle", Mosimo.warning, "作成時点では確認済み・その後に鍵が失効" + suffix)
            case .verified:
                return ("info.circle", Mosimo.warning, (c.expiredNow == true ? "作成時点では確認済み（現在は期限切れ）" : "作成時点では確認済み（現在の有効性は時計未確認）") + suffix)
            case .expired: return ("info.circle", Mosimo.danger, "作成時点で鍵の有効期間外です（発信者未確認）")
            case .unknownTime: return ("info.circle", Mosimo.warning, "端末時計が未確認のため検証できません")
            case .unverifiable: return ("info.circle", Mosimo.textTertiary, "信頼していない発行元の証明です")
            case .invalid: return ("info.circle", Mosimo.danger, "署名チェーンを検証できません")
            }
        }()
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 12)).foregroundStyle(color)
                Text(text).font(.bodySmall).foregroundStyle(color)
            }
            if chain?.revocation == .notRevokedAsKnown {
                Text("失効情報は手元の通知時点のものです").font(.labelSmall).foregroundStyle(Mosimo.textTertiary)
            }
        }
    }
}

func openAppSettings() {
    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
}

enum Tab { case timeline, map, settings }
enum Route: Hashable { case post(String), trust, advanced, identity, blocked, terms, policy }

/// One stack per tab. The map is the start tab; posts opened from the map live in its sheet.
@MainActor
final class Navigator: ObservableObject {
    @Published var tab: Tab = .map
    @Published var timelinePath: [Route] = []
    @Published var settingsPath: [Route] = []
    /// Post id to centre and open on the map; [focusToken] re-fires the same id.
    @Published private(set) var mapFocus: String?
    @Published private(set) var focusToken = 0

    var atTabRoot: Bool {
        switch tab {
        case .timeline: return timelinePath.isEmpty
        case .settings: return settingsPath.isEmpty
        case .map: return true
        }
    }
    func select(_ t: Tab) { tab = t }
    func push(_ r: Route) {
        switch tab {
        case .timeline: timelinePath.append(r)
        case .settings: settingsPath.append(r)
        case .map: break
        }
    }
    func showOnMap(_ id: String) {
        mapFocus = id
        focusToken += 1
        select(.map)
    }
}
