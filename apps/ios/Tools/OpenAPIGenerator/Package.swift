// swift-tools-version: 6.0
// Pins Apple's swift-openapi-generator for `just generate-api-ios`.
import PackageDescription

let package = Package(
    name: "OpenAPIGeneratorTool",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(url: "https://github.com/apple/swift-openapi-generator", exact: "1.14.2")
    ],
    targets: []
)
