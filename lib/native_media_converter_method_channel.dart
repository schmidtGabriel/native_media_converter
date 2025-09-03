import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'native_media_converter_platform_interface.dart';

/// An implementation of [NativeMediaConverterPlatform] that uses method channels.
class MethodChannelNativeMediaConverter extends NativeMediaConverterPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('native_media_converter');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
