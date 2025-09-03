import Flutter
import UIKit
import AVFoundation

public class NativeMediaConverterPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
     private var eventSink: FlutterEventSink?
    private var exportSession: AVAssetExportSession?

    public static func register(with registrar: FlutterPluginRegistrar) {
    let methodChannel = FlutterMethodChannel(name: "native_media_converter", binaryMessenger: registrar.messenger())
    let eventChannel = FlutterEventChannel(name: "native_media_converter/progress", binaryMessenger: registrar.messenger())

    let instance = NativeMediaConverterPlugin()
    registrar.addMethodCallDelegate(instance, channel: methodChannel)
    eventChannel.setStreamHandler(instance)
}

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self.eventSink = nil
        return nil
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        if call.method == "transcode" {
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
            let hdr = args["hdr"] as? Bool ?? false

            self.transcodeVideo(
                inputPath: inputPath,
                outputPath: outputPath,
                crop: crop,
                width: width,
                height: height,
                bitrate: bitrate,
                fps: fps,
                codec: codec,
                hdr: hdr,
            ) { success, outputPath in
                if success == false as Bool?, !success {
                    result(nil)
                } else {
                    print("Transcoding completed: \(outputPath)")
                    result(outputPath)
                }
            }
        } else {
            result(nil)
        }
    }

    private func transcodeVideo(inputPath: String,
                                outputPath: String,
                                crop: [String: Any]?,
                                width: Int,
                                height: Int,
                                bitrate: Int,
                                fps: Int,
                                codec: String,
                                hdr: Bool,
                                completion: @escaping (Bool, String?) -> Void) {
        let asset = AVAsset(url: URL(fileURLWithPath: inputPath))

        guard asset.tracks(withMediaType: .video).first != nil else {
            completion(false, nil)
            return
        }
        
        if let videoTrack = asset.tracks(withMediaType: .video).first {


        Timer.scheduledTimer(withTimeInterval: 0.3, repeats: true) { timer in
                if let session = self.exportSession {
                    let progress = session.progress // 0.0 to 1.0
                    self.eventSink?(progress)
                    print("Progress: \(progress)")
                    if session.status == .completed || session.status == .failed || session.status == .cancelled {
                        timer.invalidate()
                    }
                }
            }

            let videoInfo: [String : Any] = getVideoInfo(videoPath: inputPath)
            let isPortrait = videoInfo["isPortrait"] as? Bool ?? false
            print(isPortrait ? "Video is in portrait orientation." : "Video is in landscape orientation.")
            // Create export session
            let idealPreset = getPreset(width: width, height: height, bitrate: bitrate, fps: fps, codec: codec)
            self.exportSession = AVAssetExportSession(asset: asset, presetName: idealPreset)

            self.exportSession?.outputFileType = AVFileType.mp4
            self.exportSession?.outputURL = URL(fileURLWithPath: outputPath)

            // Video composition for cropping/scaling
            let composition = AVMutableVideoComposition(asset: asset) { request in
                var transform = CGAffineTransform.identity
                if let cropRect = crop {
                    let x = CGFloat(cropRect["x"] as? Int ?? 0)
                    let y = CGFloat(cropRect["y"] as? Int ?? 0)
                    transform = transform.translatedBy(x: -x, y: -y)
                }
                let output = request.sourceImage.transformed(by: transform)
                request.finish(with: output, context: nil)
            }
            composition.renderSize = CGSize(width: width, height: height)
            composition.sourceTrackIDForFrameTiming = kCMPersistentTrackID_Invalid
            composition.frameDuration = CMTime(value: 1, timescale: CMTimeScale(fps))

             // HDR handling: if hdr=true, try to preserve color space
            if hdr {
                // exportSession.videoComposition?.colorPrimaries = AVVideoColorPrimaries_ITU_R_2020
                // exportSession.videoComposition?.colorTransferFunction = AVVideoTransferFunction_ITU_R_2100_HLG
            }
            
            if isPortrait {
                let instruction = AVMutableVideoCompositionInstruction()
                let duration = asset.duration.isValid ? asset.duration : CMTime(seconds: 1, preferredTimescale: 600)
                instruction.timeRange = CMTimeRange(start: .zero, duration: duration)

            let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: videoTrack)
            layerInstruction.setTransform(videoTrack.preferredTransform, at: .zero)
            instruction.layerInstructions = [layerInstruction]
            composition.instructions = [instruction]
            }
           

            self.exportSession?.videoComposition = composition
        }

        self.exportSession?.exportAsynchronously { 

            if self.exportSession?.status == .completed {
                print("Video successfully exported as MP4.")
                completion(true, self.exportSession?.outputURL?.path)

            } else {
                print("Failed to export video: \(String(describing: self.exportSession?.error))")
                completion(false, nil)

            } 

        } 
        }


   private func getPreset(width: Int, height: Int, bitrate: Int, fps: Int, codec: String) -> String {
    // For custom video composition to work, we need presets that support videoComposition
    // AVAssetExportPresetHighestQuality ignores custom compositions
    
    let totalPixels = width * height
    print("Width: \(width) Height: \(height)")
    
    // Consider bitrate per pixel to determine quality
    let bitratePerPixel = Double(bitrate) / Double(totalPixels)
    
    // High bitrate per pixel suggests high quality intent
    let isHighBitrate = bitratePerPixel > 0.1
    
    // High frame rate content
    let isHighFrameRate = fps > 30
    
    // For portrait videos, we need to be more flexible with preset selection
    // since standard presets are designed for landscape orientation
    
    // Determine preset based on resolution and quality requirements
    if totalPixels <= 307200 { // 640x480 or equivalent (480x640 for portrait)
        return isHighBitrate ? AVAssetExportPresetMediumQuality : AVAssetExportPresetLowQuality
    } else if totalPixels <= 518400 { // 960x540 or equivalent (540x960 for portrait)
        return AVAssetExportPresetMediumQuality
    } else if totalPixels <= 921600 { // 1280x720 or equivalent (720x1280 for portrait)
        // For portrait videos at this resolution, use medium quality to ensure compatibility
        return (isHighBitrate || isHighFrameRate ? AVAssetExportPreset1280x720 : AVAssetExportPreset1280x720)
    } else if totalPixels <= 2073600 { // 1920x1080 or equivalent (1080x1920 for portrait)
        // For portrait videos, use medium/high quality presets that work better with custom compositions
        return (isHighBitrate || isHighFrameRate ? AVAssetExportPreset1920x1080 : AVAssetExportPreset1920x1080)
    } else if totalPixels <= 8294400 { // 3840x2160 or equivalent (2160x3840 for portrait)
        // For 4K portrait, use high quality preset
        return  AVAssetExportPreset3840x2160
    } else {
        // For very high resolutions, use highest quality preset
        return AVAssetExportPresetHighestQuality
    }
}

private func getVideoInfo(videoPath: String) -> [String: Any] {
        let asset = AVAsset(url: URL(fileURLWithPath: videoPath))
        
        guard let videoTrack = asset.tracks(withMediaType: .video).first else {
            return [:]
        }
        
        let naturalSize = videoTrack.naturalSize
        let transform = videoTrack.preferredTransform
        
        // Calculate actual display dimensions considering rotation
        let size = naturalSize.applying(transform)
        let displayWidth = abs(size.width)
        let displayHeight = abs(size.height)
        
        // Determine rotation angle from transform matrix
        let rotationAngle = atan2(transform.b, transform.a) * (180 / .pi)
        
        // Determine orientation based on display dimensions
        let isPortrait = displayHeight > displayWidth
        
        return [
            "width": Int(displayWidth),
            "height": Int(displayHeight),
            "naturalWidth": Int(naturalSize.width),
            "naturalHeight": Int(naturalSize.height),
            "rotation": Int(rotationAngle),
            "orientation": isPortrait ? "portrait" : "landscape",
            "isPortrait": isPortrait,
            "duration": asset.duration.seconds,
            "fps": videoTrack.nominalFrameRate
        ]
    }
    
    }
