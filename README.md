
# Native Media Converter

[![pub package](https://img.shields.io/pub/v/native_media_converter.svg)](https://pub.dev/packages/native_media_converter)

A powerful Flutter plugin for native video conversion on Android and iOS. Convert videos to different resolutions, frame rates, and formats with simple API calls, all while maintaining excellent performance through native implementations.

## Features

- 🎥 **Video Resolution Conversion** - Convert videos to 480p, 720p, or 1080p
- 🎞️ **Frame Rate Adjustment** - Change frame rates to 15fps, 20fps, or 30fps  
- 📱 **Cross-Platform** - Works seamlessly on both Android and iOS
- ⚡ **Native Performance** - Uses native code for optimal processing speed
- 📊 **Progress Tracking** - Real-time progress updates during conversion
- 🎛️ **Bitrate Control** - Adjust video quality and file size
- 🎨 **HDR Support** - Configure HDR options for enhanced video quality
- 📦 **Multiple Formats** - Support for H.264 codec and MP4 container

## Installation

Add this to your package's `pubspec.yaml` file:

```yaml
dependencies:
  native_media_converter: ^0.0.3
```

Then run:

```bash
flutter pub get
```

## Usage

### Basic Example

```dart
import 'package:native_media_converter/native_media_converter.dart';

// Listen for progress updates
NativeMediaConverter.progressStream().listen((progress) {
  print('Conversion progress: ${(progress * 100).toStringAsFixed(1)}%');
});

// Configure conversion options
final options = ConvertOptions(
  inputPath: '/path/to/input/video.mp4',
  outputPath: '/path/to/output/video.mp4',
  width: 1280,
  // width/height are derived from `resolution` and input orientation on the native side
  resolution: 720,
  fps: 30,
  videoBitrate: 2000000,
  codec: "h264",
  container: "mp4",
  hdr: HDROptions(isHdr: false),
);

try {
  // Start conversion
  final outputPath = await NativeMediaConverter.transcode(options);
  print('Video converted successfully: $outputPath');
} catch (e) {
  print('Conversion failed: $e');
}
```

### Complete Example with UI

For a complete example with a user interface, see the [example app](https://github.com/schmidtGabriel/native_media_converter/tree/main/example) which demonstrates:

- Video picker integration
- Real-time progress tracking
- Resolution and frame rate selection
- Video preview (before and after)
- Save to device gallery

## API Reference
- `transcode({inputPath, outputPath, resolution, fps})` – Converts a video file with the specified parameters.

## Contributing
Pull requests are welcome! For major changes, please open an issue first to discuss what you would like to change.

## License
This project is licensed under the MIT License.

---

For more details, check the documentation and example usage in the `example` folder.

