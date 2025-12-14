import SwiftUI

struct ArtworkWithScrubber: View {
    let artworkUrl: URL?
    let duration: Double
    let currentTime: Double
    let onSeek: (Double) -> Void
    
    @State private var isDragging = false
    @State private var dragProgress: Double = 0.0
    
    // Geometry Constants
    private let trackWidth: CGFloat = 8
    private let progressWidth: CGFloat = 8
    private let scrubberSize: CGFloat = 22
    
    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
            let radius = (size / 2) - 20 // Padding
            
            ZStack {
                // Artwork (Circular Mask)
                AsyncImage(url: artworkUrl) { image in
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                } placeholder: {
                    Color(hex: "18192C")
                }
                .frame(width: size - 80, height: size - 80)
                .clipShape(Circle())
                .shadow(color: .black.opacity(0.5), radius: 20, x: 0, y: 10)
                
                // Track Background (Dark Grey)
                // Start 140 deg, Sweep 260 deg (Total 360-100 gap)
                // 140 degrees = 2.443 radians
                // We want a gap at the bottom.
                // 0 degrees is right (3 o'clock).
                // 90 degrees is bottom (6 o'clock).
                // Let's use standard SwiftUI styling: .rotationEffect
                
                Circle()
                    .trim(from: 0.0, to: 0.75) // 270 degrees arc
                    .stroke(Color(hex: "3F3F57"), style: StrokeStyle(lineWidth: trackWidth, lineCap: .round))
                    .rotationEffect(.degrees(135)) // Rotate to put gap at bottom
                    .frame(width: size, height: size)
                
                // Active Progress Gradient
                Circle()
                    .trim(from: 0.0, to: CGFloat(isDragging ? dragProgress : (currentTime / (duration > 0 ? duration : 1))) * 0.75)
                    .stroke(
                        AngularGradient(
                            gradient: Gradient(colors: [Color(hex: "FFB86C"), Color(hex: "FF6B81"), Color(hex: "C34CFF"), Color(hex: "4F7BFF")]),
                            center: .center,
                            startAngle: .degrees(135),
                            endAngle: .degrees(135 + 270)
                        ),
                        style: StrokeStyle(lineWidth: progressWidth, lineCap: .round)
                    )
                    .rotationEffect(.degrees(135))
                    .frame(width: size, height: size)
                
                // Knob
                // Calculate position based on progress
                let currentProg = isDragging ? dragProgress : (currentTime / (duration > 0 ? duration : 1))
                let angleDeg = 135 + (currentProg * 270)
                let angleRad = angleDeg * .pi / 180
                
                let knobX = center.x + radius * 1.07 * cos(CGFloat(angleRad)) // 1.07 to push slightly outside? Match layout logic.
                let knobY = center.y + radius * 1.07 * sin(CGFloat(angleRad))
                
                // Actually simply use rotation on a container
                Circle()
                    .fill(Color.white)
                    .frame(width: scrubberSize, height: scrubberSize)
                    .shadow(radius: 4)
                    .offset(x: size/2) // Push to edge
                    .rotationEffect(.degrees(Double(angleDeg))) // Rotate container
            }
            .contentShape(Circle()) // Ensure the whole area is hittable
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        isDragging = true
                        let vector = CGVector(dx: value.location.x - center.x, dy: value.location.y - center.y)
                        var angle = atan2(vector.dy, vector.dx) * 180 / .pi
                        if angle < 0 { angle += 360 }
                        
                        // Map angle to progress
                        // Start is 135. End is 135+270 = 405 (or 45).
                        // We need to normalize this.
                        // Let's simplify: Shift angle by -135.
                        var relAngle = angle - 135
                        if relAngle < 0 { relAngle += 360 }
                        
                        // Now range is 0 to 270 is valid. 270 to 360 is gap.
                        var newProgress = relAngle / 270
                        
                        // Snap logic for gap
                        // 270 (End) to 360 (Start) is the gap (90 degrees wide).
                        // If user drags into the gap, keep them at nearest end.
                        if relAngle > 270 {
                            if relAngle > 315 { newProgress = 0 } // Closer to start (360/0)
                            else { newProgress = 1 } // Closer to end (270)
                        }
                        
                        dragProgress = min(max(newProgress, 0), 1)
                    }
                    .onEnded { _ in
                        isDragging = false
                        onSeek(dragProgress * duration)
                    }
            )
            .frame(width: geometry.size.width, height: geometry.size.height)
            .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
        }
    }
}
