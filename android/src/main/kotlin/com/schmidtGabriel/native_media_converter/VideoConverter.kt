package com.schmidtGabriel.native_media_converter

import android.media.*
import android.os.Build
import android.util.Log
import android.view.Surface
import io.flutter.plugin.common.EventChannel
import java.io.File
import java.nio.ByteBuffer

/**
 * RobustVideoConverter - Enhanced video converter with comprehensive error handling
 * and multiple fallback strategies for maximum device compatibility.
 */
class VideoConverter {

    companion object {
        private const val TAG = "RobustVideoConverter"
        private const val TIMEOUT_US = 10000L
    }

    private var eventSinkProvider: (() -> EventChannel.EventSink?)? = null

    fun setEventSink(provider: () -> EventChannel.EventSink?) {
        eventSinkProvider = provider
    }

    /**
     * Check if a specific codec is supported on this device
     */
    private fun isCodecSupported(mimeType: String): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val codecInfo = codecList.findEncoderForFormat(MediaFormat.createVideoFormat(mimeType, 1280, 720))
            codecInfo != null
        } catch (e: Exception) {
            Log.w(TAG, "Error checking codec support for $mimeType: ${e.message}")
            false
        }
    }

    /**
     * Get supported color format for a codec
     */
    private fun getSupportedColorFormat(codecInfo: MediaCodecInfo, mimeType: String): Int {
        val capabilities = codecInfo.getCapabilitiesForType(mimeType)
        val colorFormats = capabilities.colorFormats
        
        // Preference order for compatibility
        val preferredFormats = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        )
        
        for (format in preferredFormats) {
            if (colorFormats.contains(format)) {
                Log.d(TAG, "Using color format: $format")
                return format
            }
        }
        
        val fallbackFormat = colorFormats.firstOrNull() ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        Log.w(TAG, "Using fallback color format: $fallbackFormat")
        return fallbackFormat
    }

    /**
     * Create encoder with capability check - simplified version
     */
    private fun createEncoderWithCapabilityCheck(
        mime: String,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int
    ): MediaCodec {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val codecInfo = codecList.findEncoderForFormat(MediaFormat.createVideoFormat(mime, width, height))
                ?: throw RuntimeException("No encoder found for $mime")
            
            Log.d(TAG, "Using encoder: ${codecInfo} for $mime")
            
            // Use conservative settings for better compatibility
            val safeWidth = minOf(width, 1920)
            val safeHeight = minOf(height, 1080)
            val safeBitrate = minOf(bitrate, 5_000_000)
            val safeFps = minOf(fps, 30)
            
            Log.d(TAG, "Safe params: ${safeWidth}x${safeHeight}, ${safeBitrate}bps, ${safeFps}fps")
            
            return createBasicSurfaceEncoder(mime, safeWidth, safeHeight, safeBitrate, safeFps)
        } catch (e: Exception) {
            Log.w(TAG, "Capability check failed: ${e.message}")
            return createBasicSurfaceEncoder(mime, width, height, bitrate, fps)
        }
    }

    /**
     * Log device capabilities for debugging
     */
    private fun logDeviceCapabilities() {
        Log.d(TAG, "Device info: API ${Build.VERSION.SDK_INT}, Model: ${Build.MODEL}, Manufacturer: ${Build.MANUFACTURER}")
        
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var encoderCount = 0
        
        for (codecInfo in codecList.codecInfos) {
            if (codecInfo.isEncoder) {
                encoderCount++
                Log.d(TAG, "Available encoder: ${codecInfo.name}")
                for (type in codecInfo.supportedTypes) {
                    if (type.startsWith("video/")) {
                        Log.d(TAG, "  Supports: $type")
                    }
                }
                if (encoderCount > 10) break // Limit logging
            }
        }
    }

    /**
     * Main transcode function with robust error handling
     */
    fun transcode(args: Map<String, Any>, callback: (String?) -> Unit) {
        val inputPath = args["inputPath"] as String
        val outputPath = args["outputPath"] as String
        val crop = args["crop"] as? Map<String, Any>
        val width = args["width"] as? Int ?: 1920
        val height = args["height"] as? Int ?: 1080
        val resolution = args["resolution"] as? Int ?: 720
        val bitrate = (args["bitrate"] as? Int) ?: 2_000_000
        val fps = (args["fps"] as? Int) ?: 30
        val codec = (args["codec"] as? String) ?: "h264"
        val hdr = (args["hdr"] as? Boolean) ?: false

        Thread {
            try {
                Log.d(TAG, "Starting robust video transcode")
                
                // Log device capabilities for debugging
                logDeviceCapabilities()

                // Validate inputs
                validateInputs(inputPath, outputPath, width, height, bitrate, fps, codec)

                // Try surface method first (hardware accelerated)
                if (canUseSurfaceMethod()) {
                    try {
                        transcodeWithSurface(
                            inputPath,
                            outputPath,
                            width,
                            height,
                            resolution,
                            bitrate,
                            fps,
                            codec,
                            hdr,
                            crop
                        )
                        sendProgress(1.0)
                        callback(outputPath)
                        return@Thread
                    } catch (e: Exception) {
                        Log.w(TAG, "Surface method failed: ${e.message}, trying buffer method")
                    }
                }

                // Fallback to buffer method
                transcodeWithBuffers(
                    inputPath,
                    outputPath,
                    width,
                    height,
                    resolution,
                    bitrate,
                    fps,
                    codec,
                    hdr,
                    crop
                )
                sendProgress(1.0)
                callback(outputPath)

            } catch (e: Exception) {
                Log.e(TAG, "Video transcode failed", e)
                callback(null)
            }
        }.start()
    }

    private fun validateInputs(
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int,
        codec: String
    ) {
        val inputFile = File(inputPath)
        if (!inputFile.exists() || inputFile.length() == 0L) {
            throw IllegalArgumentException("Invalid input file: $inputPath")
        }

        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw IllegalArgumentException("Invalid dimensions: ${width}x${height}")
        }

        if (bitrate <= 0 || fps <= 0) {
            throw IllegalArgumentException("Invalid bitrate or fps: $bitrate, $fps")
        }
    }

    private fun canUseSurfaceMethod(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return false
        }
        
        // Additional check for devices with problematic surface implementations
        return try {
            val testEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            testEncoder.createInputSurface() // Test if surface actually works
            testEncoder.release()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Surface method not working on this device: ${e.message}")
            false
        }
    }

    /**
     * Surface-based transcoding with proper error handling
     */
    private fun transcodeWithSurface(
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        resolution: Int,
        bitrate: Int,
        fps: Int,
        codec: String,
        hdr: Boolean,
        crop: Map<String, Any>?
    ) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: Surface? = null

        try {
            // Setup extractor
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val videoTrackIndex = findVideoTrack(extractor)
            extractor.selectTrack(videoTrackIndex)
            val inputFormat = extractor.getTrackFormat(videoTrackIndex)
            
            // Calculate total duration for progress calculation
            val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION)
            Log.d(TAG, "Video duration: ${durationUs}µs")

            // Calculate output dimensions
            val outputDimensions = calculateOutputDimensions(inputFormat, width, height, resolution)
            val outputWidth = outputDimensions.first
            val outputHeight = outputDimensions.second

            Log.d(TAG, "Surface transcode: ${outputWidth}x${outputHeight}")

            // Create decoder
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(inputMime)

            // Create encoder with robust configuration
            val outputMime = getMimeTypeForCodec(codec)
            encoder =
                createRobustSurfaceEncoder(outputMime, outputWidth, outputHeight, bitrate, fps)
            surface = encoder.createInputSurface()
            encoder.start()

            // Configure decoder with surface
            decoder.configure(inputFormat, surface, null, 0)
            decoder.start()

            // Setup muxer
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Process video with audio
            processSurfaceVideoWithAudio(extractor, decoder, encoder, muxer, inputPath, outputPath, durationUs)

        } finally {
            cleanupResources(decoder, encoder, muxer, extractor, surface)
        }
    }

    /**
     * Creates a surface encoder with multiple fallback strategies
     */
    private fun createRobustSurfaceEncoder(
        mime: String,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int
    ): MediaCodec {
        val strategies = listOf(
            // Strategy 1: Verificar capacidades do dispositivo primeiro
            { createEncoderWithCapabilityCheck(mime, width, height, bitrate, fps) },
            // Strategy 2: Basic configuration
            { createBasicSurfaceEncoder(mime, width, height, bitrate, fps) },
            // Strategy 3: Reduced bitrate
            { createBasicSurfaceEncoder(mime, width, height, bitrate / 2, fps) },
            // Strategy 4: Reduced resolution with 16-pixel alignment
            {
                createBasicSurfaceEncoder(
                    mime,
                    width - (width % 16),
                    height - (height % 16),
                    bitrate / 2,
                    fps
                )
            },
            // Strategy 5: Conservative settings with H.264 fallback
            {
                createBasicSurfaceEncoder(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    minOf(width, 1280), minOf(height, 720),
                    minOf(bitrate, 2_000_000), minOf(fps, 30)
                )
            },
            // Strategy 6: Minimum safe configuration
            {
                createBasicSurfaceEncoder(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    640, 480, 1_000_000, 24
                )
            }
        )

        for ((index, strategy) in strategies.withIndex()) {
            try {
                Log.d(TAG, "Trying surface encoder strategy ${index + 1}")
                return strategy()
            } catch (e: Exception) {
                Log.w(TAG, "Surface encoder strategy ${index + 1} failed: ${e.message}")
                if (index == strategies.size - 1) {
                    throw e // Re-throw the last exception
                }
            }
        }

        throw RuntimeException("All surface encoder strategies failed")
    }

    

    private fun createBasicSurfaceEncoder(
        mime: String,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int
    ): MediaCodec {
        val encoder = MediaCodec.createEncoderByType(mime)

        val format = MediaFormat.createVideoFormat(mime, width, height)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return encoder
        } catch (e: MediaCodec.CodecException) {
            Log.w(TAG, "MediaCodec configuration failed with CodecException: ${300} - ${e.message}")
            encoder.release()

            // Try with alternative color format
            val fallbackEncoder = MediaCodec.createEncoderByType(mime)
            val fallbackFormat = MediaFormat.createVideoFormat(mime, width, height)
            fallbackFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            fallbackFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            fallbackFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // Longer GOP
            // Try without explicit color format to let the encoder choose

            fallbackEncoder.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return fallbackEncoder
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure surface encoder: ${e.message}")
            encoder.release()
            throw e
        }
    }

    /**
     * Buffer-based transcoding as fallback
     */
    private fun transcodeWithBuffers(
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        resolution: Int,
        bitrate: Int,
        fps: Int,
        codec: String,
        hdr: Boolean,
        crop: Map<String, Any>?
    ) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            // Setup extractor
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val videoTrackIndex = findVideoTrack(extractor)
            extractor.selectTrack(videoTrackIndex)
            val inputFormat = extractor.getTrackFormat(videoTrackIndex)
            
            // Calculate total duration for progress calculation
            val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION)
            Log.d(TAG, "Buffer video duration: ${durationUs}µs")

            // Calculate output dimensions
            val outputDimensions = calculateOutputDimensions(inputFormat, width, height, resolution)
            val outputWidth = outputDimensions.first
            val outputHeight = outputDimensions.second

            Log.d(TAG, "Buffer transcode: ${outputWidth}x${outputHeight}")

            // Create decoder
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Create encoder for buffer-based encoding
            val outputMime = getMimeTypeForCodec(codec)
            encoder = createBasicBufferEncoder(
                outputMime,
                outputWidth,
                outputHeight,
                bitrate,
                fps,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            encoder?.start()

            // Setup muxer
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Process video with audio using buffers
            if (encoder != null) {
                processBufferVideoWithAudio(
                    extractor,
                    decoder,
                    encoder,
                    muxer,
                    inputPath,
                    outputPath,
                    durationUs
                )
            }


        } finally {
            cleanupResources(decoder, encoder, muxer, extractor, null)
        }
    }

    // Surface video processing with audio
    private fun processSurfaceVideoWithAudio(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        inputPath: String,
        outputPath: String,
        durationUs: Long
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEOS = false
        var outputEOS = false
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var frameCount = 0
        var muxerStarted = false
        var lastProgressUpdate = 0L

        // Check if audio track exists
        val audioTrackIdx = findAudioTrack(extractor)
        var audioFormat: MediaFormat? = null
        if (audioTrackIdx >= 0) {
            audioFormat = extractor.getTrackFormat(audioTrackIdx)
            Log.d(TAG, "Found audio track: $audioFormat")
        }

        while (!outputEOS) {
            // Feed input to decoder
            if (!inputEOS) {
                val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEOS = true
                    } else {
                        val presentationTime = extractor.sampleTime
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            sampleSize,
                            presentationTime,
                            0
                        )
                        extractor.advance()
                        frameCount++
                        
                        // Calculate and send progress based on current time vs total duration
                        if (durationUs > 0) {
                            val currentProgress = (presentationTime.toDouble() / durationUs.toDouble()).coerceIn(0.0, 1.0)
                            // Send progress updates every 1% to avoid too many updates
                            val progressPercent = (currentProgress * 100).toInt()
                            if (progressPercent > lastProgressUpdate) {
                                sendProgress(currentProgress)
                                lastProgressUpdate = progressPercent.toLong()
                            }
                        }
                    }
                }
            }

            // Process decoder output (renders to encoder surface)
            val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (decoderStatus >= 0) {
                val render = bufferInfo.size > 0
                decoder.releaseOutputBuffer(decoderStatus, render)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encoder.signalEndOfInputStream()
                }
            }

            // Process encoder output
            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrackIndex = muxer.addTrack(encoder.outputFormat)

                    // Add audio track if it exists
                    if (audioFormat != null) {
                        audioTrackIndex = muxer.addTrack(audioFormat)
                    }

                    muxer.start()
                    muxerStarted = true
                    Log.d(
                        TAG,
                        "Muxer started with video track: $videoTrackIndex, audio track: $audioTrackIndex"
                    )
                }

                encoderStatus >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                    if (bufferInfo.size > 0 && videoTrackIndex >= 0 && muxerStarted) {
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(encoderStatus, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEOS = true
                    }
                }
            }

            // Update progress - removed hardcoded sendProgress(0.5)
        }

        // Copy audio track directly if it exists and muxer is started
        if (audioTrackIdx >= 0 && audioTrackIndex >= 0 && muxerStarted) {
            Log.d(TAG, "Starting audio track copy")
            copyAudioTrackSafe(inputPath, muxer, audioTrackIndex)
            sendProgress(0.9)
        }
    }

    // Buffer video processing with audio
    private fun processBufferVideoWithAudio(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        inputPath: String,
        outputPath: String,
        durationUs: Long
    ) {
        val decoderBufferInfo = MediaCodec.BufferInfo()
        val encoderBufferInfo = MediaCodec.BufferInfo()
        var inputEOS = false
        var outputEOS = false
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var frameCount = 0
        var muxerStarted = false
        var lastProgressUpdate = 0L

        // Check if audio track exists
        val audioTrackIdx = findAudioTrack(extractor)
        var audioFormat: MediaFormat? = null
        if (audioTrackIdx >= 0) {
            audioFormat = extractor.getTrackFormat(audioTrackIdx)
            Log.d(TAG, "Found audio track: $audioFormat")
        }

        while (!outputEOS) {
            // Feed input to decoder
            if (!inputEOS) {
                val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEOS = true
                    } else {
                        val presentationTime = extractor.sampleTime
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            sampleSize,
                            presentationTime,
                            0
                        )
                        extractor.advance()
                        frameCount++
                        
                        // Calculate and send progress based on current time vs total duration
                        if (durationUs > 0) {
                            val currentProgress = (presentationTime.toDouble() / durationUs.toDouble()).coerceIn(0.0, 1.0)
                            // Send progress updates every 1% to avoid too many updates
                            val progressPercent = (currentProgress * 100).toInt()
                            if (progressPercent > lastProgressUpdate) {
                                sendProgress(currentProgress)
                                lastProgressUpdate = progressPercent.toLong()
                            }
                        }
                    }
                }
            }

            // Process decoder output
            val decoderStatus = decoder.dequeueOutputBuffer(decoderBufferInfo, TIMEOUT_US)
            if (decoderStatus >= 0) {
                val decodedBuffer = decoder.getOutputBuffer(decoderStatus)

                if (decodedBuffer != null && decoderBufferInfo.size > 0) {
                    // Simple frame copy to encoder
                    val encoderInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (encoderInputIndex >= 0) {
                        val encoderInputBuffer = encoder.getInputBuffer(encoderInputIndex)!!
                        val dataSize =
                            minOf(decodedBuffer.remaining(), encoderInputBuffer.remaining())
                        val tempArray = ByteArray(dataSize)
                        decodedBuffer.get(tempArray, 0, dataSize)
                        encoderInputBuffer.put(tempArray)

                        encoder.queueInputBuffer(
                            encoderInputIndex, 0, dataSize,
                            decoderBufferInfo.presentationTimeUs,
                            decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }

                decoder.releaseOutputBuffer(decoderStatus, false)

                if ((decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    val encoderInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (encoderInputIndex >= 0) {
                        encoder.queueInputBuffer(
                            encoderInputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }
            }

            // Process encoder output
            val encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_US)
            when {
                encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrackIndex = muxer.addTrack(encoder.outputFormat)

                    // Add audio track if it exists
                    if (audioFormat != null) {
                        audioTrackIndex = muxer.addTrack(audioFormat)
                    }

                    muxer.start()
                    muxerStarted = true
                    Log.d(
                        TAG,
                        "Buffer muxer started with video track: $videoTrackIndex, audio track: $audioTrackIndex"
                    )
                }

                encoderStatus >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                    if (encoderBufferInfo.size > 0 && videoTrackIndex >= 0 && muxerStarted) {
                        muxer.writeSampleData(videoTrackIndex, encodedData, encoderBufferInfo)
                    }
                    encoder.releaseOutputBuffer(encoderStatus, false)

                    if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEOS = true
                    }
                }
            }

            // Update progress - removed hardcoded sendProgress(0.7)
        }

        // Copy audio track directly if it exists and muxer is started
        if (audioTrackIdx >= 0 && audioTrackIndex >= 0 && muxerStarted) {
            Log.d(TAG, "Starting audio track copy")
            copyAudioTrackSafe(inputPath, muxer, audioTrackIndex)
        }
    }

    /**
     * Safely copies audio track directly from input to output without re-encoding
     */
    private fun copyAudioTrackSafe(inputPath: String, muxer: MediaMuxer, audioTrackIndex: Int) {
        var audioExtractor: MediaExtractor? = null

        try {
            audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(inputPath)

            val audioTrackIdx = findAudioTrack(audioExtractor)
            if (audioTrackIdx >= 0) {
                audioExtractor.selectTrack(audioTrackIdx)

                val bufferInfo = MediaCodec.BufferInfo()
                val buffer = ByteBuffer.allocate(64 * 1024) // 64KB buffer for better compatibility

                var samplesWritten = 0
                while (true) {
                    buffer.clear()
                    val sampleSize = audioExtractor.readSampleData(buffer, 0)

                    if (sampleSize < 0) {
                        Log.d(TAG, "Audio copy completed - wrote $samplesWritten samples")
                        break // End of audio stream
                    }

                    val presentationTime = audioExtractor.sampleTime
                    if (presentationTime < 0) {
                        Log.w(TAG, "Invalid audio timestamp, skipping sample")
                        audioExtractor.advance()
                        continue
                    }

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = presentationTime
                    bufferInfo.flags =
                        if (audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }

                    try {
                        muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                        samplesWritten++
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to write audio sample: ${e.message}")
                        break
                    }

                    audioExtractor.advance()
                }

                Log.d(TAG, "Audio track copied successfully")
            } else {
                Log.w(TAG, "No audio track found in input file")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy audio track: ${e.message}")
        } finally {
            audioExtractor?.release()
        }
    }

        private fun createBasicBufferEncoder(mime: String, width: Int, height: Int, bitrate: Int, fps: Int, colorFormat: Int): MediaCodec {
            Thread.sleep(5000)

            val encoder = MediaCodec.createEncoderByType(mime)

            val format = MediaFormat.createVideoFormat(mime, width, height)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 1)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)

            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return encoder
            } catch (e: MediaCodec.CodecException) {
                Log.w(com.schmidtGabriel.native_media_converter.VideoConverter.TAG, "Buffer encoder configuration failed with CodecException: ${300} - ${e.message}")
                encoder.release()

                // Try with minimal configuration
                val fallbackEncoder = MediaCodec.createEncoderByType(mime)
                val fallbackFormat = MediaFormat.createVideoFormat(mime, width, height)
                fallbackFormat.setInteger(MediaFormat.KEY_BIT_RATE, minOf(bitrate, 2_000_000))
                fallbackFormat.setInteger(MediaFormat.KEY_FRAME_RATE, minOf(fps, 30))
                fallbackFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // Don't set color format, let encoder choose

                fallbackEncoder.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return fallbackEncoder
            } catch (e: Exception) {
                Log.e(com.schmidtGabriel.native_media_converter.VideoConverter.TAG, "Failed to configure buffer encoder: ${e.message}")
                encoder.release()
                throw e
            }
        }

    // Helper functions
    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) {
                return i
            }
        }
        throw RuntimeException("No video track found")
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1 // No audio track found
    }

    private fun calculateOutputDimensions(
        inputFormat: MediaFormat,
        width: Int,
        height: Int,
        resolution: Int
    ): Pair<Int, Int> {
        val inputWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
        val inputHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val aspectRatio = inputWidth.toFloat() / inputHeight.toFloat()

//        return when (resolution) {
//            480 ->  Pair(854, 480)
//            720 ->  Pair(1280, 720)
//            1080 ->  Pair(1920, 1080)
//            else -> {
//                val finalWidth = if (width % 2 == 0) width else width - 1
//                val finalHeight = if (height % 2 == 0) height else height - 1
//                Pair(finalWidth, finalHeight)
//            }
//        }

        return when (resolution) {
            480 -> if (aspectRatio > 1) Pair(854, 480) else Pair(480, 854)
            720 -> if (aspectRatio > 1) Pair(1280, 720) else Pair(720, 1280)
            1080 -> if (aspectRatio > 1) Pair(1920, 1080) else Pair(1080, 1920)
            else -> {
                val finalWidth = if (width % 2 == 0) width else width - 1
                val finalHeight = if (height % 2 == 0) height else height - 1
                Pair(finalWidth, finalHeight)
            }
        }
    }

    private fun getMimeTypeForCodec(codec: String): String {
        val requestedMime = when (codec.lowercase()) {
            "hevc", "h265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            "av1" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaFormat.MIMETYPE_VIDEO_AV1
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }
        
        // Fallback to H.264 if requested codec is not supported
        return if (isCodecSupported(requestedMime)) {
            requestedMime
        } else {
            Log.w(TAG, "Codec $codec not supported, falling back to H.264")
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
    }

    private fun sendProgress(progress: Double) {
        try {
            eventSinkProvider?.invoke()?.success(progress)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send progress update", e)
        }
    }

    private fun cleanupResources(
        decoder: MediaCodec?, encoder: MediaCodec?, muxer: MediaMuxer?,
        extractor: MediaExtractor?, surface: Surface?
    ) {
          // Clean up in reverse order of creation
    try {
        surface?.release()
    } catch (e: Exception) {
        Log.w(TAG, "Surface cleanup error", e)
    }
    
    try {
        muxer?.stop()
    } catch (e: Exception) {
        Log.w(TAG, "Muxer stop error", e)
    }
    try {
        muxer?.release()
    } catch (e: Exception) {
        Log.w(TAG, "Muxer release error", e)
    }
    
    try {
        encoder?.stop()
    } catch (e: Exception) {
        Log.w(TAG, "Encoder stop error", e)
    }
    try {
        encoder?.release()
    } catch (e: Exception) {
        Log.w(TAG, "Encoder release error", e)
    }
    
    try {
        decoder?.stop()
    } catch (e: Exception) {
        Log.w(TAG, "Decoder stop error", e)
    }
    try {
        decoder?.release()
    } catch (e: Exception) {
        Log.w(TAG, "Decoder release error", e)
    }
    
    try {
        extractor?.release()
    } catch (e: Exception) {
        Log.w(TAG, "Extractor cleanup error", e)
    }
    
    // Give system time to clean up resources
    Thread.sleep(100)
    }
}

