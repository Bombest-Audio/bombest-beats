import SwiftUI
import Charts

struct DashboardView: View {
    @StateObject private var viewModel = DashboardViewModel()
    
    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                if viewModel.isLoading {
                    ProgressView()
                        .tint(Color("NeonPurple"))
                        .scaleEffect(1.2)
                        .padding(.top, 50)
                } else if let stats = viewModel.stats {
                    // 1. Stats Cards
                    HStack(spacing: 16) {
                        StatCard(title: "Total Plays", value: "\(stats.total_plays)")
                        StatCard(title: "Top Tracks", value: "\(stats.top_tracks.count)")
                    }
                    
                    // 2. Daily Plays Chart (Simple Bar)
                    if !stats.daily_plays.isEmpty {
                        VStack(alignment: .leading) {
                            Text("Activity")
                                .font(.headline)
                                .foregroundColor(.gray)
                            
                            // Simple visual representation since access to Swift Charts might depend on iOS 16+ (we are 17+ so fine).
                            // But for simplicity/speed just a custom view or Chart if available.
                            // Assuming Chart is available.
                            if #available(iOS 16.0, *) {
                                Chart(stats.daily_plays, id: \.date) { item in
                                    BarMark(
                                        x: .value("Date", item.date),
                                        y: .value("Plays", item.count)
                                    )
                                    .foregroundStyle(Color("NeonPink"))
                                }
                                .frame(height: 150)
                            }
                        }
                        .padding()
                        .background(Color.white.opacity(0.05))
                        .cornerRadius(12)
                    }
                    
                    // 3. Top Tracks
                    VStack(alignment: .leading) {
                        Text("Top Tracks")
                            .font(.title2)
                            .fontWeight(.bold)
                        
                        ForEach(stats.top_tracks) { track in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(track.title ?? "Unknown")
                                        .font(.headline)
                                        .foregroundColor(.white)
                                    Text(track.artist ?? "Unknown Artist")
                                        .font(.caption)
                                        .foregroundColor(.gray)
                                }
                                Spacer()
                                Text("\(track.plays) plays")
                                    .font(.subheadline)
                                    .fontWeight(.bold)
                                    .foregroundColor(Color("NeonPurple"))
                            }
                            .padding()
                            .background(Color.white.opacity(0.05))
                            .cornerRadius(12)
                        }
                    }
                } else if let error = viewModel.error {
                    Text("Error: \(error)")
                        .foregroundColor(.red)
                        .padding()
                }
            }
            .padding()
        }
        .background(Color("DeepNavy").ignoresSafeArea())
        .navigationTitle("Dashboard")
        .task {
            await viewModel.fetchDashboard()
        }
    }
}

struct StatCard: View {
    let title: String
    let value: String
    
    var body: some View {
        VStack {
            Text(value)
                .font(.system(size: 32, weight: .bold))
                .foregroundColor(Color("NeonPurple"))
            Text(title)
                .font(.caption)
                .foregroundColor(.gray)
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(Color.white.opacity(0.05))
        .cornerRadius(12)
    }
}
