import ProjectDescription

let project = Project(
    name: "BombestBeats",
    targets: [
        .target(
            name: "BombestBeats",
            destinations: .iOS,
            product: .app,
            bundleId: "best.bom.beats",
            deploymentTargets: .iOS("17.0"),
            infoPlist: .extendingDefault(
                with: [
                    "UILaunchScreen": [
                        "UIColorName": "",
                        "UIImageName": ""
                    ],
                    "NSAppTransportSecurity": [
                        "NSAllowsArbitraryLoads": true
                    ],
                    "UIBackgroundModes": [
                        "audio",
                        "fetch",
                        "processing"
                    ],
                    "NSFaceIDUsageDescription": "Use FaceID to sign in with your Passkey.",
                    "NSLocalNetworkUsageDescription": "Bombest Beats needs to access your local server to play music."
                ]
            ),
            sources: ["Targets/BombestBeats/Sources/**"],
            resources: ["Targets/BombestBeats/Resources/**"],
            entitlements: "BombestBeats.entitlements",
            dependencies: [],
            settings: .settings(
                base: [
                    "DEVELOPMENT_TEAM": "8C4A8V568P",
                    "CODE_SIGN_STYLE": "Automatic"
                ]
            )
        )
    ]
)
