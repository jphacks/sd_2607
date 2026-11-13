import MosimoCore
import SwiftUI

extension PostView {
    /// hazard §8: the post's own chain, confirmed now against the local roots, ends in `public-information` or in an importer
    /// authorized for its (Bot, type); else a user claim. Checked from the post itself, so mesh, relay and import all agree.
    var officialHazard: Bool { chain.map { $0.confirmedNow && ($0.publicInformation || $0.importAuthorized) } == true }
    /// Severity colour for official notices only; a user claim never looks like an alert.
    var hazardColor: Color? { ext.hazard.map { officialHazard ? severityColor($0) : Mosimo.textSecondary } }
    /// Area colour on the map: severity for official notices, nil (keep the default) otherwise.
    var hazardAreaColor: Color? { ext.hazard.flatMap { officialHazard ? severityColor($0) : nil } }
    /// Badge title with the 「訓練」「試験」 prefix, for pins and list rows.
    var hazardTitle: String? { ext.hazard.map { h in [HazardLabels.status(h.status), HazardLabels.title(h)].compactMap { $0 }.joined(separator: " ") } }
}

func severityColor(_ h: Hazard) -> Color {
    if h.allClear { return Mosimo.safe }
    switch h.severity {
    case "Extreme": return Mosimo.extreme
    case "Severe": return Mosimo.danger
    case "Moderate": return Mosimo.warning
    case "Minor": return Mosimo.minor
    default: return Mosimo.textSecondary
    }
}

func hazardSymbol(_ h: Hazard) -> String {
    switch h.kind {
    case .earthquake?: return "waveform.path.ecg"
    case .tsunami?: return "water.waves"
    case .volcano?: return "mountain.2.fill"
    case .weather?: return "cloud.rain.fill"
    case .cyclone?: return "hurricane"
    case nil: return "exclamationmark.triangle.fill"
    }
}

/// Card/detail header for a hazard post: kind, first scale, severity colour; user claims are neutral and say so.
struct HazardBadge: View {
    let post: PostView
    let hazard: Hazard
    var body: some View {
        let color = post.hazardColor ?? Mosimo.textSecondary
        let scale = hazard.scales.first.map(HazardLabels.scale)
        HStack(spacing: 6) {
            Image(systemName: hazardSymbol(hazard)).font(.system(size: 14)).foregroundStyle(color)
                .frame(width: 26, height: 26).background(color.opacity(0.16), in: RoundedRectangle(cornerRadius: 6))
                .padding(.trailing, 2)
            if let status = HazardLabels.status(hazard.status) { tag(status, Mosimo.info) }
            Text(HazardLabels.title(hazard)).font(.labelLarge).foregroundStyle(color).lineLimit(2)
            if let scale { Text(scale).font(.labelMedium.bold()).foregroundStyle(color).lineLimit(1) }
            if !post.officialHazard { tag("利用者の申告", Mosimo.textSecondary) }
        }
    }
    private func tag(_ text: String, _ color: Color) -> some View {
        Text(text).font(.labelSmall).foregroundStyle(color).fixedSize()
            .padding(.horizontal, 6).padding(.vertical, 2)
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(color.opacity(0.6), lineWidth: 1))
    }
}

/// Under the card header: the original name when it adds something, and the earthquake summary.
struct HazardSummary: View {
    let hazard: Hazard
    var body: some View {
        let event = hazard.event?.text.trimmingCharacters(in: .whitespaces)
        if let event, !event.isEmpty, event != HazardLabels.kind(hazard), event != HazardLabels.title(hazard) {
            Text(event).font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
        }
        if let summary = HazardLabels.earthquakeSummary(hazard) {
            Text(summary).font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
        }
    }
}

