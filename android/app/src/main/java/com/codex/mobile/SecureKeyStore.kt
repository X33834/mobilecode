package com.codex.mobile

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 硬件背书密钥对 API Key 做 AES/GCM 加密存储。
 * 密钥不出安全硬件（或 TEE），明文只短暂存在于内存。
 *
 * 存储格式：enc:v1:<base64(iv||ciphertext)>；不带前缀的旧值视为明文（兼容升级）。
 */
object SecureKeyStore {

    private const val TAG = "SecureKeyStore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "mobilecode_api_key_v1"
    private const val PREFIX = "enc:v1:"
    private const val GCM_TAG_BITS = 128

    /** 把明文加密成 enc:v1:… 串；失败时回退明文（绝不因加密问题卡住用户）。 */
    fun encrypt(plain: String): String {
        if (plain.isBlank() || plain.startsWith(PREFIX)) return plain
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val payload = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, payload, 0, iv.size)
            System.arraycopy(ct, 0, payload, iv.size, ct.size)
            PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "加密失败，回退明文: ${e.message}")
            plain
        }
    }

    /** 解密 enc:v1:… 串；失败或明文直接原样返回。 */
    fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank()) return stored
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val payload = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, 12)
            val ct = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "解密失败: ${e.message}")
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}