
# Native Media Converter

Native Media Converter is a Flutter plugin that allows you to convert videos natively on Android and iOS. You can change video resolution and frame rate with simple API calls, making it easy to process media files directly from your Flutter app.

## Features
- Convert videos to different resolutions
- Change video frame rate
- Works on both Android and iOS
- Simple and intuitive API

## Getting Started

### 1. Clone the Repository
```bash
git clone https://github.com/schmidtGabriel/native_media_converter.git
cd native_media_converter
```

### 2. Run the Example App
The `example` folder contains a ready-to-run Flutter app demonstrating the plugin's capabilities.

```bash
cd example
flutter pub get
flutter run
```

## Usage
Add `native_media_converter` to your `pubspec.yaml`:
```yaml
dependencies:
	native_media_converter:
		path: ../native_media_converter
```

Import and use in your Dart code:
```dart
import 'package:native_media_converter/native_media_converter.dart';

// Example usage
final result = await NativeMediaConverter.convertVideo(
	inputPath: 'input.mp4',
	outputPath: 'output.mp4',
	resolution: Resolution.hd,
	frameRate: 30,
);
```

## API Reference
- `convertVideo({inputPath, outputPath, resolution, frameRate})` – Converts a video file with the specified parameters.

## Contributing
Pull requests are welcome! For major changes, please open an issue first to discuss what you would like to change.

## License
This project is licensed under the MIT License.

---

For more details, check the documentation and example usage in the `example` folder.

