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
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.autoglm.assistant.util.ImageUtils
import com.autoglm.assistant.util.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    
    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any()
    
    private val handlerThread = HandlerThread("ScreenCapture").apply { start() }
    private val handler = Handler(handlerThread.looper)

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
        
        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    image.close()
                    if (bitmap != null) {
                        synchronized(bitmapLock) {
                            latestBitmap?.recycle()
                            latestBitmap = bitmap
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
    }

    suspend fun capture(): Screenshot? = withContext(Dispatchers.IO) {
        // 如果 MediaProjection 可用，优先使用它
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

        // 回退到 shell screencap（需要 root/shell 权限）
        return@withContext captureViaShell()
    }

    private suspend fun captureViaMediaProjection(): Bitmap? = withContext(Dispatchers.IO) {
        // 如果需要，等待第一帧（最多 1 秒）
        if (latestBitmap == null) {
            for (i in 0..10) {
                synchronized(bitmapLock) {
                    if (latestBitmap != null) return@withContext latestBitmap!!.copy(latestBitmap!!.config, false)
                }
                delay(100)
            }
            return@withContext null
        }

        synchronized(bitmapLock) {
            return@withContext latestBitmap?.copy(latestBitmap!!.config, false)
        }
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

            // 如果需要，裁剪到实际屏幕尺寸
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
            // 对于敏感屏幕返回黑色占位图
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

        // 清理截图文件
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
        handlerThread.quitSafely()
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }
}
