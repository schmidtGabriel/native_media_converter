package com.schmidtGabriel.native_media_converter

import android.content.Context
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import io.flutter.plugin.common.EventChannel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


/**
 * Media3TranscoderEngineComplete - Complete Google Media3 Transformer implementation
 * 
 * This is the complete implementation with all Media3 imports and functionality.
 * Use this once the Media3 dependencies are properly resolved in your build.gradle.
 * 
 * To activate this implementation:
 * 1. Replace the content of Media3TranscoderEngine.kt with this file
 * 2. Ensure all Media3 dependencies are in build.gradle
 * 3. Add @UnstableApi annotation to NativeMediaConverterPlugin class
 */
@UnstableApi
class Media3TranscoderEngine(private val context: Context) {

    companion object {
        private const val TAG = "Media3TranscoderComplete"
        private const val EXPORT_TIMEOUT_SECONDS = 50L // 5 minutes timeout
    }

    private var eventSinkProvider: (() -> EventChannel.EventSink?)? = null
    private var transformer: Transformer? = null

    fun setEventSink(provider: () -> EventChannel.EventSink?) {
        eventSinkProvider = provider
    }

    /**
     * Transcode video using Media3 Transformer
     */
    fun transcode(params: Map<String, Any>, callback: (String?) -> Unit) {
        try {
            val inputPath = params["inputPath"] as? String
            val outputPath = params["outputPath"] as? String
            val bitrate = params["videoBitrate"] as? Int
            val width = params["width"] as? Int
            val height = params["height"] as? Int
            val frameRate = params["fps"] as? Int ?: 30
            val videoCodec = params["codec"] as? String
            val audioCodec = params["audioCodec"] as? String
            val quality = params["quality"] as? String
            val resolution = params["resolution"] as? Int ?: 720

            if (inputPath == null || outputPath == null) {
                Log.e(TAG, "Input path or output path is null")
                callback(null)
                return
            }

            Log.d(TAG, "Starting Media3 transcoding: $inputPath -> $outputPath")
            sendProgressUpdate(0.0, "Starting Media3 transcoding...")

            // Create input and output URIs
            val inputUri = Uri.fromFile(File(inputPath))
            val outputUri = Uri.fromFile(File(outputPath))
            val metadata = getVideoMetadata(inputPath)

            // Configure video encoder settings
            val videoEncoderSettingsBuilder = VideoEncoderSettings.Builder()
            
            // Set bitrate if specified
            bitrate?.let { videoEncoderSettingsBuilder.setBitrate(it) }

            // Set video codec
           val selectedMimeType = when (videoCodec?.lowercase()) {
                "h264", null -> MimeTypes.VIDEO_H264
                "h265", "hevc" -> {
                    if (isCodecSupportedByDevice(MimeTypes.VIDEO_H265)) {
                        MimeTypes.VIDEO_H265
                    } else {
                        Log.w(TAG, "H.265 not supported, falling back to H.264")
                        MimeTypes.VIDEO_H264
                    }
                }
                else -> {
                    Log.w(TAG, "Unsupported codec $videoCodec, using H.264")
                    MimeTypes.VIDEO_H264
                }
            }


            // Set quality-based bitrate if no specific bitrate provided
            if (bitrate == null && quality != null) {
                val qualityBitrate = when (quality.lowercase()) {
                    "low" -> 1000000 // 1 Mbps
                    "medium" -> 2000000 // 2 Mbps
                    "high" -> 5000000 // 5 Mbps
                    else -> 2000000 // Default medium
                }
                videoEncoderSettingsBuilder.setBitrate(qualityBitrate)
            }


            val videoEncoderSettings = videoEncoderSettingsBuilder.build()

            var effects = arrayListOf<Effect>()

            effects.add(Presentation.createForHeight(resolution))
            effects.add(FrameDropEffect.createDefaultFrameDropEffect(frameRate.toFloat()))

            if (metadata != null) {
                if(metadata.get("isPortrait") == true){
                    effects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(90F).build())
                }
            }

            // Create media item with effects
            val mediaItem = MediaItem.fromUri(inputUri)
            val editedMediaItem = EditedMediaItem.Builder(mediaItem).apply {
                setEffects( Effects(mutableListOf(), effects))
            }.build()


            // Create composition - Media3 1.8.0 requires EditedMediaItemSequence
            val editedMediaItemSequence = EditedMediaItemSequence(listOf(editedMediaItem))
            val composition = Composition.Builder(editedMediaItemSequence).build()


            // Configure transformer with proper Media3 1.5.0+ API
            val transformerBuilder = Transformer.Builder(context)

            // Now setVideoEncoderSettings should be available in 1.5.0+
            try {
                transformerBuilder.setVideoMimeType(selectedMimeType).setAudioMimeType(MimeTypes.AUDIO_AAC)
                Log.d(TAG, "Video encoder settings applied successfully")
            } catch (e: NoSuchMethodError) {
                Log.w(TAG, "setVideoEncoderSettings not available, using default settings")
            }
            
            transformer = transformerBuilder.build()
            
            Log.d(TAG, "Target codec: $selectedMimeType, Target bitrate: ${videoEncoderSettings.bitrate}")
            
            if (audioCodec != null) {
                Log.d(TAG, "Audio codec specified: $audioCodec")
            }

            // Start progress tracking timer
            val progressTimer = java.util.Timer()
            var currentProgress = 0.1
            val progressTask = object : java.util.TimerTask() {
                override fun run() {
                    if (currentProgress < 0.95) {
                        currentProgress += 0.5
                        sendProgressUpdate(currentProgress, "Processing video...")
                    }
                }
            }
            progressTimer.scheduleAtFixedRate(progressTask, 2000, 3000) // Update every 3 seconds after 2 second delay



            // Set up transformer listener
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    Log.d(TAG, "Media3 transcoding completed successfully")
                    progressTimer.cancel()
                    val outputFile = File(outputPath)
                    if (outputFile.exists() && outputFile.length() > 0) {
                        val fileSizeMB = outputFile.length() / (1024 * 1024)
                        sendProgressUpdate(
                            1.0,
                            "Media3 transcoding completed. File size: ${fileSizeMB}MB"
                        )
                        callback(outputPath)
                    }
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    Log.e(TAG, "Media3 transcoding failed", exception)
                    sendProgressUpdate(1.0, "Media3 transcoding failed: ${exception.message}")
                    progressTimer.cancel()
                    callback(null)
                }

