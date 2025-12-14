import ProjectDescription

let project = Project(
    name: "BombestBeats",
    targets: [
        .target(
            name: "BombestBeats",
            destinations: .iOS,
            product: .app,
            bundleId: "com.bombest.BombestBeats",
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
                    ]
                ]
            ),
            sources: ["Targets/BombestBeats/Sources/**"],
            resources: ["Targets/BombestBeats/Resources/**"],
            dependencies: []
        )
    ]
)