/// Post detail 「災害情報」 section (hazard §2, §7, §8).
struct HazardSection: View {
    let post: PostView
    let hazard: Hazard
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("災害情報").font(.titleMedium).foregroundStyle(Mosimo.textPrimary).padding(.bottom, 4)
            HazardBadge(post: post, hazard: hazard).padding(.bottom, 4)
            if let event = hazard.event?.text, !event.isEmpty { fact("原名称", event) }
            fact("深刻度", HazardLabels.severity(hazard.severity))
            fact("切迫度", HazardLabels.urgency(hazard.urgency))
            fact("確実性", HazardLabels.certainty(hazard.certainty))
            if let r = HazardLabels.responses(hazard.responseTypes) { fact("取るべき行動", r) }
            if !hazard.scales.isEmpty { fact("階級", hazard.scales.map(HazardLabels.scale).joined(separator: "\n")) }
            if let onset = hazard.onset { fact("発生時刻", time(onset)) }
            ForEach(Array(quantities.enumerated()), id: \.offset) { fact($0.element.0, $0.element.1) }
            if let glide = hazard.glide { fact("GLIDE", glide) }
            // hazard §8: only a public-information chain is the issuer's own announcement; an import is a re-post.
            if post.chain.map({ $0.confirmedNow && $0.publicInformation }) == true {
                trust("checkmark.seal", Mosimo.safe, "公的な発表として確認済み")
            } else if post.importerAuthorized {
                trust("checkmark", Mosimo.safe, "認可された転載者による情報")
            } else {
                trust("info.circle", Mosimo.textSecondary, "利用者の申告（未確認）")
            }
            if post.ext.areas.contains(where: \.approximation) {
                Text("範囲は都道府県を覆う近似です").font(.labelSmall).foregroundStyle(Mosimo.textTertiary)
            }
        }
    }

    /// Type-specific facts in display order.
    private var quantities: [(String, String)] {
        func point(_ lat: Int64?, _ lon: Int64?) -> String? {
            guard let lat, let lon else { return nil }
            return coordsLabel(Double(lat) / 1e6, Double(lon) / 1e6)
        }
        switch hazard.kind {
        case let .earthquake(_, hypo, mag)?:
            return [mag.map { ("規模", HazardLabels.magnitude($0)) }, hypo?.name.map { ("震源", $0.text) },
                    point(hypo?.lat, hypo?.lon).map { ("震央", $0) }, hypo?.depth.map { ("深さ", String(HazardLabels.depth($0).dropFirst(2))) }]
                .compactMap { $0 }
        case let .tsunami(height, arrival)?:
            return [height.flatMap(HazardLabels.tsunamiHeight).map(split), arrival.map { ("到達予想", time($0)) }].compactMap { $0 }
        case let .volcano(_, v)?:
            return [v?.name.map { ("火山", $0.text) }, v?.gvp.map { ("GVP番号", $0) }, point(v?.lat, v?.lon).map { ("位置", $0) }].compactMap { $0 }
        case let .cyclone(c)?:
            return [c.name.map { ("名称", $0.text) }, c.designation.map { ("番号", $0) }, c.center.map { ("中心", coordsLabel(Double($0.lat) / 1e6, Double($0.lon) / 1e6)) },
                    c.pressure.map { split(HazardLabels.pressure($0)) }, c.maxWind.map { split(HazardLabels.wind($0)) },
                    c.stormRadius.map { split(HazardLabels.stormRadius($0)) }].compactMap { $0 }
        case .weather?, nil: return []
        }
    }
    /// 「中心気圧 970hPa」 → (「中心気圧」, 「970hPa」).
    private func split(_ s: String) -> (String, String) {
        let parts = s.split(separator: " ", maxSplits: 1).map(String.init)
        return parts.count == 2 ? (parts[0], parts[1]) : ("", s)
    }
    private func time(_ s: String) -> String { Instant.parse(s).map { dateTime($0.epochSecond) } ?? s }
    private func trust(_ icon: String, _ color: Color, _ text: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon).font(.system(size: 12)).foregroundStyle(color)
            Text(text).font(.bodySmall).foregroundStyle(color)
        }
    }
}
