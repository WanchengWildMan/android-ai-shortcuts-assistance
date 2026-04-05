package com.autoglm.assistant.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 安全存储工具 — 基于 Android Keystore 的 AES/GCM 加密
 *
 * 业务目的：加密存储锁屏密码等敏感信息，防止其他应用或反编译获取。
 *
 * 安全机制：
 * 1. 密钥存储在 Android Keystore（硬件安全模块，即使 root 也无法导出密钥）
 * 2. 使用 AES/GCM/NoPadding 加密（认证加密，同时保证机密性和完整性）
 * 3. 每次加密生成随机 IV，附在密文前面一起存储
 * 4. 加密数据存储在 MODE_PRIVATE 的 SharedPreferences 中
 */
object SecureStorage {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "autoglm_secure_key"
    private const val PREFS_NAME = "autoglm_secure_prefs"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    /**
     * 加密并存储字符串值
     * @param context 应用上下文
     * @param key SharedPreferences 的键名
     * @param plaintext 要加密的原始文本
     */
    fun putEncrypted(context: Context, key: String, plaintext: String) {
        if (plaintext.isEmpty()) {
            // 空值直接清除
            getPrefs(context).edit().remove(key).apply()
            return
        }
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv  // GCM 模式自动生成随机 IV
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // 存储格式：Base64(IV) + ":" + Base64(密文)
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val encBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        getPrefs(context).edit().putString(key, "$ivBase64:$encBase64").apply()
    }

    /**
     * 读取并解密字符串值
     * @param context 应用上下文
     * @param key SharedPreferences 的键名
     * @return 解密后的原始文本，若不存在或解密失败返回空字符串
     */
    fun getDecrypted(context: Context, key: String): String {
        val stored = getPrefs(context).getString(key, null) ?: return ""
        val parts = stored.split(":")
        if (parts.size != 2) return ""

        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)

            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("SecureStorage", "解密失败: ${e.message}")
            ""
        }
    }

    /**
     * 检查指定 key 是否存在已加密的值
     */
    fun hasValue(context: Context, key: String): Boolean {
        return getPrefs(context).contains(key)
    }

    /**
     * 删除指定 key 的加密值
     */
    fun remove(context: Context, key: String) {
        getPrefs(context).edit().remove(key).apply()
    }

    // ── 内部方法 ──

    /**
     * 获取或创建 Android Keystore 中的 AES 密钥
     * 密钥特性：
     * - 存储在硬件安全模块（TEE/StrongBox），无法导出
     * - 仅允许 GCM 加密/解密操作
     */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        // 若密钥已存在则直接返回
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        // 生成新密钥
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
