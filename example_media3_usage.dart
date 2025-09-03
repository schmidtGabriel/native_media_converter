// Example usage of Media3 Transformer in Flutter
// Add this to your lib/ folder as an example implementation

import 'dart:async';
import 'dart:io';
import 'package:flutter/services.dart';

class Media3VideoTranscoder {
  static const MethodChannel _channel = MethodChannel('native_media_converter_mediatool');
  static const EventChannel _eventChannel = EventChannel('native_media_converter_mediatool/progress');

  /// Stream for monitoring transcoding progress
  static Stream<Map<dynamic, dynamic>>? _progressStream;
  
  static Stream<Map<dynamic, dynamic>> get progressStream {
    _progressStream ??= _eventChannel.receiveBroadcastStream().cast<Map<dynamic, dynamic>>();
    return _progressStream!;
  }

  /// Transcode video using Google Media3 Transformer
  /// 
  /// Parameters:
  /// - [inputPath]: Path to input video file
  /// - [outputPath]: Path to output video file  
  /// - [bitrate]: Target bitrate in bits per second (optional)
  /// - [width]: Target width in pixels (optional)
  /// - [height]: Target height in pixels (optional)
  /// - [frameRate]: Target frame rate (optional)
  /// - [videoCodec]: Video codec ('h264', 'h265', 'vp8', 'vp9') (optional)
  /// - [audioCodec]: Audio codec (optional)
  /// - [quality]: Quality preset ('low', 'medium', 'high') (optional)
  /// 
  /// Returns the path to the transcoded video file or null if failed
  static Future<String?> transcodeWithMedia3({
    required String inputPath,
    required String outputPath,
    int? bitrate,
    int? width,
    int? height,
    int? frameRate,
    String? videoCodec,
    String? audioCodec,
    String? quality,
  }) async {
    try {
      final Map<String, dynamic> args = {
        'inputPath': inputPath,
        'outputPath': outputPath,
      };

      if (bitrate != null) args['bitrate'] = bitrate;
      if (width != null) args['width'] = width;
      if (height != null) args['height'] = height;
      if (frameRate != null) args['frameRate'] = frameRate;
      if (videoCodec != null) args['videoCodec'] = videoCodec;
      if (audioCodec != null) args['audioCodec'] = audioCodec;
      if (quality != null) args['quality'] = quality;

      final String? result = await _channel.invokeMethod('transcodeWithMedia3', args);
      return result;
    } on PlatformException catch (e) {
      print('Error transcoding with Media3: ${e.message}');
      return null;
    }
  }

  /// Get list of supported video codecs on this device
  static Future<List<String>> getSupportedCodecs() async {
    try {
      final List<dynamic> result = await _channel.invokeMethod('getSupportedCodecs');
      return result.cast<String>();
    } on PlatformException catch (e) {
      print('Error getting supported codecs: ${e.message}');
      return ['h264']; // Fallback to basic support
    }
  }

  /// Cancel ongoing transcoding operation
  static Future<bool> cancelTranscoding() async {
    try {
      final bool result = await _channel.invokeMethod('cancelTranscoding');
      return result;
    } on PlatformException catch (e) {
      print('Error cancelling transcoding: ${e.message}');
      return false;
    }
  }

  /// Convenience method for common transcoding scenarios
  static Future<String?> compressVideo({
    required String inputPath,
    required String outputPath,
    VideoQuality quality = VideoQuality.medium,
    String codec = 'h264',
  }) async {
    final Map<String, dynamic> params = {
      'inputPath': inputPath,
      'outputPath': outputPath,
      'videoCodec': codec,
      'quality': quality.name,
    };

    // Set resolution and bitrate based on quality
    switch (quality) {
      case VideoQuality.low:
        params['width'] = 640;
        params['height'] = 480;
        params['bitrate'] = 1000000; // 1 Mbps
        break;
      case VideoQuality.medium:
        params['width'] = 1280;
        params['height'] = 720;
        params['bitrate'] = 2000000; // 2 Mbps
        break;
      case VideoQuality.high:
        params['width'] = 1920;
        params['height'] = 1080;
        params['bitrate'] = 5000000; // 5 Mbps
        break;
    }

    return transcodeWithMedia3(
      inputPath: params['inputPath'],
      outputPath: params['outputPath'],
      width: params['width'],
      height: params['height'],
      bitrate: params['bitrate'],
      videoCodec: params['videoCodec'],
      quality: params['quality'],
    );
  }

