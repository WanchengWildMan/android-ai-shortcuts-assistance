package com.autoglm.assistant.core.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.autoglm.assistant.util.ImageUtils
import com.autoglm.assistant.util.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

data class Screenshot(
    val base64Data: String,
    val width: Int,
    val height: Int,
    val isSensitive: Boolean = false
)

class ScreenCapture(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val displayMetrics: DisplayMetrics by lazy {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 使用 getRealMetrics 获取真实屏幕尺寸（包括导航栏）
        DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
    }

    val screenWidth: Int get() = displayMetrics.widthPixels
    val screenHeight: Int get() = displayMetrics.heightPixels
    val screenDensity: Int get() = displayMetrics.densityDpi

    companion object {
        const val REQUEST_CODE_SCREEN_CAPTURE = 1001
        private const val SCREENSHOT_FILENAME = "screenshot.png"
        // 图片最大尺寸，超过则缩放（减少上传数据量）
        // 模型坐标系是 1000x1000，截图宽度缩放到 485
        private const val MAX_IMAGE_WIDTH = 485
        private const val MAX_IMAGE_HEIGHT = 1080
    }

    fun getMediaProjectionIntent(activity: Activity): Intent {
        val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return projectionManager.createScreenCaptureIntent()
    }

    fun initMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            Handler(Looper.getMainLooper())
        )
    }

    suspend fun capture(): Screenshot? = withContext(Dispatchers.IO) {
        // Try MediaProjection first if available
        if (mediaProjection != null && imageReader != null) {
            var bitmap = captureViaMediaProjection()
            if (bitmap != null) {
                // 缩放图片以减少数据量
                val resized = ImageUtils.resizeBitmap(bitmap, MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT)
                if (resized !== bitmap) {
                    bitmap.recycle()
                    bitmap = resized
                }
                val base64 = ImageUtils.bitmapToBase64(bitmap)
                val width = bitmap.width
                val height = bitmap.height
                bitmap.recycle()
                if (base64 != null) {
                    return@withContext Screenshot(
                        base64Data = base64,
                        width = width,
                        height = height
                    )
                }
            }
        }

        // Fallback to shell screencap (requires root/shell access)
        return@withContext captureViaShell()
    }

    private suspend fun captureViaMediaProjection(): Bitmap? = suspendCancellableCoroutine { continuation ->
        val reader = imageReader ?: run {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            try {
                val image: Image? = reader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    image.close()
                    continuation.resume(bitmap)
                } else {
                    continuation.resume(null)
                }
            } catch (e: Exception) {
                continuation.resume(null)
            }
        }, 100)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to actual screen size if needed
            if (bitmap.width != screenWidth || bitmap.height != screenHeight) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun captureViaShell(): Screenshot? {
        val cacheDir = context.cacheDir
        val screenshotFile = File(cacheDir, SCREENSHOT_FILENAME)
        val screenshotPath = screenshotFile.absolutePath

        val result = ShellExecutor.screenshot(screenshotPath)
        if (!result) {
            // Return black placeholder for sensitive screens
            val blackBitmap = ImageUtils.createBlackBitmap(MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT)
            val base64 = ImageUtils.bitmapToBase64(blackBitmap)
            blackBitmap.recycle()
            return if (base64 != null) {
                Screenshot(
                    base64Data = base64,
                    width = MAX_IMAGE_WIDTH,
                    height = MAX_IMAGE_HEIGHT,
                    isSensitive = true
                )
            } else null
        }

        var bitmap = ImageUtils.loadBitmapFromFile(screenshotPath)
        if (bitmap == null) {
            return null
        }

        // 缩放图片以减少数据量
        val resized = ImageUtils.resizeBitmap(bitmap, MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT)
        if (resized !== bitmap) {
            bitmap.recycle()
            bitmap = resized
        }

        val base64 = ImageUtils.bitmapToBase64(bitmap)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()

        // Clean up screenshot file
        screenshotFile.delete()

        return if (base64 != null) {
            Screenshot(
                base64Data = base64,
                width = width,
                height = height
            )
        } else null
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}