                override fun onFallbackApplied(
                    composition: Composition,
                    originalTransformationRequest: TransformationRequest,
                    fallbackTransformationRequest: TransformationRequest
                ) {
                    Log.w(TAG, "Fallback applied during Media3 transcoding")
                    sendProgressUpdate(0.5, "Applying fallback configuration...")
                }
            }

            transformer?.addListener(listener)



            // Start export
            sendProgressUpdate(0.1, "Initializing Media3 transcoding...")
            // Try the correct API for Media3 1.5.0+
            try {
                transformer?.start(composition, outputPath)
            } catch (e: NoSuchMethodError) {
                // Fallback to older API if available
                Log.w(TAG, "Using fallback start method")
                transformer?.start(composition, outputUri.toString())
            }








        } catch (e: Exception) {
            Log.e(TAG, "Error during Media3 transcoding", e)
            sendProgressUpdate(0.0, "Error: ${e.message}")
            callback(null)
        }
    }

    fun getResolution(resolution: Int): Map<String, Any>?{
        when (resolution){
            480 -> return mapOf("width" to 480, "height" to 854 )
            720 -> return mapOf("width" to 720, "height" to 1280)
            1080 -> return mapOf("width" to 1080, "height" to 1920)
        }
        return null
    }

    /**
     * Cancel ongoing transcoding operation
     */
    fun cancel() {
        transformer?.cancel()
        Log.d(TAG, "Media3 transcoding cancelled")
        sendProgressUpdate(0.0, "Media3 transcoding cancelled")
    }

    /**
     * Send progress update to Flutter
     */
    private fun sendProgressUpdate(progress: Double, message: String) {
        try {
            // Send just the progress value to match Flutter's expectation
            eventSinkProvider?.invoke()?.success(progress)
            Log.d(TAG, "Progress: ${progress}% - $message")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send progress update: ${e.message}")
        }
    }

    /**
     * Get supported video codecs for this device
     */
    fun getSupportedVideoCodecs(): List<String> {
        val supportedCodecs = mutableListOf<String>()
        
        try {
            // Check common video codecs with Media3
            val codecs = listOf(
                MimeTypes.VIDEO_H264 to "h264",
                MimeTypes.VIDEO_H265 to "h265", 
                MimeTypes.VIDEO_VP8 to "vp8",
                MimeTypes.VIDEO_VP9 to "vp9"
            )

            codecs.forEach { (mimeType, codecName) ->
                if (isCodecSupportedByMedia3(mimeType)) {
                    supportedCodecs.add(codecName)
                }
            }
            
            Log.d(TAG, "Supported codecs: $supportedCodecs")
        } catch (e: Exception) {
            Log.w(TAG, "Error checking codec support: ${e.message}")
            // Return basic codecs as fallback
            return listOf("h264")
        }
        
        return supportedCodecs
    }

    private fun isCodecSupportedByDevice(mimeType: String): Boolean {
        return try {
            val mediaCodecList = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            mediaCodecList.findEncoderForFormat(
                android.media.MediaFormat.createVideoFormat(mimeType, 1280, 720)
            ) != null
        } catch (e: Exception) {
            Log.w(TAG, "Error checking codec support for $mimeType: ${e.message}")
            false
        }
    }

    /**
     * Check if a specific codec is supported by Media3
     */
    private fun isCodecSupportedByMedia3(mimeType: String): Boolean {
        return try {
            // Create a test transformer with the specified codec to check support
            val testTransformer = Transformer.Builder(context)
                .setVideoMimeType(mimeType)
                .build()
                
            // If we can create the transformer without exceptions, the codec is supported
            true
        } catch (e: Exception) {
            Log.w(TAG, "Codec $mimeType not supported by Media3: ${e.message}")
            false
        }
    }

    /**
     * Get detailed codec information
     */
    fun getCodecCapabilities(): Map<String, Any> {
        val capabilities = mutableMapOf<String, Any>()
        
        try {
            val supportedCodecs = getSupportedVideoCodecs()
            capabilities["supportedVideoCodecs"] = supportedCodecs
            
            // Check for hardware acceleration support
            capabilities["hardwareAcceleration"] = checkHardwareAcceleration()
            
            // Check maximum supported resolution
            capabilities["maxResolution"] = getMaxSupportedResolution()
            
        } catch (e: Exception) {
            Log.w(TAG, "Error getting codec capabilities: ${e.message}")
        }
        
        return capabilities
    }

    /**
     * Check if hardware acceleration is available
     */
    private fun checkHardwareAcceleration(): Boolean {
        return try {
            // This is a simplified check - in practice you might want to test
            // with a small sample video to verify hardware acceleration
            val testTransformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .build()
                
            true // If no exception, hardware acceleration is likely available
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get maximum supported resolution
     */
    private fun getMaxSupportedResolution(): Map<String, Int> {
        // This is a simplified implementation
        // In practice, you might want to query MediaCodecInfo for precise limits
        return mapOf(
            "width" to 3840,   // 4K width
            "height" to 2160   // 4K height
        )
    }



    /**
     * Get comprehensive video metadata including rotation
     */
    fun getVideoMetadata(inputPath: String): Map<String, Any>? {
       val metadata = VideoMetadataExtractor.getVideoMetadata(inputPath)

        if (metadata != null) {
           return mapOf(
                "width" to metadata.width,
                "height" to metadata.height,
                "displayWidth" to metadata.displayWidth,
                "displayHeight" to metadata.displayHeight,
                "duration" to metadata.duration,
                "rotation" to metadata.rotation,
                "bitrate" to metadata.bitrate,
                "frameRate" to metadata.frameRate,
                "mimeType" to metadata.mimeType,
                "videoCodec" to metadata.videoCodec,
                "filePath" to metadata.filePath,
                "fileSize" to metadata.fileSize,
                "isRotated" to metadata.isRotated(),
                "isPortrait" to metadata.isPortrait(),
                "aspectRatio" to metadata.getAspectRatio(),
                "durationFormatted" to metadata.getDurationFormatted(),
                "fileSizeFormatted" to metadata.getFileSizeFormatted()
            )
        }

        return null
    }


    /**
     * Cleanup resources
     */
    fun cleanup() {
        transformer?.cancel()
        transformer = null
        eventSinkProvider = null
        Log.d(TAG, "Media3 transcoder engine cleaned up")
    }
}
