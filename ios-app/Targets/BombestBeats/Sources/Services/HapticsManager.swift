import CoreHaptics
import UIKit

enum FrequencyBand {
    case low    // kick/bass — strong dull thud
    case mid    // snare — sharp crack
    case high   // hi-hat — light tick
}

class HapticsManager {
    static let shared = HapticsManager()
    
    private var engine: CHHapticEngine?
    
    private init() {
        prepareHaptics()
    }
    
    func prepareHaptics() {
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics else { return }
        
        do {
            engine = try CHHapticEngine()
            try engine?.start()
            
            // Restart engine if stopped
            engine?.stoppedHandler = { reason in
                print("Haptic Engine Stopped: \(reason)")
                do {
                    try self.engine?.start()
                } catch {
                    print("Failed to restart haptic engine: \(error)")
                }
            }
            
            engine?.resetHandler = {
                print("Haptic Engine Reset")
                do {
                    try self.engine?.start()
                } catch {
                    print("Failed to restart haptic engine: \(error)")
                }
            }
        } catch {
            print("Haptics Error: \(error.localizedDescription)")
        }
    }
    
    func playTick() {
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics else {
            // Fallback for older devices
            let generator = UIImpactFeedbackGenerator(style: .light)
            generator.impactOccurred()
            return
        }
        
        var events = [CHHapticEvent]()
        
        // Crisp, sharp tick
        let intensity = CHHapticEventParameter(parameterID: .hapticIntensity, value: 0.7)
        let sharpness = CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.9)
        let event = CHHapticEvent(eventType: .hapticTransient, parameters: [intensity, sharpness], relativeTime: 0)
        events.append(event)
        
        do {
            let pattern = try CHHapticPattern(events: events, parameters: [])
            let player = try engine?.makePlayer(with: pattern)
            try player?.start(atTime: 0)
        } catch {
            print("Failed to play tick: \(error)")
        }
    }
    
    func playImpact() {
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics else {
            let generator = UIImpactFeedbackGenerator(style: .medium)
            generator.impactOccurred()
            return
        }
        
        var events = [CHHapticEvent]()
        
        // Heavy thud
        let intensity = CHHapticEventParameter(parameterID: .hapticIntensity, value: 1.0)
        let sharpness = CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.4) // Dull
        let event = CHHapticEvent(eventType: .hapticTransient, parameters: [intensity, sharpness], relativeTime: 0)
        events.append(event)
        
        do {
            let pattern = try CHHapticPattern(events: events, parameters: [])
            let player = try engine?.makePlayer(with: pattern)
            try player?.start(atTime: 0)
        } catch {
            print("Failed to play impact: \(error)")
        }
    }
    
    func playSuccess() {
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
    }

    /// Plays a frequency-band-specific haptic pulse for the Groove Engine.
    /// Called from AudioService when frequencyBands updates.
    /// - Parameters:
    ///   - band: The frequency band that triggered this groove pulse
    ///   - intensity: Normalized amplitude from the FFT (0.0–1.0)
    func playGroove(band: FrequencyBand, intensity: Float) {
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics else { return }
        guard intensity > 0.15 else { return } // gate: skip sub-threshold pulses

        // Per D-07: band-specific intensity/sharpness pairs
        let (hapticIntensity, sharpness): (Float, Float)
        switch band {
        case .low:  hapticIntensity = 1.0; sharpness = 0.3   // kick — dull thud
        case .mid:  hapticIntensity = 0.8; sharpness = 0.9   // snare — sharp crack
        case .high: hapticIntensity = 0.4; sharpness = 0.95  // hi-hat — light tick
        }

        let scaledIntensity = min(hapticIntensity * intensity, 1.0)
        let i = CHHapticEventParameter(parameterID: .hapticIntensity, value: scaledIntensity)
        let s = CHHapticEventParameter(parameterID: .hapticSharpness, value: sharpness)
        let event = CHHapticEvent(eventType: .hapticTransient, parameters: [i, s], relativeTime: 0)

        do {
            let pattern = try CHHapticPattern(events: [event], parameters: [])
            try engine?.makePlayer(with: pattern).start(atTime: 0)
        } catch {
            print("[Haptics] Groove playback error: \(error)")
        }
    }
}
