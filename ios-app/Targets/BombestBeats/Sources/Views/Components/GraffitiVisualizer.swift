import SwiftUI

struct GraffitiVisualizer: View {
    let amplitudes: [Float]
    var colors: [Color] = [Color("NeonOrange"), Color("NeonPink"), Color("NeonPurple")]
    
    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { context, size in

                let width = size.width
                let height = size.height
                let centerY = height / 2
                
                // Draw misty background texture
                // ...
                
                let barCount = amplitudes.count
                let barWidth = width / CGFloat(max(barCount, 1))
                
                for (index, amplitude) in amplitudes.enumerated() {
                    let x = CGFloat(index) * barWidth + barWidth / 2
                    // Interpolate color
                    let color = colors[index % colors.count] // Simple cycle for now
                    
                    let intensity = CGFloat(amplitude)
                    let sprayHeight = height * 0.5 * intensity
                    
                    // Draw spray stroke
                    var path = Path()
                    let rect = CGRect(
                        x: x - barWidth/2,
                        y: centerY - sprayHeight/2,
                        width: barWidth,
                        height: sprayHeight
                    )
                    path.addEllipse(in: rect)
                    
                    context.fill(path, with: .color(color.opacity(0.7)))
                    
                    // Add "drips" or "spray particles"
                    if intensity > 0.5 {
                        let particleCount = Int(intensity * 10)
                        for _ in 0..<particleCount {
                            let px = x + CGFloat.random(in: -10...10)
                            let py = centerY + CGFloat.random(in: -sprayHeight...sprayHeight)
                            let pSize = CGFloat.random(in: 1...3)
                            
                            let particlePath = Path(ellipseIn: CGRect(x: px, y: py, width: pSize, height: pSize))
                            context.fill(particlePath, with: .color(color.opacity(0.5)))
                        }
                    }
                }
            }
        }
    }
}
