import 'dart:io';
import 'package:flutter/material.dart';
import 'package:gal/gal.dart';
import 'package:native_media_converter/native_media_converter.dart';
import 'package:path_provider/path_provider.dart';
import 'package:image_picker/image_picker.dart';
import 'package:video_player/video_player.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Native Media Converter',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      home: const MyHomePage(),
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key});

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  double _progress = 0.0;
  String _status = "Idle";
  File? _selectedVideo;
  File? _transcodedVideo;
  VideoPlayerController? _originalController;
  VideoPlayerController? _transcodedController;
  final ImagePicker _picker = ImagePicker();
  bool _isSaving = false;
  int _selectedResolution = 720; // Default to 720p
  int _selectWidth = 1280;
  int _selectHeight = 720;
  int _selectFrames = 30;


  @override
  void initState() {
    super.initState();

    // Listen for progress updates
    NativeMediaConverter.progressStream().listen((p) {
      setState(() {
        _progress = p;
        _status = "Transcoding: ${(_progress * 100).toStringAsFixed(1)}%";
      });
    });
  }

  @override
  void dispose() {
    _originalController?.dispose();
    _transcodedController?.dispose();
    super.dispose();
  }

  Future<void> _requestPermissions() async {
    await Permission.photos.request();
    await Permission.videos.request();
    // Request storage permission for saving to gallery
    if (Platform.isAndroid) {
      await Permission.storage.request();
      // For Android 13+ (API 33+)
      await Permission.videos.request();
      await Permission.audio.request();
    }
  }

  Future<void> _pickVideo() async {
    await _requestPermissions();
    
    try {
      final XFile? video = await _picker.pickVideo(source: ImageSource.gallery);
      
      if (video != null) {
        setState(() {
          _selectedVideo = File(video.path);
          _transcodedVideo = null;
          _status = "Video selected: ${video.name}";
        });

        // Initialize original video controller
        _originalController?.dispose();
        _originalController = VideoPlayerController.file(_selectedVideo!);
        await _originalController!.initialize();
        setState(() {});
      }
    } catch (e) {
      setState(() {
        _status = "Error picking video: $e";
      });
    }
  }

  Future<void> _startTranscode() async {
    if (_selectedVideo == null) {
      setState(() {
        _status = "Please select a video first";
      });
      return;
    }

    setState(() {
      _progress = 0.0;
      _status = "Starting transcoding...";
    });

    try {
      // Output path in temp directory
      final tempDir = await getTemporaryDirectory();
      final timestamp = DateTime.now().millisecondsSinceEpoch;
      final outputFile = File("${tempDir.path}/transcoded_$timestamp.mp4");

      final opts = ConvertOptions(
        inputPath: _selectedVideo!.path,
        outputPath: outputFile.path,
        width: _selectWidth,
        height: _selectHeight,
        resolution: _selectedResolution,
        fps: _selectFrames,
        videoBitrate: 2_000_000, // Reduced bitrate for faster processing
        codec: "h264",
        container: "mp4",
        // crop: { 'x': 500, 'y': 300, 'width': 1280, 'height': 720 },
        hdr: HDROptions(isHdr: false), // Set to false for better compatibility
      );

      final outPath = await NativeMediaConverter.transcode(opts);
      print(outPath);
      setState(() {
        _status = "Transcoding completed!";
        _progress = 1.0;
        _transcodedVideo = File(outPath);
      });

      // Initialize transcoded video controller
      _transcodedController?.dispose();
      _transcodedController = VideoPlayerController.file(_transcodedVideo!);
      await _transcodedController!.initialize();
      setState(() {});

    } catch (e) {
      setState(() {
        _status = "Error: $e";
      });
    }
  }

  Future<void> _cancel() async {
    await NativeMediaConverter.cancel();
    setState(() {
      _status = "Cancelled";
      _selectedVideo = null;
      _transcodedVideo = null;
      _progress = 0.0;
    });
  }

  Future<void> _saveToGallery() async {
    if (_transcodedVideo == null) {
      setState(() {
        _status = "No transcoded video to save";
      });
      return;
    }

    setState(() {
      _isSaving = true;
      _status = "Saving to gallery...";
    });

    try {
      await _requestPermissions();

      await Gal.putVideo(_transcodedVideo!.path, album: "Transcoded Videos");

      setState(() {
        _isSaving = false;
       
          _status = "Video saved to gallery successfully!";
        
      });

      if (mounted) {
        // Show success dialog
        _showSuccessDialog();
      }
    } catch (e) {
      setState(() {
        _isSaving = false;
                  _status = "Failed to save video to gallery";

      });
    }
  }

  void _showSuccessDialog() {
    if (!mounted) return;
    
    showDialog(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: const Text("Success"),
          content: const Text("Video has been saved to your gallery!"),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text("OK"),
            ),
          ],
        );
      },
    );
  }

  Widget _buildVideoPlayer(VideoPlayerController? controller, String title) {
    if (controller == null || !controller.value.isInitialized) {
      return Container(
        height: 200,
        decoration: BoxDecoration(
          border: Border.all(color: Colors.grey),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Center(
          child: Text(
            title,
            style: const TextStyle(color: Colors.grey),
          ),
        ),
      );
    }

    return Column(
      children: [
        Text(
          title,
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 8),
        Container(
          height: 200,
          decoration: BoxDecoration(
            border: Border.all(color: Colors.grey),
            borderRadius: BorderRadius.circular(8),
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: AspectRatio(
              aspectRatio: controller.value.aspectRatio,
              child: VideoPlayer(controller),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            IconButton(
              onPressed: () {
                setState(() {
                  controller.value.isPlaying
                      ? controller.pause()
                      : controller.play();
                });
              },
              icon: Icon(
                controller.value.isPlaying
                    ? Icons.pause
                    : Icons.play_arrow,
              ),
            ),
            IconButton(
              onPressed: () {
                controller.seekTo(Duration.zero);
                controller.pause();
              },
              icon: const Icon(Icons.stop),
            ),
          ],
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("Native Media Converter Example")),
      body: Padding(
        padding: const EdgeInsets.all(20),
        child: SingleChildScrollView(
          child: Column(
            children: [
              // Progress and status
              LinearProgressIndicator(value: _progress),
              const SizedBox(height: 20),
              Text(
                _status,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 16),
              ),
              const SizedBox(height: 30),

              Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      "Select Resolution:",
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 10),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                      children: [
                        _buildResolutionOption(480, "480p"),
                        _buildResolutionOption(720, "720p"),
                        _buildResolutionOption(1080, "1080p"),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 20),

                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        "Select Frame Rate:",
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(height: 10),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                        children: [
                          _buildFramesOption(15, "15 fps"),
                          _buildFramesOption(20, "20 fps"),
                          _buildFramesOption(30, "30 fps"),
                        ],
                      ),
                    ],
                  ),
                ),
              
              const SizedBox(height: 30),

              // Control buttons
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  ElevatedButton.icon(
                    onPressed: _pickVideo,
                    icon: const Icon(Icons.video_library),
                    label: const Text("Pick Video"),
                  ),
                  ElevatedButton.icon(
                    onPressed: _selectedVideo != null ? _startTranscode : null,
                    icon: const Icon(Icons.play_arrow),
                    label: const Text("Transcode"),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  ElevatedButton.icon(
                    onPressed: _cancel,
                    icon: const Icon(Icons.cancel),
                    label: const Text("Cancel"),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.red,
                      foregroundColor: Colors.white,
                    ),
                  ),
                  ElevatedButton.icon(
                    onPressed: _transcodedVideo != null && !_isSaving 
                        ? _saveToGallery 
                        : null,
                    icon: _isSaving 
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.download),
                    label: Text(_isSaving ? "Saving..." : "Save to Gallery"),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.green,
                      foregroundColor: Colors.white,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 30),

              // Video players
              if (_selectedVideo != null) ...[
                _buildVideoPlayer(_originalController, "Original Video"),
                const SizedBox(height: 30),
              ],
              
              if (_transcodedVideo != null) ...[
                _buildVideoPlayer(_transcodedController, "Transcoded Video"),
                const SizedBox(height: 20),
                Text(
                  "Output file: ${_transcodedVideo!.path}",
                  style: const TextStyle(fontSize: 12, color: Colors.grey),
                  textAlign: TextAlign.center,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

    Widget _buildResolutionOption(int resolution, String label) {
    final isSelected = _selectedResolution == resolution;
    return GestureDetector(
      onTap: () {
        setState(() {
          _selectedResolution = resolution;

          switch (resolution) {
            case 480:
              _selectWidth = 480;
              _selectHeight = 270;
              break;
            case 720:
              _selectWidth = 1280;
              _selectHeight = 720;
              break;
            case 1080:
              _selectWidth = 1920;
              _selectHeight = 1080;
              break;
            default:
              _selectWidth = 1280;
              _selectHeight = 720;
          }

        });
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        decoration: BoxDecoration(
          color: isSelected ? Colors.blue : Colors.grey[200],
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: isSelected ? Colors.blue : Colors.grey,
            width: 2,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: isSelected ? Colors.white : Colors.black,
            fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
          ),
        ),
      ),
    );
  }

   Widget _buildFramesOption(int frameRate, String label) {
    final isSelected = _selectFrames == frameRate;
    return GestureDetector(
      onTap: () {
        setState(() {
          _selectFrames = frameRate;
        });
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        decoration: BoxDecoration(
          color: isSelected ? Colors.orange : Colors.grey[200],
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: isSelected ? Colors.orange : Colors.grey,
            width: 2,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: isSelected ? Colors.white : Colors.black,
            fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
          ),
        ),
      ),
    );
  }
}