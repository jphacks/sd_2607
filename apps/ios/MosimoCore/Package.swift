// swift-tools-version: 6.1
import PackageDescription

let package = Package(
    name: "MosimoCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "MosimoCore", targets: ["MosimoCore"])],
    dependencies: [.package(url: "https://github.com/21-DOT-DEV/swift-secp256k1", exact: "0.24.0")],
    targets: [
        .target(name: "MosimoCore", dependencies: [.product(name: "libsecp256k1", package: "swift-secp256k1")]),
        .testTarget(name: "MosimoCoreTests", dependencies: ["MosimoCore"]),
    ],
    swiftLanguageModes: [.v5]
)