  /// Check if a video file exists and is valid
  static Future<bool> isValidVideoFile(String path) async {
    try {
      final file = File(path);
      if (!await file.exists()) return false;
      
      final stat = await file.stat();
      return stat.size > 0;
    } catch (e) {
      return false;
    }
  }

  /// Get estimated compression ratio
  static Future<double> getEstimatedCompressionRatio({
    required String inputPath,
    VideoQuality targetQuality = VideoQuality.medium,
  }) async {
    try {
      final inputFile = File(inputPath);
      final inputSize = await inputFile.length();
      
      // Rough estimation based on typical compression ratios
      double compressionFactor;
      switch (targetQuality) {
        case VideoQuality.low:
          compressionFactor = 0.3; // 70% reduction
          break;
        case VideoQuality.medium:
          compressionFactor = 0.5; // 50% reduction
          break;
        case VideoQuality.high:
          compressionFactor = 0.7; // 30% reduction
          break;
      }
      
      // Calculate estimated output size (for future use)
      final estimatedOutputSize = (inputSize * compressionFactor).round();
      print('Estimated output size: ${estimatedOutputSize ~/ (1024 * 1024)}MB');
      
      return compressionFactor;
    } catch (e) {
      return 0.5; // Default 50% compression
    }
  }
}

enum VideoQuality {
  low,
  medium,
  high,
}

/// Example usage class demonstrating complete transcoding workflow
class VideoTranscodingExample {
  static StreamSubscription? _progressSubscription;

  /// Complete example of video transcoding with progress monitoring
  static Future<String?> transcodeVideoWithProgress({
    required String inputPath,
    required String outputPath,
    VideoQuality quality = VideoQuality.medium,
    String codec = 'h264',
    Function(double progress, String message)? onProgress,
  }) async {
    // Subscribe to progress updates
    _progressSubscription = Media3VideoTranscoder.progressStream.listen((progress) {
      final double progressValue = (progress['progress'] ?? 0.0).toDouble();
      final String message = progress['message'] ?? '';
      onProgress?.call(progressValue, message);
    });

    try {
      // Check if input file exists
      if (!await Media3VideoTranscoder.isValidVideoFile(inputPath)) {
        throw Exception('Input file does not exist or is invalid');
      }

      // Check supported codecs
      final supportedCodecs = await Media3VideoTranscoder.getSupportedCodecs();
      if (!supportedCodecs.contains(codec)) {
        print('Codec $codec not supported, falling back to h264');
        codec = 'h264';
      }

      // Start transcoding
      final result = await Media3VideoTranscoder.compressVideo(
        inputPath: inputPath,
        outputPath: outputPath,
        quality: quality,
        codec: codec,
      );

      return result;
    } catch (e) {
      print('Transcoding failed: $e');
      return null;
    } finally {
      // Clean up progress subscription
      await _progressSubscription?.cancel();
      _progressSubscription = null;
    }
  }

  /// Batch transcode multiple videos
  static Future<List<String?>> transcodeMultipleVideos({
    required List<String> inputPaths,
    required List<String> outputPaths,
    VideoQuality quality = VideoQuality.medium,
    String codec = 'h264',
    Function(int currentIndex, int total, double progress, String message)? onProgress,
  }) async {
    if (inputPaths.length != outputPaths.length) {
      throw ArgumentError('Input and output paths must have the same length');
    }

    final List<String?> results = [];

    for (int i = 0; i < inputPaths.length; i++) {
      try {
        final result = await transcodeVideoWithProgress(
          inputPath: inputPaths[i],
          outputPath: outputPaths[i],
          quality: quality,
          codec: codec,
          onProgress: (progress, message) {
            onProgress?.call(i, inputPaths.length, progress, message);
          },
        );
        results.add(result);
      } catch (e) {
        print('Failed to transcode ${inputPaths[i]}: $e');
        results.add(null);
      }
    }

    return results;
  }
}
