package com.autoglm.assistant.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * 全局图片池管理器
 * 支持内存 LRU 缓存和本地持久化缓存
 * 参考 app-operit 的 ImagePoolManager 实现
 */
object ImagePoolManager {
    private const val TAG = "ImagePool"

    // 可配置的池子大小限制
    var maxPoolSize = 10
        set(value) {
            if (value > 0) {
                field = value
                Logger.d(TAG, "Pool size limit updated to: $value")
            }
        }

    // 本地缓存目录（需要在使用前初始化）
    private var cacheDir: File? = null

    /**
     * 图片数据类
     * @param base64 Base64 编码的图片数据
     * @param mimeType MIME 类型（如 image/jpeg）
     * @param width 图片宽度
     * @param height 图片高度
     */
    data class ImageData(
        val base64: String,
        val mimeType: String,
        val width: Int = 0,
        val height: Int = 0
    )

    /**
     * 初始化图片池，设置本地缓存目录
     * @param cacheDirPath 本地缓存目录路径
     */
    fun initialize(cacheDirPath: File) {
        cacheDir = File(cacheDirPath, "image_pool")
        if (!cacheDir!!.exists()) {
            cacheDir!!.mkdirs()
            Logger.d(TAG, "Created image cache directory: ${cacheDir!!.absolutePath}")
        }
        loadFromDisk()
    }

