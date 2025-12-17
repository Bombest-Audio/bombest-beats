import SwiftUI

struct SprayPaintProgress: View {
    @Binding var progress: Float
    var size: CGFloat
    var onEditingChanged: ((Bool) -> Void)? = nil
    
    // Internal state for smooth scrubbing
    @State private var isDragging: Bool = false
    @State private var lastHapticValue: Float = 0
    
    // Calculate angle from center to touch point
    private func angle(geometry: GeometryProxy, value: DragGesture.Value) -> Double {
        let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
        let vector = CGVector(dx: value.location.x - center.x, dy: value.location.y - center.y)
        var radians = atan2(vector.dy, vector.dx)
        
        // Adjust coordinate system to match clock (0 at top) or standard start
        // Our progress starts at top (-90 degrees visual rotation)
        // atan2 returns -pi to pi.
        
        // Normalize to 0...1
        // Need to calibratre this to match the visual representation
        // Visual: -90 deg start. 
        // dragging at top (dy < 0, dx ~0) -> -pi/2
        
        // Let's just use simple atan2 + offset
        if radians < 0 { radians += 2 * .pi }
        
        // If we want 0 at top (-pi/2 in standard trig)
        // Standard trig: 0 at right, pi/2 at bottom, pi at left, 3pi/2 at top
        
        // visual rot -90 means: 0 progress is at 12 o'clock.
        // atan2(0, -1) [top] is -pi/2. + 2pi = 3pi/2 (4.71)
        
        // Let's rotate our mapped angle so 0 is at 12 o'clock
        var angle = radians + (.pi / 2)
        if angle < 0 { angle += 2 * .pi }
        while angle >= 2 * .pi { angle -= 2 * .pi }
        
        return angle / (2 * .pi)
    }
    
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                // Track
                Circle()
                    .stroke(Color.white.opacity(0.1), lineWidth: 10)
                
                // Progress
                Circle()
                    .trim(from: 0.0, to: CGFloat(progress))
                    .stroke(
                        AngularGradient(
                            gradient: Gradient(colors: [Color("NeonOrange"), Color("NeonPink"), Color("NeonPurple")]),
                            center: .center
                        ),
                        // Rough/Spray Style: dashed line
                        style: StrokeStyle(lineWidth: 14, lineCap: .butt, lineJoin: .round, miterLimit: 0, dash: [1, 3], dashPhase: 0)
                    )
                    .blur(radius: 0.5) // Slight blur for spray effect
                    .rotationEffect(Angle(degrees: -90))
                    //.animation(.spring(), value: progress) // Disable animation when dragging for responsiveness
                
                // Knob (The "Spray Can Nozzle")
                Circle()
                    .fill(Color.white)
                    .frame(width: 20, height: 20)
                    .shadow(color: .black.opacity(0.3), radius: 2)
                    .offset(y: -size/2)
                    .rotationEffect(Angle(degrees: Double(progress) * 360))
            }
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !isDragging {
                            isDragging = true
                            onEditingChanged?(true)
                        }
                        let newProgress = Float(self.angle(geometry: geometry, value: value))
                        
                        // Haptic Tick logic (every ~2% change)
                        if abs(newProgress - lastHapticValue) > 0.02 {
                            HapticsManager.shared.playTick()
                            lastHapticValue = newProgress
                        }
                        
                        self.progress = newProgress
                    }
                    .onEnded { _ in
                        isDragging = false
                        onEditingChanged?(false)
                    }
            )
        }
        .frame(width: size, height: size)
        .contentShape(Circle()) // Ensure entire area is draggable, not just the stroke
    }
}
