import SwiftUI

struct CachedImage: View {
    let url: URL?
    let placeholder: String
    
    @State private var image: UIImage?
    @State private var isLoading = false
    
    var body: some View {
        Group {
            if let image = image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                Image("DefaultAlbumArt")
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            }
        }
        .onAppear {
            loadImage()
        }
        .onChange(of: url) { oldValue, newValue in
            loadImage()
        }
    }
    
    private func loadImage() {
        guard let url = url else { return }
        if isLoading { return }
        
        isLoading = true
        
        Task {
            if let loadedImage = await ImageCacheService.shared.image(for: url) {
                await MainActor.run {
                    withAnimation {
                        self.image = loadedImage
                        self.isLoading = false
                    }
                }
            } else {
                await MainActor.run {
                    self.isLoading = false
                }
            }
        }
    }
}
