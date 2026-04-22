import SwiftUI

struct GraffitiVisualizer: View {
    let amplitudes: [Float]
    var isPlaying: Bool = true
    var colors: [Color] = [Color("NeonOrange"), Color("NeonPink"), Color("NeonPurple")]

    // Smooth animated bars driven by TimelineView
    @State private var animatedAmplitudes: [Float] = Array(repeating: 0, count: 30)
    @State private var phase: Double = 0

    private let barCount = 30

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
            Canvas { context, size in
                let width = size.width
                let height = size.height
                let barWidth = width / CGFloat(barCount)
                let t = timeline.date.timeIntervalSinceReferenceDate

                for i in 0..<barCount {
                    let x = CGFloat(i) * barWidth + barWidth / 2

                    // If we have real data, use it; otherwise animate smoothly
                    let realAmp = i < amplitudes.count ? amplitudes[i] : 0
                    let hasRealData = amplitudes.contains { $0 > 0.01 }

                    let amp: CGFloat
                    if hasRealData {
                        amp = CGFloat(realAmp)
                    } else if isPlaying {
                        // Smooth pseudo-random animation when playing but no FFT data
                        let freq1 = 0.8 + Double(i) * 0.15
                        let freq2 = 1.3 + Double(i) * 0.07
                        let wave = sin(t * freq1 + Double(i) * 0.4) * 0.4
                              + sin(t * freq2 + Double(i) * 0.9) * 0.3
                        // Shape: higher in mids
                        let midBoost = 1.0 - abs(Double(i) - Double(barCount) * 0.45) / Double(barCount) * 0.8
                        amp = CGFloat(max(0, (wave + 0.5) * midBoost * 0.85))
                    } else {
                        amp = 0
                    }

                    let barHeight = max(2, height * 0.9 * amp)
                    let color = colors[i % colors.count]

                    // Main bar — ellipse for spray-paint look
                    let rect = CGRect(
                        x: x - barWidth * 0.4,
                        y: height / 2 - barHeight / 2,
                        width: barWidth * 0.8,
                        height: barHeight
                    )
                    context.fill(Path(ellipseIn: rect), with: .color(color.opacity(0.75)))

                    // Spray particles on taller bars
                    if amp > 0.45 {
                        let particleCount = Int(amp * 8)
                        for _ in 0..<particleCount {
                            let px = x + CGFloat.random(in: -barWidth...barWidth)
                            let py = height / 2 + CGFloat.random(in: -barHeight * 0.6...barHeight * 0.6)
                            let pSize = CGFloat.random(in: 1...2.5)
                            context.fill(
                                Path(ellipseIn: CGRect(x: px, y: py, width: pSize, height: pSize)),
                                with: .color(color.opacity(0.4))
                            )
                        }
                    }
                }
            }
        }
    }
}
