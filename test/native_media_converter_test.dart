import 'package:flutter_test/flutter_test.dart';
import 'package:native_media_converter/native_media_converter.dart';
import 'package:native_media_converter/native_media_converter_platform_interface.dart';
import 'package:native_media_converter/native_media_converter_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockNativeMediaConverterPlatform
    with MockPlatformInterfaceMixin
    implements NativeMediaConverterPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final NativeMediaConverterPlatform initialPlatform = NativeMediaConverterPlatform.instance;

  test('$MethodChannelNativeMediaConverter is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelNativeMediaConverter>());
  });
  
  test('getPlatformVersion', () async {
    NativeMediaConverter nativeMediaConverterPlugin = NativeMediaConverter();
    MockNativeMediaConverterPlatform fakePlatform = MockNativeMediaConverterPlatform();
    NativeMediaConverterPlatform.instance = fakePlatform;

    expect(await nativeMediaConverterPlugin.getPlatformVersion(), '42');
  });
}
