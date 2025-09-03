package com.schmidtGabriel.native_media_converter

import androidx.annotation.NonNull
import androidx.media3.common.util.UnstableApi
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

@UnstableApi
class NativeMediaConverterPlugin: FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
   private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private val processor = VideoConverter()
    private lateinit var media3Transcoder: Media3TranscoderEngine

   override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "native_media_converter_mediatool")
        channel.setMethodCallHandler(this)

        eventChannel = EventChannel(binding.binaryMessenger, "native_media_converter_mediatool/progress")
        eventChannel.setStreamHandler(this)
        
        // Initialize Media3 transcoder
        try {
            media3Transcoder = Media3TranscoderEngine(binding.applicationContext)
        } catch (e: Exception) {
            // Media3 might not be available, continue without it
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        try {
            media3Transcoder.cleanup()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
    

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        when (call.method) {
            "transcode_old" -> {
                val args = call.arguments as Map<String, Any>
                processor.setEventSink { eventSink }
                processor.transcode(args) { outputPath ->
                    if (outputPath != null) {
                        result.success(outputPath)
                    } else {
                        result.error("TRANSCODE_ERROR", "Error on transcoding", null)
                    }
                }
            }
            "transcode" -> {
                try {
                    val args = call.arguments as Map<String, Any>
                    media3Transcoder.setEventSink { eventSink }
                    media3Transcoder.transcode(args) { outputPath ->
                        if (outputPath != null) {
                            result.success(outputPath)
                        } else {
                            result.error("TRANSCODE_ERROR", "Error on transcoding with Media3", null)
                        }
                    }
                } catch (e: Exception) {
                    result.error("MEDIA3_NOT_AVAILABLE", "Media3 is not available or not initialized", null)
                }
            }
            "getSupportedCodecs" -> {
                try {
                    val supportedCodecs = media3Transcoder.getSupportedVideoCodecs()
                    result.success(supportedCodecs)
                } catch (e: Exception) {
                    result.error("MEDIA3_NOT_AVAILABLE", "Media3 is not available", null)
                }
            }
            "cancelTranscoding" -> {
                try {
                    media3Transcoder.cancel()
                    result.success(true)
                } catch (e: Exception) {
                    result.success(false)
                }
            }
           

        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

   
}