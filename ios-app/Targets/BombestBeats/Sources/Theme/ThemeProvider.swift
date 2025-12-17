import SwiftUI

// MARK: - Theme Specification

/// Theme abstraction for Bombest Beats iOS
/// Supports multiple visual styles that can be swapped at runtime
struct BombestThemeSpec {
    let name: String
    let colors: BombestThemeColors
    let progressStyle: ProgressStyle
    let visualizerStyle: VisualizerStyle
    let useTextures: Bool
}

/// Color palette for a theme
struct BombestThemeColors {
    let background: Color
    let surface: Color
    let surfaceActive: Color
    let primary: Color
    let accent: Color
    let textPrimary: Color
    let textSecondary: Color
    let border: Color
    
    // Gradient colors for visualizers
    let gradientColors: [Color]
}

enum ProgressStyle {
    case standard
    case sprayPaint
    case vuMeter
}

enum VisualizerStyle {
    case standard
    case graffiti
    case oscilloscope
}

// MARK: - Theme Definitions

/// Graffiti Theme - Urban spray-paint aesthetic
let GraffitiTheme = BombestThemeSpec(
    name: "Graffiti",
    colors: BombestThemeColors(
        background: Color("DeepNavy"),
        surface: Color(hex: 0x121730),
        surfaceActive: Color(hex: 0x1A2040),
        primary: Color("NeonPink"),
        accent: Color(hex: 0xE8E4DD),
        textPrimary: .white,
        textSecondary: Color(hex: 0x9CA3AF),
        border: Color(hex: 0x2D3250),
        gradientColors: [Color("NeonOrange"), Color("NeonPink"), Color("NeonPurple")]
    ),
    progressStyle: .sprayPaint,
    visualizerStyle: .graffiti,
    useTextures: true
)

/// Studio Dust Theme - Analog recording studio aesthetic
let StudioDustTheme = BombestThemeSpec(
    name: "Studio Dust",
    colors: BombestThemeColors(
        background: Color(hex: 0x1A1A1A),      // Charcoal
        surface: Color(hex: 0x232323),          // Dark steel
        surfaceActive: Color(hex: 0x2E2E2E),    // Warm gray
        primary: Color(hex: 0xD4A574),          // Warm amber
        accent: Color(hex: 0x5A7D7E),           // Muted teal
        textPrimary: Color(hex: 0xE8E4E0),      // Soft white
        textSecondary: Color(hex: 0x8A8680),    // Dusty gray
        border: Color(hex: 0x3A3A3A),
        gradientColors: [Color(hex: 0xD4A574), Color(hex: 0x5A7D7E), Color(hex: 0xC45C5C)]
    ),
    progressStyle: .vuMeter,
    visualizerStyle: .oscilloscope,
    useTextures: true
)

// MARK: - Theme Environment

/// Observable theme manager for SwiftUI
class ThemeManager: ObservableObject {
    @Published var currentTheme: BombestThemeSpec = GraffitiTheme
    
    func setTheme(_ theme: BombestThemeSpec) {
        withAnimation(.easeInOut(duration: 0.3)) {
            currentTheme = theme
        }
    }
    
    func toggleTheme() {
        if currentTheme.name == "Graffiti" {
            setTheme(StudioDustTheme)
        } else {
            setTheme(GraffitiTheme)
        }
    }
}

/// Environment key for theme
private struct ThemeManagerKey: EnvironmentKey {
    static let defaultValue = ThemeManager()
}

extension EnvironmentValues {
    var themeManager: ThemeManager {
        get { self[ThemeManagerKey.self] }
        set { self[ThemeManagerKey.self] = newValue }
    }
}

// MARK: - Color Hex Extension

extension Color {
    init(hex: Int, alpha: Double = 1.0) {
        let red = Double((hex >> 16) & 0xFF) / 255.0
        let green = Double((hex >> 8) & 0xFF) / 255.0
        let blue = Double(hex & 0xFF) / 255.0
        self.init(red: red, green: green, blue: blue, opacity: alpha)
    }
}
