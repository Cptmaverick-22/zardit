package io.github.cptmaverick_22.zardit.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object InstagramAuth {
    private const val KEY_ALIAS = "zardit_ig_session_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val SESSION_FILE = "ig_session.bin"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    private val allowedCookieNames = setOf("sessionid", "csrftoken", "ds_user_id")

    fun hasSession(context: Context): Boolean =
        File(context.applicationContext.filesDir, SESSION_FILE).isFile

    fun extractMinimalCookies(rawCookieHeader: String?): Map<String, String> =
        io.github.cptmaverick_22.zardit.downloader.ZarditParser.extractMinimalInstagramCookies(rawCookieHeader)

    fun saveCookies(context: Context, cookies: Map<String, String>) {
        if ("sessionid" !in cookies) return
        val netscape = buildString {
            appendLine("# Netscape HTTP Cookie File")
            val expiry = (System.currentTimeMillis() / 1000L) + 86400L * 90L
            cookies.forEach { (name, value) ->
                if (name in allowedCookieNames && value.isNotBlank()) {
                    appendLine(".instagram.com\tTRUE\t/\tTRUE\t$expiry\t$name\t$value")
                }
            }
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(netscape.toByteArray(Charsets.UTF_8))

        val outFile = File(context.applicationContext.filesDir, SESSION_FILE)
        outFile.outputStream().use { out ->
            out.write(iv)
            out.write(encrypted)
        }
    }

    fun getCookieMap(context: Context): Map<String, String> {
        val file = File(context.applicationContext.filesDir, SESSION_FILE)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val allBytes = file.readBytes()
            if (allBytes.size <= GCM_IV_LEN) return emptyMap()
            val iv = allBytes.copyOfRange(0, GCM_IV_LEN)
            val ciphertext = allBytes.copyOfRange(GCM_IV_LEN, allBytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                val map = linkedMapOf<String, String>()
                plaintext.toString(Charsets.UTF_8).lineSequence().forEach { line ->
                    if (line.startsWith("#") || line.isBlank()) return@forEach
                    val cols = line.split("\t")
                    if (cols.size >= 7 && cols[5] in allowedCookieNames && cols[6].isNotBlank()) {
                        map[cols[5]] = cols[6]
                    }
                }
                map
            } finally {
                plaintext.fill(0)
            }
        }.getOrDefault(emptyMap())
    }

    fun getCookieHeader(context: Context): String? {
        val map = getCookieMap(context)
        if ("sessionid" !in map) return null
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun <T> withTempCookieFile(context: Context, block: (File) -> T): T {
        val file = File(context.applicationContext.filesDir, SESSION_FILE)
        require(file.isFile) { "No Instagram session saved." }
        val allBytes = file.readBytes()
        require(allBytes.size > GCM_IV_LEN) { "Corrupted session data." }

        val iv = allBytes.copyOfRange(0, GCM_IV_LEN)
        val ciphertext = allBytes.copyOfRange(GCM_IV_LEN, allBytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)

        val temp = File(context.applicationContext.cacheDir, "ig_${UUID.randomUUID()}.txt")
        return try {
            temp.writeBytes(plaintext)
            block(temp)
        } finally {
            plaintext.fill(0)
            if (temp.exists()) {
                runCatching { temp.writeBytes(ByteArray(temp.length().toInt().coerceAtMost(4096))) }
                temp.delete()
            }
        }
    }

    fun clearSession(context: Context) {
        runCatching { File(context.applicationContext.filesDir, SESSION_FILE).delete() }
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }
}
