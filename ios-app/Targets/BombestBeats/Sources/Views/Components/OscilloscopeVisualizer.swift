import SwiftUI

/// Oscilloscope-style waveform visualizer for Studio Dust theme
/// Features thin glowing lines with natural imperfections, phosphor glow effect
struct OscilloscopeVisualizer: View {
    let amplitudes: [Float]
    var primaryColor: Color = Color(hex: 0xD4A574)
    var glowColor: Color = Color(hex: 0x5A7D7E)
    var lineThickness: CGFloat = 2
    
    var body: some View {
        TimelineView(.animation(minimumInterval: 1/30)) { timeline in
            Canvas { context, size in
                let width = size.width
                let height = size.height
                let centerY = height / 2
                
                guard !amplitudes.isEmpty else { return }
                
                let pointCount = amplitudes.count
                let stepX = width / CGFloat(pointCount - 1).clamped(to: 1...CGFloat.greatestFiniteMagnitude)
                
                // Calculate energy for dynamic effects
                let energy = CGFloat(amplitudes.reduce(0, +) / Float(amplitudes.count))
                
                // Build points with natural jitter
                var points: [CGPoint] = []
                let time = Date().timeIntervalSince1970
                
                for (i, amp) in amplitudes.enumerated() {
                    let x = CGFloat(i) * stepX
                    let clampedAmp = CGFloat(min(max(amp, 0), 1))
                    
                    // Add natural jitter
                    let jitter = CGFloat.random(in: -2...2)
                    let y = centerY - (clampedAmp * height * 0.4) + jitter
                    
                    points.append(CGPoint(x: x, y: y))
                }
                
                guard points.count >= 2 else { return }
                
                // Draw glow layers
                for glowRadius in [8.0, 5.0, 3.0] as [CGFloat] {
                    let glowPath = createSmoothPath(points: points)
                    let glowAlpha = (0.1 / (glowRadius / 3)) * (0.5 + energy * 0.5)
                    
                    context.stroke(
                        glowPath,
                        with: .color(glowColor.opacity(Double(glowAlpha.clamped(to: 0...0.3)))),
                        style: StrokeStyle(
                            lineWidth: glowRadius + lineThickness,
                            lineCap: .round,
                            lineJoin: .round
                        )
                    )
                }
                
                // Main waveform line
                let mainPath = createSmoothPath(points: points)
                let dynamicThickness = lineThickness * (0.8 + energy * 0.4)
                
                context.stroke(
                    mainPath,
                    with: .color(primaryColor.opacity(0.9)),
                    style: StrokeStyle(
                        lineWidth: dynamicThickness,
                        lineCap: .round,
                        lineJoin: .round
                    )
                )
                
                // Phosphor bright center
                context.stroke(
                    mainPath,
                    with: .color(Color.white.opacity(Double(0.4 + energy * 0.3))),
                    style: StrokeStyle(
                        lineWidth: dynamicThickness * 0.3,
                        lineCap: .round,
                        lineJoin: .round
                    )
                )
                
                // Draw scan lines for CRT effect
                drawScanLines(context: context, width: width, height: height, opacity: 0.03)
                
                // Draw grain overlay
                drawGrain(context: context, width: width, height: height, opacity: 0.02)
            }
        }
    }
    
    /// Create smooth bezier path through points
    private func createSmoothPath(points: [CGPoint]) -> Path {
        var path = Path()
        path.move(to: points[0])
        
        for i in 1..<points.count {
            let prev = points[i - 1]
            let curr = points[i]
            let controlX = (prev.x + curr.x) / 2
            let controlY = (prev.y + curr.y) / 2
            
            path.addQuadCurve(to: CGPoint(x: controlX, y: controlY), control: prev)
        }
        
        if let last = points.last {
            path.addLine(to: last)
        }
        
        return path
    }
    
    /// Draw horizontal scan lines
    private func drawScanLines(context: GraphicsContext, width: CGFloat, height: CGFloat, opacity: Double) {
        let spacing: CGFloat = 4
        var y: CGFloat = 0
        
        while y < height {
            var path = Path()
            path.move(to: CGPoint(x: 0, y: y))
            path.addLine(to: CGPoint(x: width, y: y))
            
            context.stroke(
                path,
                with: .color(Color.black.opacity(opacity)),
                lineWidth: 1
            )
            y += spacing
        }
    }
    
    /// Draw subtle grain overlay
    private func drawGrain(context: GraphicsContext, width: CGFloat, height: CGFloat, opacity: Double) {
        for _ in 0..<30 {
            let x = CGFloat.random(in: 0...width)
            let y = CGFloat.random(in: 0...height)
            let size = CGFloat.random(in: 0.5...2)
            
            context.fill(
                Path(ellipseIn: CGRect(x: x, y: y, width: size, height: size)),
                with: .color(Color.white.opacity(opacity * Double.random(in: 0...1)))
            )
        }
    }
}

// MARK: - Comparable Extension for Clamping

extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        return min(max(self, range.lowerBound), range.upperBound)
    }
}
