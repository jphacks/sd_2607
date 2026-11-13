import Foundation

/// Japanese display labels for the hazard extensions; time formatting stays in the app (time zone, locale).
/// Unknown severity/urgency/certainty read as 不明; unknown categories, responses and phenomena are skipped.
public enum HazardLabels {
    static let categories = [
        "Geo": "地象", "Met": "気象", "Safety": "安全", "Security": "治安・国民保護", "Rescue": "救助", "Fire": "火災",
        "Health": "保健・医療", "Env": "環境", "Transport": "交通", "Infra": "インフラ", "CBRNE": "有害物質等", "Other": "その他",
    ]
    static let severities = ["Extreme": "極めて重大", "Severe": "重大", "Moderate": "警戒", "Minor": "注意"]
    static let urgencies = ["Immediate": "直ちに", "Expected": "まもなく", "Future": "今後", "Past": "経過"]
    static let certainties = ["Observed": "発生", "Likely": "可能性が高い", "Possible": "可能性あり", "Unlikely": "可能性は低い"]
    static let responses = [
        "Shelter": "安全確保", "Evacuate": "避難", "Prepare": "避難の準備", "Execute": "計画に従う", "Avoid": "近づかない",
        "Monitor": "情報に注意", "Assess": "状況を確認", "AllClear": "解除", "None": "行動不要",
    ]
    static let phenomena = [
        "rain": "大雨", "flood": "洪水", "landslide": "土砂災害", "storm-surge": "高潮", "wave": "高波", "wind": "暴風",
        "tornado": "竜巻", "thunderstorm": "雷", "snow": "大雪", "snowmelt": "融雪", "avalanche": "なだれ", "fog": "濃霧",
        "dry-air": "乾燥", "cold": "低温", "heat": "高温", "frost": "霜", "icing": "着氷", "snow-accretion": "着雪",
    ]
    static let volcanoPhenomena = ["eruption": "噴火", "ashfall": "降灰", "activity": "火山活動", "pyroclastic-flow": "火砕流", "lahar": "火山泥流", "gas": "火山ガス"]
    static let earthquakeKinds = ["event": "地震", "early-warning": "緊急地震速報", "assessment": "地震の評価"]
    /// Registered scales (hazard §5.2): value → label; a value outside the table falls back to the raw form.
    static let scales: [String: [String: String]] = [
        "jp.jma.shindo": ["0": "震度0", "1": "震度1", "2": "震度2", "3": "震度3", "4": "震度4", "5-": "震度5弱", "5+": "震度5強",
                          "6-": "震度6弱", "6+": "震度6強", "7": "震度7"],
        "jp.jma.warning-level": levels("警戒レベル"),
        "jp.jma.warning-class": ["advisory": "注意報級", "warning": "警報級", "special-warning": "特別警報級"],
        "jp.jma.tsunami": ["forecast": "津波予報", "advisory": "津波注意報", "warning": "津波警報", "major-warning": "大津波警報"],
        "jp.jma.volcanic-alert-level": levels("噴火警戒レベル"),
        "jp.jma.typhoon-intensity": ["none": "強さの階級なし", "strong": "強い", "very-strong": "非常に強い", "violent": "猛烈な"],
        "jp.jma.nankai-trough": ["investigating": "調査中", "caution": "巨大地震注意", "alert": "巨大地震警戒", "concluded": "調査終了"],
        "intl.mmi": Dictionary(uniqueKeysWithValues: ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"].map { ($0, "MMI \($0)") }),
        "intl.saffir-simpson": levels("カテゴリー"),
        "intl.meteoalarm.awareness-level": ["green": "警戒色 緑", "yellow": "警戒色 黄", "orange": "警戒色 橙", "red": "警戒色 赤"],
    ]
    private static func levels(_ prefix: String) -> [String: String] { Dictionary(uniqueKeysWithValues: (1...5).map { ("\($0)", "\(prefix)\($0)") }) }

    public static func severity(_ v: String) -> String { severities[v] ?? "不明" }
    public static func urgency(_ v: String) -> String { urgencies[v] ?? "不明" }
    public static func certainty(_ v: String) -> String { certainties[v] ?? "不明" }
    /// 「避難・近づかない」; nil when no known response.
    public static func responses(_ vs: [String]) -> String? { vs.compactMap { responses[$0] }.joined(separator: "・").nilIfEmpty }
    /// 「訓練」「試験」 for non-actual notices; nil for Actual.
    public static func status(_ v: String) -> String? { v == "Exercise" ? "訓練" : v == "Test" ? "試験" : nil }
    public static func basis(_ v: String?) -> String { v == "observed" ? "（観測）" : v == "forecast" ? "（予測）" : "" }

    /// The badge title: type-specific kind first, else the first known category.
    public static func kind(_ h: Hazard) -> String {
        switch h.kind {
        case let .earthquake(kind, _, _)?: return earthquakeKinds[kind] ?? "地震"
        case .tsunami?: return "津波"
        case let .volcano(phenomenon, _)?: return volcanoPhenomena[phenomenon] ?? "火山"
        case let .weather(ps)?: return ps.compactMap { phenomena[$0] }.joined(separator: "・").nilIfEmpty ?? "気象"
        case .cyclone?: return "台風・熱帯低気圧"
        case nil: return h.category.lazy.compactMap { categories[$0] }.first ?? "災害情報"
        }
    }
    /// Kind label plus 「解除」 for an all-clear.
    public static func title(_ h: Hazard) -> String { kind(h) + (h.allClear ? " 解除" : "") }
    /// 「震度5弱（観測）」; an unknown scheme or value is shown raw, never hidden.
    public static func scale(_ s: HazardScale) -> String { (scales[s.scheme]?[s.value] ?? "\(s.scheme) \(s.value)") + basis(s.basis) }

    /// 「M6.5」「M6.5（Mj）」 from tenths.
    public static func magnitude(_ m: HazardKind.Magnitude) -> String {
        "M" + String(format: "%.1f", Double(m.value) / 10) + (m.type.map { "（\($0)）" } ?? "")
    }
    /// 「深さ約10km」「深さ1km未満」 from millimeters.
    public static func depth(_ mm: Int64) -> String { mm < 1_000_000 ? "深さ1km未満" : "深さ約\((mm + 500_000) / 1_000_000)km" }
    /// 「M6.5・深さ約10km・震源 石川県能登地方」; nil when the earthquake carries none of them.
    public static func earthquakeSummary(_ h: Hazard) -> String? {
        guard case let .earthquake(_, hypo, mag)? = h.kind else { return nil }
        return [mag.map(magnitude), hypo?.depth.map(depth), hypo?.name.map { "震源 \($0.text)" }].compactMap { $0 }.joined(separator: "・").nilIfEmpty
    }
    /// 「最大の高さ 3m（予測）」: the original text when present, else the value in meters.
    public static func tsunamiHeight(_ h: HazardKind.Height) -> String? {
        guard let v = h.text?.text ?? h.value.map(meters) else { return nil }
        return "最大の高さ \(v)" + basis(h.basis)
    }
    static func meters(_ mm: Int64) -> String { mm % 1000 == 0 ? "\(mm / 1000)m" : String(format: "%.1fm", Double(mm) / 1000) }
    public static func pressure(_ pa: Int64) -> String { "中心気圧 \((pa + 50) / 100)hPa" }
    public static func wind(_ mmPerSecond: Int64) -> String { "最大風速 \((mmPerSecond + 500) / 1000)m/s" }
    public static func stormRadius(_ mm: Int64) -> String { "暴風域の半径 約\((mm + 500_000) / 1_000_000)km" }
}

extension String { var nilIfEmpty: String? { isEmpty ? nil : self } }
