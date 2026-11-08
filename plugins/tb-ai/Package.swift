// swift-tools-version: 5.9
import PackageDescription

// 툴박스 기기 AI 엔진. ONNX Runtime 1.30 (DeformConv 등 새 누끼 모델에 필요한 연산 포함)
// ORT_CHECKSUM 은 빌드(GitHub Actions)에서 실제 파일을 받아 계산해 채워요.
let package = Package(
    name: "TbAi",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "TbAi", targets: ["TbAIPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .binaryTarget(name: "onnxruntime",
                      url: "https://download.onnxruntime.ai/pod-archive-onnxruntime-c-1.30.0.zip",
                      checksum: "ORT_CHECKSUM"),
        .target(name: "OnnxRuntimeBindings",
                dependencies: ["onnxruntime"],
                path: "ios/objectivec",
                exclude: ["ReadMe.md", "format_objc.sh",
                          "ort_checkpoint.mm", "ort_checkpoint_internal.h",
                          "ort_training_session_internal.h", "ort_training_session.mm",
                          "include/ort_checkpoint.h", "include/ort_training_session.h", "include/onnxruntime_training.h"],
                cxxSettings: [.define("SPM_BUILD")]),
        .target(name: "TbAIPlugin",
                dependencies: [
                    .product(name: "Capacitor", package: "capacitor-swift-pm"),
                    .product(name: "Cordova", package: "capacitor-swift-pm"),
                    "OnnxRuntimeBindings"
                ],
                path: "ios/Sources/TbAIPlugin")
    ],
    cxxLanguageStandard: .cxx17
)
