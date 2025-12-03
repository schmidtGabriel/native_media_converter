import 'package:flutter_test/flutter_test.dart';
import 'package:native_media_converter/native_media_converter.dart';
import 'package:native_media_converter/native_media_converter_platform_interface.dart';
import 'package:native_media_converter/native_media_converter_method_channel.dart';

void main() {
  final NativeMediaConverterPlatform initialPlatform = NativeMediaConverterPlatform.instance;

  test('$MethodChannelNativeMediaConverter is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelNativeMediaConverter>());
  });
  
  test('ConvertOptions creates proper map without width/height', () {
    final opts = ConvertOptions(
      inputPath: '/input.mp4',
      outputPath: '/output.mp4',
      resolution: 720,
      fps: 30,
    );
    
    final map = opts.toMap();
    
    expect(map['inputPath'], '/input.mp4');
    expect(map['outputPath'], '/output.mp4');
    expect(map['resolution'], 720);
    expect(map['fps'], 30);
    // Ensure width/height are not in the map
    expect(map.containsKey('width'), false);
    expect(map.containsKey('height'), false);
  });
}
