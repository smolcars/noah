// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "SatoraFFI",
    platforms: [
        .iOS("18.0"),
    ],
    products: [
        .library(
            name: "SatoraFFI",
            targets: ["SatoraFFI"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "SatoraFFIFFIBinary",
            path: "SatoraFFI.xcframework"
        ),
        .target(
            name: "SatoraFFIFFI",
            dependencies: ["SatoraFFIFFIBinary"],
            path: "FFI",
            publicHeadersPath: "include"
        ),
        .target(
            name: "SatoraFFI",
            dependencies: ["SatoraFFIFFI"],
            path: "Sources"
        ),
    ]
)