import SwiftUI

struct LibraryCard: View {
    let title: String
    let icon: String
    let color: Color
    
    var body: some View {
        VStack(alignment: .leading) {
            Image(systemName: icon)
                .font(.title)
                .foregroundColor(.white)
                .padding(.bottom, 8)
            
            Text(title)
                .font(.headline)
                .foregroundColor(.white)
        }
        .frame(width: 140, height: 100)
        .background(color.opacity(0.8))
        .cornerRadius(12)
        .shadow(radius: 4)
    }
}
