import Flutter
import UIKit
import AVFoundation
import MediaToolSwift

public class NativeMediaConverterPluginMediaTool: NSObject, FlutterPlugin, FlutterStreamHandler {
    private var eventSink: FlutterEventSink?
    private var conversionTask: CompressionTask?
    private var currentProgress: Progress?
    private var progressObserver: NSKeyValueObservation?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let methodChannel = FlutterMethodChannel(name: "native_media_converter_mediatool", binaryMessenger: registrar.messenger())
        let eventChannel = FlutterEventChannel(name: "native_media_converter_mediatool/progress", binaryMessenger: registrar.messenger())

        let instance = NativeMediaConverterPluginMediaTool()
        registrar.addMethodCallDelegate(instance, channel: methodChannel)
        eventChannel.setStreamHandler(instance)
    }

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        // print("MediaToolSwift Advanced: Event sink connected")
        self.eventSink = events
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        // print("MediaToolSwift Advanced: Event sink disconnected")
        self.eventSink = nil
        self.conversionTask?.cancel()
        self.cleanupProgress()
        return nil
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "transcode":
            print("Transcoding video...")
            handleTranscode(call: call, result: result)
        case "getVideoInfo":
            handleGetVideoInfo(call: call, result: result)
        case "cancelTranscode":
            handleCancelTranscode(result: result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    private func handleTranscode(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let inputPath = args["inputPath"] as? String,
              let outputPath = args["outputPath"] as? String else {
            result(FlutterError(code: "BAD_ARGS", message: "Missing input or output path", details: nil))
            return
        }

        let crop = args["crop"] as? [String: Any]
        let width = args["width"] as? Int ?? 1920
        let height = args["height"] as? Int ?? 1080
        let bitrate = args["videoBitrate"] as? Int ?? 5_000_000
        let fps = args["fps"] as? Int ?? 30
        let codec = args["codec"] as? String ?? "h264"
        let resolution = args["resolution"] as? Int ?? nil

        self.transcodeVideo(
            inputPath: inputPath,
            outputPath: outputPath,
            crop: crop,
            width: width,
            height: height,
            resolution: resolution,
            bitrate: bitrate,
            fps: fps,
            codec: codec,
            
        ) { success, outputPath in
            if success {
                result(outputPath)
            } else {
                result(FlutterError(code: "TRANSCODE_FAILED", message: "Failed to transcode video", details: nil))
            }
        }
    }
    
    private func handleGetVideoInfo(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let videoPath = args["videoPath"] as? String else {
            result(FlutterError(code: "BAD_ARGS", message: "Missing video path", details: nil))
            return
        }
        
        Task {
            let info = await self.getVideoInfo(videoPath: videoPath)
            DispatchQueue.main.async {
                result(info)
            }
        }
    }
    
    private func handleCancelTranscode(result: @escaping FlutterResult) {
        print("MediaToolSwift Advanced: Cancelling transcoding...")
        self.conversionTask?.cancel()
        self.cleanupProgress()
        result(true)
    }

    private func transcodeVideo(
        inputPath: String,
        outputPath: String,
        crop: [String: Any]?,
        width: Int,
        height: Int,
        resolution: Int?,
        bitrate: Int,
        fps: Int,
        codec: String,
        completion: @escaping (Bool, String?) -> Void
    ) {
        let sourceURL = URL(fileURLWithPath: inputPath)
        let destinationURL = URL(fileURLWithPath: outputPath)
        
        Task {
            do {
                // Get video info to determine orientation and properties
                let videoInfo = try await VideoTool.getInfo(source: sourceURL)
                let isPortrait = videoInfo.resolution.height > videoInfo.resolution.width
                let isHdr = videoInfo.isHDR;
                
                print("MediaToolSwift Advanced: Video info - Resolution: \(videoInfo.resolution), Duration: \(videoInfo.duration), Orientation: \(isPortrait ? "Portrait" : "Landscape"), HDR: \(isHdr ? "YES" : "NO")")
                
                // Check if source has HDR and log it
                print("MediaToolSwift Advanced: Source video analysis - checking for HDR characteristics")
                
                // Configure video codec
                let videoCodec: AVVideoCodecType = self.mapStringToCodec(codec)
                       

                // Configure video size and operations
                var targetSize = !isPortrait ? CGSize(width: width, height: height) : CGSize(width: height, height: width)
                var videoSize: CompressionVideoSize = .fit(targetSize)

                if resolution != nil {
                    let _resolution: Int = resolution!;
                    let mappedResolution = self.getResolution(resolution: _resolution, isPortrait: isPortrait)
                    targetSize = CGSize(width: mappedResolution?.width ?? 0, height: mappedResolution?.height ?? 0)
                    videoSize = .fit(targetSize)
                }
               
                let videoOperations = self.configureVideoOperations(crop: crop)
            
                
                var videoSettings: CompressionVideoSettings
                
                // Create settings with explicit SDR parameters
                    videoSettings = CompressionVideoSettings(
                        codec: videoCodec,
                        bitrate: .value(bitrate),
                        size: videoSize,
                        frameRate: fps,
                        preserveAlphaChannel: false,
                        profile: .h264High,
                        color: .itu709_2,    
                        hardwareAcceleration: .auto,
                        edit: videoOperations
                    )
                
              
                
                // Configure audio settings
                let audioSettings = CompressionAudioSettings(
                    codec: .aac,
                    bitrate: .auto,
                    quality: .medium
                )
                
                // print("MediaToolSwift Advanced: Starting conversion with settings - Codec: \(videoCodec), Bitrate: \(bitrate), Size: \(targetSize), FPS: \(fps)")
                                
                self.conversionTask = await VideoTool.convert(
                    source: sourceURL,
                    destination: destinationURL,
                    fileType: .mp4,
                    videoSettings: videoSettings,
                    optimizeForNetworkUse: true,
                    skipAudio: false,
                    audioSettings: audioSettings,
                    overwrite: true,
                    callback: { [weak self] state in
                        DispatchQueue.main.async {
                            switch state {
                            case .started:
                                print("MediaToolSwift Advanced: Transcoding started")
                                
                            case .completed(let info):
                                print("MediaToolSwift Advanced: Transcoding completed: \(info.url.path)")
                              
                                self?.cleanupProgress()
                                completion(true, info.url.path)
                                
                            case .failed(let error):
                                print("MediaToolSwift Advanced: Transcoding failed: \(error.localizedDescription)")
                                self?.cleanupProgress()
                                completion(false, nil)
                                
                            case .cancelled:
                                print("MediaToolSwift Advanced: Transcoding cancelled")
                                self?.cleanupProgress()
                                completion(false, nil)
                            }
                        }
                    }
                )
                
                // Set up progress monitoring using MediaToolSwift's Progress object
                if let task = self.conversionTask {
                    print("MediaToolSwift Advanced: Setting up progress monitoring for task")
                    self.setupProgressMonitoring(task: task)
                } else {
                    print("MediaToolSwift Advanced: Warning - conversion task is nil, cannot set up progress monitoring")
                }
                
            } catch {
                print("MediaToolSwift Advanced: Error during transcoding setup: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    completion(false, nil)
                }
            }
        }
    }

    
    // MARK: - Helper Methods


    private func getResolution(resolution: Int, isPortrait: Bool) -> (width: Int, height: Int)? {

        switch resolution {
        case 1080:
            return isPortrait ? (width: 1080, height: 1920) : (width: 1920, height: 1080)
        case 720:
            return isPortrait ? (width: 720, height: 1280) : (width: 1280, height: 720)
        case 480:
            return isPortrait ? (width: 480, height: 854) : (width: 854, height: 480)
        default:
            return nil
        }
    }
    
    private func mapStringToCodec(_ codec: String) -> AVVideoCodecType {
        switch codec.lowercased() {
        case "h264":
            return .h264
        case "h265", "hevc":
            return .hevc
        case "prores":
            return .proRes422
        default:
            return .h264
        }
    }
    
    private func configureVideoOperations(crop: [String: Any]?) -> Set<VideoOperation> {
        var videoOperations: Set<VideoOperation> = []
        
        if let cropData = crop,
           let x = cropData["x"] as? Int,
           let y = cropData["y"] as? Int,
           let cropWidth = cropData["width"] as? Int,
           let cropHeight = cropData["height"] as? Int {
                        
            videoOperations.insert(VideoOperation.crop(
                Crop(
                    origin: CGPoint(x: x, y: y),
                    size: CGSize(width: cropWidth, height: cropHeight)
                )
            ))

            
            print("MediaToolSwift Advanced: Applying crop - X: \(x), Y: \(y), Width: \(cropWidth), Height: \(cropHeight)")
        }
        
        return videoOperations
    }

    private func setupProgressMonitoring(task: CompressionTask) {
        print("MediaToolSwift Advanced: Accessing task progress object")
        
        // Observe the main progress object from MediaToolSwift's CompressionTask
        self.observeProgress(task.progress)
        
        // Optionally, you can also observe writing progress for more detailed feedback
        // self.observeProgress(task.writingProgress)
        
        print("MediaToolSwift Advanced: Progress monitoring setup completed")
    }
    
    private func cleanupProgress() {
        self.progressObserver?.invalidate()
        self.progressObserver = nil
        self.currentProgress = nil
        self.conversionTask = nil
    }
    
    private func getVideoInfo(videoPath: String) async -> [String: Any] {
        do {
            let url = URL(fileURLWithPath: videoPath)
            let info = try await VideoTool.getInfo(source: url)
            
            let isPortrait = info.resolution.height > info.resolution.width

             // Get file size using FileManager
            let fileSize: Int64
            do {
                let attributes = try FileManager.default.attributesOfItem(atPath: videoPath)
                fileSize = attributes[.size] as? Int64 ?? 0
            } catch {
                fileSize = 0
            }
            
            return [
                "width": Int(info.resolution.width),
                "height": Int(info.resolution.height),
                "orientation": isPortrait ? "portrait" : "landscape",
                "isPortrait": isPortrait,
                "duration": info.duration,
                "fps": info.frameRate ?? 30.0,
                "bitrate": info.videoBitrate ?? 0,
                "hasAudio": info.hasAudio, // Remove .rawValue since hasAudio is Bool
                "audioCodec": info.audioCodec?.rawValue ?? "", // Add optional chaining
                "videoCodec": info.videoCodec.rawValue ?? "",
                "fileSize": fileSize,
                "isHdr": info.isHDR
            ]
        } catch {
            print("MediaToolSwift Advanced: Error getting video info: \(error.localizedDescription)")
            return [
                "error": error.localizedDescription
            ]
        }
    }
    
    // private func analyzeOutputVideo(path: String) async {
    //     print("=== OUTPUT VIDEO ANALYSIS ===")
    //     print("Analyzing: \(path)")
        
    //     do {
    //         let url = URL(fileURLWithPath: path)
    //         let asset = AVAsset(url: url)
            
    //         // Load video tracks asynchronously
    //         let videoTracks = try await asset.loadTracks(withMediaType: .video)
            
    //         for (index, track) in videoTracks.enumerated() {
    //             print("Video Track \(index):")
                
    //             // Get format descriptions to check codec and HDR metadata
    //             let formatDescriptions = track.formatDescriptions
    //             let codecString = formatDescriptions.first.map { desc in
    //                 let mediaSubType = CMFormatDescriptionGetMediaSubType(desc)
    //                 return fourCCToString(mediaSubType)
    //             } ?? "Unknown"
                
    //             print("  Codec: \(codecString)")
    //             print("  Natural Size: \(track.naturalSize)")
    //             print("  Estimated Data Rate: \(track.estimatedDataRate)")
                
    //             // Analyze format descriptions for HDR metadata
    //             for (formatIndex, formatDesc) in formatDescriptions.enumerated() {
    //                 print("  Format Description \(formatIndex):")
                    
    //                 let mediaSubType = CMFormatDescriptionGetMediaSubType(formatDesc)
    //                 print("    Media SubType: \(fourCCToString(mediaSubType))")
                    
    //                 // Check for HDR-related extensions or metadata
    //                 let extensions = CMFormatDescriptionGetExtensions(formatDesc)
    //                 if let ext = extensions as? [String: Any] {
    //                     print("    Extensions: \(ext)")
                        
    //                     // Look for HDR-specific keys
    //                     let hasHDRKeys = ext.keys.contains { key in
    //                         let keyStr = key.lowercased()
    //                         return keyStr.contains("hdr") || 
    //                                keyStr.contains("dolby") ||
    //                                keyStr.contains("2020") ||
    //                                keyStr.contains("hlg") ||
    //                                keyStr.contains("pq") ||
    //                                keyStr.contains("colorprimaries") ||
    //                                keyStr.contains("transferfunction") ||
    //                                keyStr.contains("matrixcoefficients")
    //                     }
                        
    //                     if hasHDRKeys {
    //                         print("    ⚠️  WARNING: Potential HDR metadata found in extensions")
    //                     } else {
    //                         print("    ✅ No obvious HDR metadata in extensions")
    //                     }
    //                 } else {
    //                     print("    ✅ No format extensions (good for SDR)")
    //                 }
    //             }
    //         }
            
    //     } catch {
    //         print("Error analyzing output video: \(error.localizedDescription)")
    //     }
    // }
    
    private func fourCCToString(_ fourCC: FourCharCode) -> String {
        let bytes = [
            UInt8((fourCC >> 24) & 0xFF),
            UInt8((fourCC >> 16) & 0xFF),
            UInt8((fourCC >> 8) & 0xFF),
            UInt8(fourCC & 0xFF)
        ]
        return String(bytes: bytes, encoding: .ascii) ?? "Unknown"
    }
}

// MARK: - Extensions for MediaToolSwift Integration

extension NativeMediaConverterPluginMediaTool {
    
    /// Enhanced progress monitoring that integrates with MediaToolSwift's progress system
    private func observeProgress(_ progress: Progress) {
        self.currentProgress = progress
        
        self.progressObserver = progress.observe(\.fractionCompleted, options: [.new, .initial]) { [weak self] progress, change in
            DispatchQueue.main.async {
                let fractionCompleted = progress.fractionCompleted
                print("MediaToolSwift Advanced: Progress updated: \(fractionCompleted)")
                
                // Send progress to Flutter via event sink
                if let eventSink = self?.eventSink {
                    eventSink(fractionCompleted)
                } else {
                    print("MediaToolSwift Advanced: Warning - eventSink is nil, cannot send progress")
                }
            }
        }
        
        print("MediaToolSwift Advanced: Progress monitoring started")
    }
}
