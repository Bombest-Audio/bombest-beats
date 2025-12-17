import SwiftUI

/// VU Meter style circular progress indicator for Studio Dust theme
/// Features analog dial aesthetic with eased motion, leading edge glow, and peak brightness
struct VUMeterProgress: View {
    let progress: Double
    var size: CGFloat = 280
    var strokeWidth: CGFloat = 6
    var baseColor: Color = Color(hex: 0x3A3A3A)
    var primaryColor: Color = Color(hex: 0xD4A574)
    var peakColor: Color = Color(hex: 0xC45C5C)
    var glowColor: Color = Color(hex: 0x5A7D7E)
    
    @State private var animatedProgress: Double = 0
    @State private var glowPhase: Double = 0
    
    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { context, canvasSize in
                let center = CGPoint(x: canvasSize.width / 2, y: canvasSize.height / 2)
                let radius = min(canvasSize.width, canvasSize.height) / 2 - strokeWidth * 3
                
                // Background track
                let trackPath = Path { path in
                    path.addArc(
                        center: center,
                        radius: radius,
                        startAngle: .degrees(-90),
                        endAngle: .degrees(270),
                        clockwise: false
                    )
                }
                context.stroke(
                    trackPath,
                    with: .color(baseColor),
                    lineWidth: strokeWidth * 0.5
                )
                
                // Draw tick marks
                for i in 0...11 {
                    let tickAngle = Angle.degrees(-90 + Double(i) * 30)
                    let innerRadius = radius - strokeWidth * 1.5
                    let outerRadius = radius + strokeWidth * 0.5
                    
                    let startX = center.x + innerRadius * cos(tickAngle.radians)
                    let startY = center.y + innerRadius * sin(tickAngle.radians)
                    let endX = center.x + outerRadius * cos(tickAngle.radians)
                    let endY = center.y + outerRadius * sin(tickAngle.radians)
                    
                    var tickPath = Path()
                    tickPath.move(to: CGPoint(x: startX, y: startY))
                    tickPath.addLine(to: CGPoint(x: endX, y: endY))
                    
                    context.stroke(
                        tickPath,
                        with: .color(baseColor.opacity(0.6)),
                        lineWidth: 2
                    )
                }
                
                guard animatedProgress > 0.001 else { return }
                
                // Calculate sweep angle (counter-clockwise)
                let sweepAngle = -animatedProgress * 360
                
                // Determine color based on progress (VU meter zones)
                let meterColor: Color = {
                    if animatedProgress > 0.85 {
                        return peakColor
                    } else if animatedProgress > 0.7 {
                        let t = (animatedProgress - 0.7) / 0.15
                        return blend(primaryColor, peakColor, t: t)
                    } else {
                        return primaryColor
                    }
                }()
                
                // Glow intensity animation
                let glowIntensity = 0.6 + sin(glowPhase) * 0.4
                
                // Outer glow layer
                let glowPath = Path { path in
                    path.addArc(
                        center: center,
                        radius: radius + strokeWidth,
                        startAngle: .degrees(-90),
                        endAngle: .degrees(-90 + sweepAngle),
                        clockwise: sweepAngle < 0
                    )
                }
                context.stroke(
                    glowPath,
                    with: .color(glowColor.opacity(0.15 * glowIntensity)),
                    style: StrokeStyle(lineWidth: strokeWidth * 2.5, lineCap: .round)
                )
                
                // Main progress arc
                let progressPath = Path { path in
                    path.addArc(
                        center: center,
                        radius: radius,
                        startAngle: .degrees(-90),
                        endAngle: .degrees(-90 + sweepAngle),
                        clockwise: sweepAngle < 0
                    )
                }
                context.stroke(
                    progressPath,
                    with: .color(meterColor),
                    style: StrokeStyle(lineWidth: strokeWidth * 1.5, lineCap: .round)
                )
                
                // Leading edge glow
                let leadingAngle = Angle.degrees(-90 + sweepAngle)
                let leadingX = center.x + radius * cos(leadingAngle.radians)
                let leadingY = center.y + radius * sin(leadingAngle.radians)
                let leadingPoint = CGPoint(x: leadingX, y: leadingY)
                
                // Radial glow at leading edge
                let glowGradient = Gradient(colors: [
                    meterColor.opacity(0.8 * glowIntensity),
                    meterColor.opacity(0.3 * glowIntensity),
                    Color.clear
                ])
                context.fill(
                    Path(ellipseIn: CGRect(
                        x: leadingX - strokeWidth * 3,
                        y: leadingY - strokeWidth * 3,
                        width: strokeWidth * 6,
                        height: strokeWidth * 6
                    )),
                    with: .radialGradient(
                        glowGradient,
                        center: leadingPoint,
                        startRadius: 0,
                        endRadius: strokeWidth * 3
                    )
                )
                
                // Bright dot at leading edge
                context.fill(
                    Path(ellipseIn: CGRect(
                        x: leadingX - strokeWidth * 0.4,
                        y: leadingY - strokeWidth * 0.4,
                        width: strokeWidth * 0.8,
                        height: strokeWidth * 0.8
                    )),
                    with: .color(Color.white.opacity(0.9))
                )
            }
        }
        .frame(width: size, height: size)
        .onAppear {
            // Start glow animation
            withAnimation(.linear(duration: 2).repeatForever(autoreverses: false)) {
                glowPhase = .pi * 2
            }
        }
        .onChange(of: progress) { _, newValue in
            withAnimation(.easeOut(duration: 0.15)) {
                animatedProgress = newValue
            }
        }
    }
    
    /// Blend two colors
    private func blend(_ c1: Color, _ c2: Color, t: Double) -> Color {
        // Simple linear blend (approximation)
        let clampedT = max(0, min(1, t))
        return Color(
            red: lerp(c1.components.red, c2.components.red, t: clampedT),
            green: lerp(c1.components.green, c2.components.green, t: clampedT),
            blue: lerp(c1.components.blue, c2.components.blue, t: clampedT)
        )
    }
    
    private func lerp(_ a: Double, _ b: Double, t: Double) -> Double {
        return a + (b - a) * t
    }
}

// MARK: - Color Components Extension

extension Color {
    var components: (red: Double, green: Double, blue: Double, opacity: Double) {
        // Approximate extraction - works for most SwiftUI colors
        let uiColor = UIColor(self)
        var r: CGFloat = 0
        var g: CGFloat = 0
        var b: CGFloat = 0
        var a: CGFloat = 0
        uiColor.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (Double(r), Double(g), Double(b), Double(a))
    }
}
