import Foundation
import UIKit
import Capacitor
import OnnxRuntimeBindings

/// 툴박스 기기 AI 엔진 (iOS).
/// 웹 화면(index.html)이 만든 입력 텐서를 받아 ONNX Runtime으로 돌리고 결과를 돌려줘요.
/// 가능한 부분은 CoreML(뉴럴 엔진·GPU)로, 나머지는 CPU 멀티코어로 돌려요.
/// 모델 파일은 앱 안 public/models 에 웹과 같은 형식(8비트 압축 조각)으로 들어 있고,
/// 처음 한 번 원래 모델로 풀어서 캐시 폴더에 저장해 둬요.
@objc(TbAIPlugin)
public class TbAIPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "TbAIPlugin"
    public let jsName = "TbAI"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "load", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "run", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "info", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "save", returnType: CAPPluginReturnPromise)
    ]

    private var env: ORTEnv?
    private var sessions: [String: ORTSession] = [:]
    private var accels: [String: String] = [:]
    private let queue = DispatchQueue(label: "kr.toolboxapp.tbai", qos: .userInitiated)

    // MARK: - JS에서 부르는 함수

    @objc func info(_ call: CAPPluginCall) {
        call.resolve([
            "platform": "ios",
            "memory": Double(ProcessInfo.processInfo.physicalMemory),
            "cores": ProcessInfo.processInfo.activeProcessorCount,
            "ort": "1.30.0"
        ])
    }

    /// 결과 저장 (앱 안에서는 웹의 '다운로드'가 안 돼서 공유 창으로 — '이미지 저장'·'파일에 저장'을 고르면 돼요)
    @objc func save(_ call: CAPPluginCall) {
        guard let b64 = call.getString("data"), let data = Data(base64Encoded: b64) else { call.reject("no data"); return }
        let raw = call.getString("name") ?? "툴박스"
        let name = raw.replacingOccurrences(of: "/", with: "_")
        let mime = call.getString("mime") ?? ""
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let url = dir.appendingPathComponent(name)
            try? FileManager.default.removeItem(at: url)
            try data.write(to: url)
            DispatchQueue.main.async {
                guard let vc = self.bridge?.viewController else { call.reject("no view"); return }
                // 사진은 '이미지 저장'이 바로 보이게 UIImage 로, 나머지는 파일로
                var items: [Any] = [url]
                if mime.hasPrefix("image/"), let img = UIImage(data: data) { items = [img] }
                let av = UIActivityViewController(activityItems: items, applicationActivities: nil)
                if let pop = av.popoverPresentationController {
                    pop.sourceView = vc.view
                    pop.sourceRect = CGRect(x: vc.view.bounds.midX, y: vc.view.bounds.maxY - 40, width: 1, height: 1)
                    pop.permittedArrowDirections = []
                }
                av.completionWithItemsHandler = { _, completed, _, _ in
                    call.resolve(["shared": true, "completed": completed])
                }
                vc.present(av, animated: true)
            }
        } catch {
            call.reject("save failed: \(error.localizedDescription)")
        }
    }

    @objc func load(_ call: CAPPluginCall) {
        guard let name = call.getString("name"), let files = call.getArray("files", String.self), !files.isEmpty else {
            call.reject("bad args"); return
        }
        let q8 = call.getBool("q8") ?? false
        let heavy = call.getBool("heavy") ?? false
        queue.async {
            // 큰 모델(새 누끼)은 메모리가 넉넉한 폰(6GB 이상)에서만. 아니면 웹 화면이 기존 모델로 바꿔요.
            if heavy && ProcessInfo.processInfo.physicalMemory < 5_500_000_000 {
                call.reject("TB_LOWMEM"); return
            }
            do {
                if let s = self.sessions[name] {
                    call.resolve(try self.describe(s, accel: self.accels[name] ?? "cpu")); return
                }
                if self.env == nil { self.env = try ORTEnv(loggingLevel: ORTLoggingLevel.warning) }
                let url = try self.modelFile(name: name, files: files, q8: q8)
                var accel = "coreml"
                var session: ORTSession
                do {
                    session = try ORTSession(env: self.env!, modelPath: url.path, sessionOptions: try self.options(coreml: true))
                } catch {
                    // CoreML이 이 모델을 못 받으면 CPU 멀티코어로
                    NSLog("[TbAI] CoreML 실패, CPU로: \(error.localizedDescription)")
                    accel = "cpu"
                    session = try ORTSession(env: self.env!, modelPath: url.path, sessionOptions: try self.options(coreml: false))
                }
                self.sessions[name] = session
                self.accels[name] = accel
                call.resolve(try self.describe(session, accel: accel))
            } catch {
                call.reject("load failed: \(error.localizedDescription)")
            }
        }
    }

    @objc func run(_ call: CAPPluginCall) {
        guard let name = call.getString("name"), let inputs = call.getObject("inputs") else {
            call.reject("bad args"); return
        }
        queue.async {
            guard let session = self.sessions[name] else { call.reject("not loaded"); return }
            do {
                var feeds: [String: ORTValue] = [:]
                for (key, value) in inputs {
                    guard let t = value as? JSObject, let dimsAny = t["dims"] as? JSArray, let b64 = t["data"] as? String,
                          let data = Data(base64Encoded: b64) else {
                        call.reject("bad input \(key)"); return
                    }
                    let shape: [NSNumber] = dimsAny.map { v in
                        if let i = v as? Int { return NSNumber(value: i) }
                        if let d = v as? Double { return NSNumber(value: Int(d)) }
                        if let n = v as? NSNumber { return NSNumber(value: n.intValue) }
                        return NSNumber(value: 0)
                    }
                    feeds[key] = try ORTValue(tensorData: NSMutableData(data: data), elementType: ORTTensorElementDataType.float, shape: shape)
                }
                let outNames = Set(try session.outputNames())
                let outs = try session.run(withInputs: feeds, outputNames: outNames, runOptions: nil)
                var result = JSObject()
                for (key, v) in outs {
                    let d = try v.tensorData() as Data
                    let shape = try v.tensorTypeAndShapeInfo().shape.map { $0.intValue }
                    var o = JSObject()
                    o["dims"] = shape.map { $0 as JSValue } as JSArray
                    o["data"] = d.base64EncodedString()
                    result[key] = o
                }
                call.resolve(["outputs": result])
            } catch {
                call.reject("run failed: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - 내부

    private func options(coreml: Bool) throws -> ORTSessionOptions {
        let o = try ORTSessionOptions()
        try o.setIntraOpNumThreads(Int32(max(1, min(4, ProcessInfo.processInfo.activeProcessorCount))))
        try o.setGraphOptimizationLevel(ORTGraphOptimizationLevel.all)
        if coreml {
            let c = ORTCoreMLExecutionProviderOptions()
            c.enableOnSubgraphs = true
            try o.appendCoreMLExecutionProvider(with: c)
        }
        return o
    }

    private func describe(_ s: ORTSession, accel: String) throws -> [String: Any] {
        return ["inputs": try s.inputNames(), "outputs": try s.outputNames(), "accel": accel]
    }

    private func bundleURL(_ file: String) throws -> URL {
        guard let base = Bundle.main.url(forResource: "public", withExtension: nil) else {
            throw NSError(domain: "TbAI", code: 1, userInfo: [NSLocalizedDescriptionKey: "앱 안에 웹 파일이 없어요"])
        }
        return base.appendingPathComponent(file)
    }

    /// 8비트 압축 조각 → 원래 ONNX 파일 (웹 index.html 의 loadModel 과 같은 형식)
    /// 조각[0] = 그래프(가중치 뺀 모델), 조각[1...] = 이어 붙이면
    ///   [u32 머리 길이][머리 JSON {o: 바깥 머리(base64), e: [[텐서 머리(base64), 행, 열], ...]}][행별 배율 f32...][int8 값...]
    private func modelFile(name: String, files: [String], q8: Bool) throws -> URL {
        if !q8 { return try bundleURL(files[0]) }
        let fm = FileManager.default
        let dir = try fm.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("tbmodels", isDirectory: true)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        // 모델이 바뀌면(조각 크기가 달라지면) 새로 풀어요
        var sig = 0
        for f in files { sig = sig &* 31 &+ ((try? fm.attributesOfItem(atPath: try bundleURL(f).path)[.size] as? Int) ?? 0) }
        let out = dir.appendingPathComponent("\(name)-\(String(sig, radix: 16)).onnx")
        if fm.fileExists(atPath: out.path) { return out }
        // 같은 모델의 옛 캐시는 지워요
        if let old = try? fm.contentsOfDirectory(atPath: dir.path) { for f in old where f.hasPrefix(name + "-") { try? fm.removeItem(at: dir.appendingPathComponent(f)) } }

        let graph = try Data(contentsOf: try bundleURL(files[0]))
        var blob = Data()
        for f in files.dropFirst() { blob.append(try Data(contentsOf: try bundleURL(f))) }
        let headLen = blob.withUnsafeBytes { Int($0.loadUnaligned(fromByteOffset: 0, as: UInt32.self).littleEndian) }
        guard let man = try JSONSerialization.jsonObject(with: blob.subdata(in: 4..<(4 + headLen))) as? [String: Any],
              let outerB64 = man["o"] as? String, let outer = Data(base64Encoded: outerB64),
              let entries = man["e"] as? [[Any]] else {
            throw NSError(domain: "TbAI", code: 2, userInfo: [NSLocalizedDescriptionKey: "모델 머리를 읽지 못했어요"])
        }
        let totalRows = entries.reduce(0) { $0 + (($1[1] as? Int) ?? 0) }
        let scaleStart = 4 + headLen
        var qPos = scaleStart + totalRows * 4
        var rowPos = 0

        let tmp = out.appendingPathExtension("part")
        fm.createFile(atPath: tmp.path, contents: nil)
        let fh = try FileHandle(forWritingTo: tmp)
        defer { try? fh.close() }
        fh.write(graph)
        fh.write(outer)
        try blob.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            for e in entries {
                guard let preB64 = e[0] as? String, let pre = Data(base64Encoded: preB64), let rows = e[1] as? Int, let cols = e[2] as? Int else {
                    throw NSError(domain: "TbAI", code: 3, userInfo: [NSLocalizedDescriptionKey: "모델 조각이 이상해요"])
                }
                fh.write(pre)
                var floats = [Float](repeating: 0, count: rows * cols)
                for r in 0..<rows {
                    let s = raw.loadUnaligned(fromByteOffset: scaleStart + (rowPos + r) * 4, as: Float32.self)
                    let base = qPos + r * cols
                    for c in 0..<cols {
                        let q = Int8(bitPattern: raw[base + c])
                        floats[r * cols + c] = Float(q) * s
                    }
                }
                floats.withUnsafeBufferPointer { fh.write(Data(buffer: $0)) }
                qPos += rows * cols
                rowPos += rows
            }
        }
        try fh.close()
        try fm.moveItem(at: tmp, to: out)
        return out
    }
}
