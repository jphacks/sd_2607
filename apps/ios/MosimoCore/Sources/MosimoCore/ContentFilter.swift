import Foundation

/// Objectionable-word filter (App Review 1.2): the app blocks such posts on send and hides received ones.
/// The list is the app's blocked-words.b64: base64 of a JSON string array, so the public repo holds no plaintext words.
/// Edit with `base64 -D -i blocked-words.b64 > w.json`, then `base64 -i w.json -o blocked-words.b64` (keep w.json out of git).
/// Bundle.module is unusable here because Bpv7's Bundle shadows Foundation's, so the app passes the file in.
/// ponytail: substring match after NFKC/lowercase/hiragana→katakana; spaced-out words slip through, add a moderation list if abuse shows up.
public struct ContentFilter {
    let words: [String]

    public init(base64: String?) {
        let json = base64.flatMap { Data(base64Encoded: $0, options: .ignoreUnknownCharacters) }
        let list = json.flatMap { try? JSONDecoder().decode([String].self, from: $0) } ?? []
        words = list.map(Self.normalize)
    }

    public func blocks(_ text: String) -> Bool {
        let t = Self.normalize(text)
        return words.contains { t.contains($0) }
    }

    static func normalize(_ text: String) -> String {
        let s = text.precomposedStringWithCompatibilityMapping.lowercased()
        return s.applyingTransform(.hiraganaToKatakana, reverse: false) ?? s
    }
}
