# Google Media3 Transformer Integration

This document describes the Google Media3 Transformer implementation added to the Native Media Converter plugin.

## Overview

The `Media3TranscoderEngine.kt` file provides a new transcoding engine that uses Google's Media3 Transformer library for efficient video transcoding with hardware acceleration support.

## Features

- **Hardware Acceleration**: Leverages device hardware encoders for efficient transcoding
- **Multiple Codec Support**: Supports H.264, H.265, VP8, and VP9 codecs
- **Resolution Scaling**: Allows changing video resolution during transcoding
- **Bitrate Control**: Precise bitrate settings for output quality control
- **Progress Monitoring**: Real-time progress updates via Flutter event channels
- **Error Handling**: Comprehensive error handling and fallback mechanisms

## Dependencies Added

The following dependencies have been added to `android/build.gradle`:

```gradle
// Google Media3 dependencies for video transcoding
implementation("androidx.media3:media3-transformer:1.4.1")
implementation("androidx.media3:media3-effect:1.4.1")
implementation("androidx.media3:media3-common:1.4.1")
implementation("androidx.media3:media3-muxer:1.4.1")
implementation("androidx.media3:media3-extractor:1.4.1")
implementation("androidx.media3:media3-decoder:1.4.1")
```

## Usage

### From Flutter/Dart

```dart
// Use Media3 transcoder instead of the default transcoder
final result = await NativeMediaConverter.transcodeWithMedia3({
  'inputPath': '/path/to/input/video.mp4',
  'outputPath': '/path/to/output/video.mp4',
  'bitrate': 2000000, // 2 Mbps
  'width': 1280,
  'height': 720,
  'frameRate': 30,
  'videoCodec': 'h264', // h264, h265, vp8, vp9
  'audioCodec': 'aac',
  'quality': 'medium' // low, medium, high
});

// Get supported codecs
final codecs = await NativeMediaConverter.getSupportedCodecs();
print('Supported codecs: $codecs');

// Cancel ongoing transcoding
await NativeMediaConverter.cancelTranscoding();
```

### Available Parameters

| Parameter | Type | Description | Example |
|-----------|------|-------------|---------|
| `inputPath` | String | Path to input video file | `/storage/emulated/0/input.mp4` |
| `outputPath` | String | Path to output video file | `/storage/emulated/0/output.mp4` |
| `bitrate` | int | Target bitrate in bits per second | `2000000` (2 Mbps) |
| `width` | int | Target video width | `1280` |
| `height` | int | Target video height | `720` |
| `frameRate` | int | Target frame rate | `30` |
| `videoCodec` | String | Video codec (`h264`, `h265`, `vp8`, `vp9`) | `h264` |
| `audioCodec` | String | Audio codec | `aac` |
| `quality` | String | Quality preset (`low`, `medium`, `high`) | `medium` |

### Supported Video Codecs

- **H.264 (AVC)**: Most widely supported, good compression
- **H.265 (HEVC)**: Better compression than H.264, newer devices
- **VP8**: Open source, web-friendly
- **VP9**: Better compression than VP8, newer standard

## Implementation Details

### Media3TranscoderEngine Class

The `Media3TranscoderEngine` class is the main interface for Media3-based transcoding:

```kotlin
class Media3TranscoderEngine(private val context: Context) {
    fun transcode(params: Map<String, Any>, callback: (String?) -> Unit)
    fun cancel()
    fun getSupportedVideoCodecs(): List<String>
    fun cleanup()
}
```

### Key Methods

#### `transcode(params, callback)`
- Performs video transcoding with specified parameters
- Calls callback with output path on success, null on failure
- Sends progress updates via event channel

#### `cancel()`
- Cancels ongoing transcoding operation
- Safe to call even if no transcoding is in progress

#### `getSupportedVideoCodecs()`
- Returns list of video codecs supported by the device
- Useful for checking codec availability before transcoding

### Plugin Integration

The Media3 transcoder is integrated into the main plugin via new method channels:

- `transcodeWithMedia3`: Uses Media3 Transformer for transcoding
- `getSupportedCodecs`: Returns supported video codecs
- `cancelTranscoding`: Cancels ongoing Media3 transcoding

## Progress Monitoring

Progress updates are sent via the existing event channel:

```dart
NativeMediaConverter.progressStream.listen((progress) {
  print('Progress: ${progress['progress']}%');
  print('Message: ${progress['message']}');
});
```

## Error Handling

The implementation includes comprehensive error handling:

- **Codec not supported**: Falls back to default codec
- **Hardware encoder unavailable**: Uses software encoder
- **Out of memory**: Reduces quality settings
- **File I/O errors**: Proper error reporting

## Performance Considerations

### Hardware Acceleration
- Media3 automatically uses hardware encoders when available
- Fallback to software encoding when hardware is unavailable
- Significant performance improvement on supported devices

### Memory Management
- Efficient memory usage compared to raw MediaCodec API
- Automatic resource cleanup
- Configurable timeout for long operations

## Best Practices

1. **Check codec support** before transcoding to ensure compatibility
2. **Use appropriate bitrates** for target resolution and quality
3. **Monitor progress** for user feedback during long operations
4. **Handle errors gracefully** with fallback options
5. **Clean up resources** when done transcoding

## Troubleshooting

### Common Issues

1. **Media3 dependencies not found**
   - Ensure gradle sync has completed
   - Check that Media3 versions are compatible

2. **Codec not supported error**
   - Use `getSupportedCodecs()` to check available codecs
   - Fall back to H.264 for maximum compatibility

3. **Out of memory during transcoding**
   - Reduce output resolution or bitrate
   - Use lower quality settings

4. **Slow transcoding performance**
   - Check if hardware acceleration is available
   - Consider using lower resolution for faster processing

### Debug Tips

- Enable verbose logging in the Media3TranscoderEngine
- Monitor device temperature during long transcoding sessions
- Test with various input formats and resolutions

## Future Enhancements

Potential improvements for the Media3 implementation:

1. **Audio processing effects**: Echo, noise reduction, etc.
2. **Video filters**: Color correction, blur, etc.
3. **Multi-pass encoding**: For better quality/size ratio
4. **Adaptive bitrate**: Dynamic quality adjustment
5. **Batch processing**: Multiple files in sequence

## Example Complete Implementation

```dart
class VideoTranscoder {
  static Future<String?> transcodeVideo({
    required String inputPath,
    required String outputPath,
    int? targetWidth,
    int? targetHeight,
    int? bitrate,
    String codec = 'h264',
  }) async {
    try {
      // Check if codec is supported
      final supportedCodecs = await NativeMediaConverter.getSupportedCodecs();
      if (!supportedCodecs.contains(codec)) {
        print('Codec $codec not supported, falling back to h264');
        codec = 'h264';
      }
      
      // Prepare transcoding parameters
      final params = {
        'inputPath': inputPath,
        'outputPath': outputPath,
        'videoCodec': codec,
        if (targetWidth != null) 'width': targetWidth,
        if (targetHeight != null) 'height': targetHeight,
        if (bitrate != null) 'bitrate': bitrate,
      };
      
      // Start transcoding
      final result = await NativeMediaConverter.transcodeWithMedia3(params);
      return result;
      
    } catch (e) {
      print('Transcoding failed: $e');
      return null;
    }
  }
}
```

This comprehensive implementation provides a robust, efficient video transcoding solution using Google's Media3 Transformer library.
