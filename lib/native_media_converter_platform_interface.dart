import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'native_media_converter_method_channel.dart';

abstract class NativeMediaConverterPlatform extends PlatformInterface {
  /// Constructs a NativeMediaConverterPlatform.
  NativeMediaConverterPlatform() : super(token: _token);

  static final Object _token = Object();

  static NativeMediaConverterPlatform _instance = MethodChannelNativeMediaConverter();

  /// The default instance of [NativeMediaConverterPlatform] to use.
  ///
  /// Defaults to [MethodChannelNativeMediaConverter].
  static NativeMediaConverterPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [NativeMediaConverterPlatform] when
  /// they register themselves.
  static set instance(NativeMediaConverterPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
