import SwiftUI

/// Figma "Design Tokens" (7:206). Dark only: the design has no light mode.
enum Mosimo {
    static let bgBase = Color(hex: 0x0B0D10)
    static let bgSurface = Color(hex: 0x15181D)
    static let bgSurface2 = Color(hex: 0x1E2229)
    static let bgElevated = Color(hex: 0x262B33)
    static let border = Color(hex: 0x2A2F36)
    static let borderStrong = Color(hex: 0x3A4049)
    static let textPrimary = Color(hex: 0xF2F4F7)
    static let textSecondary = Color(hex: 0x9AA3AD)
    static let textTertiary = Color(hex: 0x6B7480)
    static let onPrimary = Color(hex: 0x1A1200)
    /// Primary CTA, selected chip, inundation only; never on persistent chrome such as headers.
    static let primary = Color(hex: 0xFFB900)
    static let danger = Color(hex: 0xFF3B30)
    static let warning = Color(hex: 0xFF8A00)
    /// Safe, shelters, verified signatures.
    static let safe = Color(hex: 0x34C759)
    /// Communication: mesh, BLE, own position, relayed.
    static let info = Color(hex: 0x4DA3FF)
}

/// Android typography (Noto Sans JP sizes; the iOS system font covers Japanese).
extension Font {
    static let titleLarge = Font.system(size: 22, weight: .bold)
    static let titleMedium = Font.system(size: 15, weight: .bold)
    static let bodyLarge = Font.system(size: 16)
    static let bodyMedium = Font.system(size: 13)
    static let bodySmall = Font.system(size: 12)
    static let labelLarge = Font.system(size: 15, weight: .bold)
    static let labelMedium = Font.system(size: 12, weight: .medium)
    static let labelSmall = Font.system(size: 11, weight: .medium)
    /// JetBrains Mono Bold in Figma: counts, times, heights.
    static func number(_ size: CGFloat = 18) -> Font { .system(size: size, weight: .bold, design: .monospaced) }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double(hex >> 16 & 0xff) / 255, green: Double(hex >> 8 & 0xff) / 255, blue: Double(hex & 0xff) / 255)
    }
}