    // LRU 缓存：LinkedHashMap with accessOrder=true
    private val imagePool = object : LinkedHashMap<String, ImageData>(
        16,
        0.75f,
        true // accessOrder = true 表示按访问顺序排序
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ImageData>?): Boolean {
            val shouldRemove = size > maxPoolSize
            if (shouldRemove && eldest != null) {
                Logger.d(TAG, "Pool full, removing oldest image: ${eldest.key}")
                deleteFromDisk(eldest.key)
            }
            return shouldRemove
        }
    }

    /**
     * 添加 Bitmap 到池子
     * @param bitmap 图片 Bitmap
     * @param quality JPEG 压缩质量（0-100）
     * @return 图片 ID（UUID 格式），如果失败返回 null
     */
    @Synchronized
    fun addBitmap(bitmap: Bitmap, quality: Int = 60): String? {
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val id = UUID.randomUUID().toString()
            val imageData = ImageData(
                base64 = base64,
                mimeType = "image/jpeg",
                width = bitmap.width,
                height = bitmap.height
            )
            imagePool[id] = imageData
            saveToDisk(id, imageData)

            Logger.d(TAG, "Added bitmap to pool: $id, size: ${bitmap.width}x${bitmap.height}")
            id
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to add bitmap", e)
            null
        }
    }

    /**
     * 从文件路径添加图片到池子
     * @param filePath 图片文件路径
     * @return 图片 ID，如果失败返回 null
     */
    @Synchronized
    fun addImage(filePath: String): String? {
        try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) {
                Logger.e(TAG, "File does not exist: $filePath")
                return null
            }

            val mimeType = getMimeTypeFromFile(file) ?: run {
                Logger.e(TAG, "Unknown image format: $filePath")
                return null
            }

            val fileBytes = FileInputStream(file).use { it.readBytes() }

            // 转换为 JPEG 格式以减小体积
            val (finalBytes, finalMimeType) = if (mimeType != "image/jpeg") {
                val bitmap = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size)
                    ?: return null
                ByteArrayOutputStream().use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                    val jpegBytes = outputStream.toByteArray()
                    bitmap.recycle()
                    Pair(jpegBytes, "image/jpeg")
                }
            } else {
                Pair(fileBytes, mimeType)
            }

            val base64 = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
            val id = UUID.randomUUID().toString()
            val imageData = ImageData(base64, finalMimeType)
            imagePool[id] = imageData
            saveToDisk(id, imageData)

            Logger.d(TAG, "Added image to pool: $id, MIME: $finalMimeType")
            return id
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to add image: $filePath", e)
            return null
        }
    }

    /**
     * 从 Base64 数据添加图片到池子
     */
    @Synchronized
    fun addImageFromBase64(base64: String, mimeType: String): String? {
        return try {
            val id = UUID.randomUUID().toString()
            val imageData = ImageData(base64, mimeType)
            imagePool[id] = imageData
            saveToDisk(id, imageData)
            Logger.d(TAG, "Added base64 image to pool: $id")
            id
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to add base64 image", e)
            null
        }
    }

    /**
     * 获取图片数据
     * @param id 图片 ID
     * @return ImageData，不存在返回 null
     */
    @Synchronized
    fun getImage(id: String): ImageData? {
        // 先从内存缓存获取
        var imageData = imagePool[id]
        if (imageData != null) {
            return imageData
        }

        // 如果内存中没有，尝试从磁盘加载
        imageData = loadFromDisk(id)
        if (imageData != null) {
            Logger.d(TAG, "Loaded image from disk cache: $id")
            imagePool[id] = imageData
            return imageData
        }

        Logger.w(TAG, "Image not found: $id")
        return null
    }

    /**
     * 移除图片
     */
    @Synchronized
    fun removeImage(id: String) {
        if (imagePool.remove(id) != null) {
            Logger.d(TAG, "Removed image from pool: $id")
        }
        deleteFromDisk(id)
    }

    /**
     * 清空所有图片
     */
    @Synchronized
    fun clear() {
        imagePool.clear()
        clearDiskCache()
        Logger.d(TAG, "Cleared image pool and disk cache")
    }

    /**
     * 获取当前池子大小
     */
    @Synchronized
    fun size(): Int = imagePool.size

    // ==================== 私有方法 ====================

    private fun getMimeTypeFromFile(file: File): String? {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> {
                // 尝试通过文件头判断
                try {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    options.outMimeType
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun saveToDisk(id: String, imageData: ImageData) {
        val dir = cacheDir ?: return

        try {
            val dataFile = File(dir, "$id.dat")
            val metaFile = File(dir, "$id.meta")

            FileOutputStream(dataFile).use { it.write(imageData.base64.toByteArray()) }
            FileOutputStream(metaFile).use {
                it.write("${imageData.mimeType}\n${imageData.width}\n${imageData.height}".toByteArray())
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save image to disk: $id", e)
        }
    }

    private fun loadFromDisk(id: String): ImageData? {
        val dir = cacheDir ?: return null

        try {
            val dataFile = File(dir, "$id.dat")
            val metaFile = File(dir, "$id.meta")

            if (!dataFile.exists() || !metaFile.exists()) {
                return null
            }

            val base64 = FileInputStream(dataFile).use { String(it.readBytes()) }
            val meta = FileInputStream(metaFile).use { String(it.readBytes()) }.split("\n")

            return ImageData(
                base64 = base64,
                mimeType = meta.getOrElse(0) { "image/jpeg" },
                width = meta.getOrElse(1) { "0" }.toIntOrNull() ?: 0,
                height = meta.getOrElse(2) { "0" }.toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load image from disk: $id", e)
            return null
        }
    }

    private fun loadFromDisk() {
        val dir = cacheDir ?: return
        if (!dir.exists()) return

        try {
            val files = dir.listFiles { file -> file.extension == "dat" } ?: return
            var loadedCount = 0

            for (file in files.take(maxPoolSize)) {
                val id = file.nameWithoutExtension
                val imageData = loadFromDisk(id)
                if (imageData != null) {
                    imagePool[id] = imageData
                    loadedCount++
                }
            }

            if (loadedCount > 0) {
                Logger.d(TAG, "Loaded $loadedCount images from disk cache")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load images from disk", e)
        }
    }

    private fun deleteFromDisk(id: String) {
        val dir = cacheDir ?: return

        try {
            File(dir, "$id.dat").delete()
            File(dir, "$id.meta").delete()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete image from disk: $id", e)
        }
    }

    private fun clearDiskCache() {
        val dir = cacheDir ?: return
        if (!dir.exists()) return

        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to clear disk cache", e)
        }
    }
}
