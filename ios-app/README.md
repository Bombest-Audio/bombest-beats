# Bombest Beats iOS App

This folder contains the source code for the iOS version of Bombest Beats, built with SwiftUI.

## Setup Instructions

This project is generated using **Tuist**.

1.  **Open the Project**:
    ```bash
    open BombestBeats.xcworkspace
    ```
2.  **Build & Run**:
    *   Select the `BombestBeats` scheme.
    *   Choose a simulator or device.
    *   Press `Cmd + R`.

## Project Maintenance
If you add new files or change dependencies:
1.  Run `tuist edit` to modify the manifest (`Project.swift`).
2.  Run `tuist generate` to regenerate the Xcode project.


## Structure
*   `BombestBeatsApp.swift`: Main entry point.
*   `ContentView.swift`: Library list view.
*   `Views/PlayerView.swift`: The "Kickass" player UI port.
*   `Services/AudioController.swift`: Manages AVPlayer playback.
*   `Services/MusicService.swift`: Fetches tracks from the backend.
