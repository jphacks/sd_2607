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

/// Tinted square icon + label heading a card: BSAF type or 「周辺の投稿」.
struct KindLabel: View {
    let post: PostView
    var body: some View {
        let (icon, color, label) = post.isPublic ? ("info.circle", post.priorityColor, bsafLabel(post.ext.bsaf?.type))
            : ("mappin.and.ellipse", Mosimo.info, "周辺の投稿")
        HStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 14)).foregroundStyle(color)
                .frame(width: 26, height: 26).background(color.opacity(0.16), in: RoundedRectangle(cornerRadius: 6))
            Text(label).font(.labelLarge).foregroundStyle(color)
        }
    }
}

/// Import-time importer authorization only; never claims the content is 公式.
struct BsafVerification: View {
    let post: PostView
    var body: some View {
        if post.importerAuthorized {
            HStack(spacing: 2) {
                Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)).foregroundStyle(Mosimo.safe)
                Text(" \(post.bsafSource)（BSAF転載）・転載者認可済").font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
            }
        } else {
            Text("\(post.bsafSource)（BSAF転載）・認可を確認できません").font(.bodySmall).foregroundStyle(Mosimo.textTertiary)
        }
    }
}

func openAppSettings() {
    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
}

enum Tab { case timeline, map, settings }
enum Route: Hashable { case post(String), trust, advanced, identity, blocked }

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
