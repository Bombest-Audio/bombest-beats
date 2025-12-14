import SwiftUI

struct QueueView: View {
    @ObservedObject var audioController = AudioController.shared
    @Environment(\.presentationMode) var presentationMode
    
    var body: some View {
        NavigationView {
            List {
                ForEach(audioController.originalTracks) { track in
                    HStack {
                        // Playing Indicator
                        if audioController.currentTrack?.id == track.id {
                            Image(systemName: "speaker.wave.3.fill")
                                .foregroundColor(Color(hex: "F470FF"))
                                .font(.caption)
                        } else {
                            Text("\(audioController.originalTracks.firstIndex(where: { $0.id == track.id })! + 1)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .frame(width: 20)
                        }
                        
                        // Track Info
                        VStack(alignment: .leading) {
                            Text(track.displayTitle)
                                .font(.body)
                                .fontWeight(audioController.currentTrack?.id == track.id ? .bold : .regular)
                                .foregroundColor(.primary)
                            Text(track.artist)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        
                        Spacer()
                    }
                }
                .onMove(perform: move)
            }
            .listStyle(InsetGroupedListStyle())
            .navigationTitle("Up Next")
            .navigationBarItems(trailing: Button("Done") {
                presentationMode.wrappedValue.dismiss()
            })
            .environment(\.editMode, .constant(.active)) // Always allow reordering
        }
    }
    
    func move(from source: IndexSet, to destination: Int) {
        audioController.reorder(from: source, to: destination)
    }
}
