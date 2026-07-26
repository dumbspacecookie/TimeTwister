// swift-tools-version:5.9
//
//  Package.swift
//  TimeTwisterCore
//
//  A SwiftPM view of the *same* source directories XcodeGen builds from — the
//  `path:` arguments point at the existing folders, so there is exactly one copy
//  of every file and the two build systems can never drift apart.
//
//  Why this exists: the Xcode project is the only way to build the app targets,
//  and it can only be driven on a Mac. But the pure-logic core has no UIKit in
//  it, so there is no reason its tests should need one. With this manifest:
//
//      swift test
//
//  runs the whole core suite on Windows and on a Linux CI runner. The app,
//  keyboard and share extension still need Xcode; the logic they all depend on
//  no longer does.
//
//  On Windows the toolchain needs the MSVC environment loaded (vcvars64.bat) and
//  SDKROOT pointed at the Swift Windows SDK — see ios/README.md.
//

import PackageDescription

let package = Package(
    name: "TimeTwisterCore",
    products: [
        .library(name: "TimeTwisterCore", targets: ["TimeTwisterCore"]),
    ],
    targets: [
        .target(
            name: "TimeTwisterCore",
            path: "TimeTwisterCore"
        ),
        .testTarget(
            name: "TimeTwisterCoreTests",
            dependencies: ["TimeTwisterCore"],
            path: "TimeTwisterCoreTests"
        ),
    ]
)
