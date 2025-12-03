package com.schmidtGabriel.native_media_converter

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * VideoMetadataExtractor - Utility class for extracting video metadata including rotation
 */
object VideoMetadataExtractor {
    private const val TAG = "VideoMetadataExtractor"

    /**
     * Get video rotation from file usiVideoMetadataExtractorng MediaMetadataRetriever
     * Returns rotation in degrees (0, 90, 180, 270)
     */
    fun getVideoRotation(filePath: String): Int {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            
            // Get rotation metadata
            val rotationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotation = rotationString?.toIntOrNull() ?: 0
            
            Log.d(TAG, "Video rotation for $filePath: $rotation degrees")
            rotation
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract video rotation: ${e.message}")
            0 // Default to no rotation
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release MediaMetadataRetriever: ${e.message}")
            }
        }
    }

    /**
     * Get comprehensive video metadata including rotation, dimensions, duration, etc.
     */
    fun getVideoMetadata(filePath: String): VideoMetadata? {
        var retriever: MediaMetadataRetriever? = null
        var extractor: MediaExtractor? = null
        
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            
            extractor = MediaExtractor()
            extractor.setDataSource(filePath)
            
            // Extract basic metadata using MediaMetadataRetriever
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 0f
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            
            // Get more detailed info from MediaExtractor
            var actualWidth = width
            var actualHeight = height
            var videoCodec = ""
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                
                if (mime?.startsWith("video/") == true) {
                    actualWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    actualHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    videoCodec = mime
                    break
                }
            }
            
            // Adjust dimensions based on rotation
            val (displayWidth, displayHeight) = when (rotation) {
                90, 270 -> Pair(actualHeight, actualWidth) // Swap for portrait rotations
                else -> Pair(actualWidth, actualHeight)
            }    
            
            
            VideoMetadata(
                width = actualWidth,
                height = actualHeight,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                duration = duration,
                rotation = rotation,
                bitrate = bitrate,
                frameRate = frameRate,
                mimeType = mimeType,
                videoCodec = videoCodec,
                filePath = filePath,
                fileSize = File(filePath).length()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract video metadata: ${e.message}", e)
            null
        } finally {
            try {
                retriever?.release()
                extractor?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release resources: ${e.message}")
            }
        }
    }

    /**
     * Check if video needs rotation correction
     */
    fun needsRotationCorrection(filePath: String): Boolean {
        val rotation = getVideoRotation(filePath)
        return rotation != 0
    }

    /**
     * Get rotation transformation matrix type for the given rotation
     */
    fun getRotationTransformationType(rotation: Int): String {
        return when (rotation) {
            90 -> "ROTATE_90"
            180 -> "ROTATE_180"
            270 -> "ROTATE_270"
            else -> "NONE"
        }
    }
}

/**
 * Data class to hold comprehensive video metadata
 */
data class VideoMetadata(
    val width: Int,
    val height: Int,
    val displayWidth: Int,  // Width after considering rotation
    val displayHeight: Int, // Height after considering rotation
    val duration: Long,     // Duration in milliseconds
    val rotation: Int,      // Rotation in degrees (0, 90, 180, 270)
    val bitrate: Int,       // Bitrate in bps
    val frameRate: Float,   // Frame rate in fps
    val mimeType: String,   // MIME type from metadata
    val videoCodec: String, // Video codec from track format
    val filePath: String,   // Original file path
    val fileSize: Long      // File size in bytes
) {
    /**
     * Check if this video is rotated (needs rotation correction)
     */
    fun isRotated(): Boolean = rotation != 0
    
    /**
     * Check if this video is in portrait orientation
     * A video is portrait if:
     * 1. It has 90 or 270 degree rotation (needs rotation), OR
     * 2. After considering rotation, height > width
     */
    fun isPortrait(): Boolean {
      
        // Otherwise check if height > width (after rotation adjustment)
        Log.d("VideoMetadata", "isPortrait check: displayWidth=$displayWidth, displayHeight=$displayHeight")
        return displayHeight > displayWidth
    }
    
    /**
     * Get aspect ratio (width:height) after considering rotation
     */
    fun getAspectRatio(): Float = displayWidth.toFloat() / displayHeight.toFloat()
    
    /**
     * Get duration in a human-readable format
     */
    fun getDurationFormatted(): String {
        val seconds = duration / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
            else -> String.format("%02d:%02d", minutes, seconds % 60)
        }
    }
    
    /**
     * Get file size in a human-readable format
     */
    fun getFileSizeFormatted(): String {
        val kb = fileSize / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            else -> String.format("%.2f KB", kb)
        }
    }
}
