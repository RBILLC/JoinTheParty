// GENERATED from design/tokens.json — do not edit. Run tools/design-tokens/generate.py.
// System: Billet v1

import SwiftUI

enum DT {
    // MARK: Color
    enum Colors {
        static let void = Color(dtHex: 0x131110)
        static let billet = Color(dtHex: 0x1D1A17)
        static let recess = Color(dtHex: 0x0C0B0A)
        static let hairline = Color(dtHex: 0x332F2A)
        static let ink = Color(dtHex: 0xEDE7DE)
        static let ink2 = Color(dtHex: 0xA79E93)
        static let ink3 = Color(dtHex: 0x6B645B)
        static let graphite = Color(dtHex: 0x4A463F)
        static let bronze = Color(dtHex: 0x8A6F4B)
        static let brass = Color(dtHex: 0xC79A63)
        static let brassBright = Color(dtHex: 0xEAD3A6)
        static let oxide = Color(dtHex: 0xB4574E)
        static let spotify = Color(dtHex: 0x1DB954)
    }

    // MARK: Type
    static let fontFamily = "Instrument Sans"
    struct TextToken {
        let size: CGFloat
        let weight: Font.Weight
        let trackingPct: CGFloat
        let lineHeight: CGFloat
        let tabular: Bool
        let uppercase: Bool
    }
    enum Type {
        static let heroMs = TextToken(size: 76, weight: .light, trackingPct: -2, lineHeight: 1.2, tabular: true, uppercase: false)
        static let heroUnit = TextToken(size: 17, weight: .medium, trackingPct: 0, lineHeight: 1.2, tabular: false, uppercase: false)
        static let title = TextToken(size: 28, weight: .medium, trackingPct: -1, lineHeight: 1.2, tabular: false, uppercase: false)
        static let subtitle = TextToken(size: 16, weight: .regular, trackingPct: 0, lineHeight: 1.2, tabular: false, uppercase: false)
        static let body = TextToken(size: 15, weight: .regular, trackingPct: 0, lineHeight: 1.5, tabular: false, uppercase: false)
        static let label = TextToken(size: 13, weight: .medium, trackingPct: 0, lineHeight: 1.2, tabular: false, uppercase: false)
        static let engraved = TextToken(size: 10, weight: .semibold, trackingPct: 14, lineHeight: 1.2, tabular: false, uppercase: true)
        static let fine = TextToken(size: 11, weight: .regular, trackingPct: 0, lineHeight: 1.2, tabular: false, uppercase: false)
    }

    // MARK: Space & shape
    enum Space {
        static let grid: CGFloat = 8
        static let gutter: CGFloat = 24
        static let sectionGap: CGFloat = 40
    }
    enum Shape {
        static let radiusCard: CGFloat = 24
    }

    // MARK: Motion
    enum Motion {
        static let settleOmega: Double = 14
        static let heavyMass: Double = 1.4
        static let heatDurationMs: Double = 900
        static let reducedMotionCrossfadeMs: Double = 200
    }

    // MARK: Haptics
    struct HapticToken { let intensity: Double; let sharpness: Double }
    enum Haptics {
        static let wheelDetent = HapticToken(intensity: 0.4, sharpness: 0.9)
        static let wheelCoarse = HapticToken(intensity: 0.7, sharpness: 0.7)
        static let lockThunk = HapticToken(intensity: 1.0, sharpness: 0.15)
        static let lockLost = HapticToken(intensity: 0.35, sharpness: 0.4)
        static let abClick = HapticToken(intensity: 0.6, sharpness: 1.0)
        static let endStop = HapticToken(intensity: 0.8, sharpness: 0.6)
    }

    // MARK: Controls
    enum Wheel {
        static let detentMs: Double = 5
        static let detentTravelPt: Double = 9
        static let coarseStepMs: Double = 50
        static let rangeMs: Double = 1500
        static let commitDebounceMs: Double = 400
    }
    enum Meter {
        static let maxHz: Double = 15
        static let deadbandMs: Double = 25
        static let logMapRefMs: Double = 250
    }
}

extension Color {
    init(dtHex: UInt32) {
        self.init(
            .sRGB,
            red: Double((dtHex >> 16) & 0xFF) / 255.0,
            green: Double((dtHex >> 8) & 0xFF) / 255.0,
            blue: Double(dtHex & 0xFF) / 255.0,
            opacity: 1.0
        )
    }
}
