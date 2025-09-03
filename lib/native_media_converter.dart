import 'dart:async';
import 'package:flutter/services.dart';

class HDROptions {
  final bool isHdr; // input is HDR and you want to preserve HDR
  final String colorStandard; // "bt2020" | "bt709"
  final String transfer; // "pq" | "hlg" | "srgb"
  final String range; // "full" | "limited"
  HDROptions({
    this.isHdr = false,
    this.colorStandard = "bt709",
    this.transfer = "srgb",
    this.range = "limited",
  });
  Map<String, dynamic> toMap() => {
    'isHdr': isHdr,
    'colorStandard': colorStandard,
    'transfer': transfer,
    'range': range,
  };
}

class ConvertOptions {
  final String inputPath;
  final String outputPath;
  final String container;   // mp4|mov
  final String codec;  // h264|hevc
  final int width;       // target width (0 = keep)
  final int height;         // target height (0 = keep)
  final int? resolution;  // 1080 || 720 || 480
  final int fps;            // 0 = keep input
  final int videoBitrate;   // in bits per second (use 0 to prefer CRF/CQ mode)
  final double crf;         // 0..51, lower=better quality. If >0, plugin will try CRF/CQ mode
  final String videoProfile; // e.g. "main10", "high"
  final int iFrameIntervalSec;
  final bool audioPassthrough;
  final int audioBitrate;
  final int audioSampleRate;
  final int audioChannels;
  final Map<String,int>? crop; // {left, top, width, height} optional
  final HDROptions hdr;

  ConvertOptions({
    required this.inputPath,
    required this.outputPath,
    this.container = "mp4",
    this.codec = "h264",
    this.width = 0,
    this.height = 0,
    this.resolution,
    this.fps = 0,
    this.videoBitrate = 0,
    this.crf = 0,
    this.videoProfile = "auto",
    this.iFrameIntervalSec = 2,
    this.audioPassthrough = true,
    this.audioBitrate = 128000,
    this.audioSampleRate = 48000,
    this.audioChannels = 2,
    this.crop,

    HDROptions? hdr,
  }): hdr = hdr ?? HDROptions();

  Map<String, dynamic> toMap() => {
    'inputPath': inputPath,
    'outputPath': outputPath,
    'container': container,
    'codec': codec,
    'width': width,
    'height': height,
    'resolution': resolution,
    'fps': fps,
    'videoBitrate': videoBitrate,
    'crf': crf,
    'videoProfile': videoProfile,
    'iFrameIntervalSec': iFrameIntervalSec,
    'audioPassthrough': audioPassthrough,
    'audioBitrate': audioBitrate,
    'audioSampleRate': audioSampleRate,
    'audioChannels': audioChannels,
    'crop': crop,
    'hdr': hdr.toMap(),
  };
}



class NativeMediaConverter {
  static const MethodChannel _m = MethodChannel('native_media_converter_mediatool');
  static const EventChannel _e = EventChannel('native_media_converter_mediatool/progress');

  static Stream<double> progressStream() =>
    _e.receiveBroadcastStream().map((e) => (e as num).toDouble());

   static Future<dynamic> transcode(ConvertOptions opts) async {
    print("Transcode start");
    try {
      final out = await _m.invokeMethod<String>('transcode', opts.toMap());
      print("Transcode output: $out");
      return out ?? '';
    } on PlatformException catch (e) {
      print("Transcode error: ${e.message}");
      throw e;
    }
  }

  static Future<void> cancel() => _m.invokeMethod('cancelTranscode');


  /// Get supported video codecs for the current device
  static Future<List<String>> getSupportedCodecs() async {
    try {
      final result = await _m.invokeMethod<List<dynamic>>('getSupportedCodecs');
      return result?.cast<String>() ?? [];
    } on PlatformException catch (e) {
      print("Error getting supported codecs: ${e.message}");
      return [];
    }
  }
}