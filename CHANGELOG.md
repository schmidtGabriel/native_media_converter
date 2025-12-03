## 0.0.3

* **BREAKING CHANGE**: Removed `width` and `height` parameters from `ConvertOptions`
* Video dimensions are now automatically determined from `resolution` and input video orientation
* Simplified API - just specify resolution (480, 720, 1080) and orientation is handled automatically
* Updated example app to demonstrate resolution-based workflow
* Fixed test suite to match new API

## 0.0.1

* Initial release of Native Media Converter plugin
* Support for video transcoding on Android and iOS
* Video resolution conversion (480p, 720p, 1080p)
* Frame rate adjustment (15fps, 20fps, 30fps)
* Bitrate control for video quality management
* Progress tracking during transcoding
* Support for H.264 codec and MP4 container
* HDR options configuration
* Cross-platform compatibility with Flutter
* Complete example app demonstrating all features
