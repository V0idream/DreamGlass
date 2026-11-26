package com.coloros16.liquidglass.diagnostics

import android.content.Context
import com.coloros16.liquidglass.root.RootGrant
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Only the original, individually audited APK files are in this catalog.
 * Identical package/version pairs can have more than one distinct build. */
internal object ComponentApkCatalog {
    data class Sample(val packageName: String, val version: String, val code: Long, val sha256: String)
    data class Digest(val value: String, val fromRoot: Boolean)
    enum class Match { EXACT, SPLITS_UNVERIFIED, SAME_VERSION_DIFFERENT_APK, UNLISTED, UNREADABLE }

    private val launcher = Troubleshooting.LAUNCHER
    private val systemUi = Troubleshooting.SYSTEM_UI
    internal val samples = listOf(
        Sample(launcher, "16.3.22", 160030022, "91272d4a780b4c35dc527fd0a599541fb0751089d782be956d072c353c8542b1"),
        Sample(launcher, "16.4.6", 160040006, "0a688e321232f7c7780da7654b15b20093f3d856a129f5bdab2469e10b4619b6"),
        Sample(launcher, "17.3.12", 170030012, "af89139f18857f5c9e130cd492f08dc1a3e26b131aec74490432b2c590ac972e"),
        Sample(launcher, "17.5.5", 170050005, "ae500e951eb162f44805fba1180491dfecfac7f3945fa932e5d68b9a8b3394cc"),
        Sample(launcher, "17.5.5", 170050005, "c1b3af973783ec014f7e32448b6087e143343d176051927ccd30e50d73c6db04"),
        Sample(systemUi, "17.99.02", 179902, "a06bf8e0046a5e6a19347e09064e6ce8f79e0e963c78c8ce65f0fbf56d770e62"),
        Sample(systemUi, "17.99.02", 179902, "e74869175495f8546f3fe052891d80d880b856356487f722b3ed35b3639f92a5"),
        Sample(systemUi, "17.99.02", 179902, "758bf0f1ae061bb907d928cc719af1a6f3389b7cdc93f0e96a48b429147c2e4c"),
    )

    fun match(packageName: String, version: String?, code: Long, digest: String?, splitCount: Int): Match {
        if (digest == null) return Match.UNREADABLE
        val versions = samples.filter { it.packageName == packageName && it.version == version && it.code == code }
        return when {
            versions.any { it.sha256.equals(digest, ignoreCase = true) } ->
                if (splitCount == 0) Match.EXACT else Match.SPLITS_UNVERIFIED
            versions.isNotEmpty() -> Match.SAME_VERSION_DIFFERENT_APK
            else -> Match.UNLISTED
        }
    }

    private val hex = "0123456789abcdef"

    internal fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val length = input.read(buffer)
            if (length < 0) break
            digest.update(buffer, 0, length)
        }
        return buildString(64) {
            for (byte in digest.digest()) {
                val value = byte.toInt() and 255
                append(hex[value ushr 4])
                append(hex[value and 15])
            }
        }
    }

    fun read(path: String?, useRoot: Boolean): Digest? {
        if (path.isNullOrBlank()) return null
        runCatching { File(path).inputStream().use(::sha256) }.getOrNull()?.let { return Digest(it, false) }
        if (!useRoot || !path.startsWith('/') || path.contains('\u0000')) return null
        // The path comes from PackageManager. Quote it even when the OS supplies it.
        val quoted = "'" + path.replace("'", "'\\''") + "'"
        val command = "[ \"\$(id -u)\" = 0 ] && sha256sum < $quoted"
        val result = rootRead(command, 12) ?: return null
        val hash = result.substringBefore(' ')
        return if (hash.matches(Regex("[a-f0-9]{64}"))) Digest(hash, true) else null
    }

    fun requestRoot(context: Context): Boolean {
        val granted = rootRead("id -u", 45) == "0"
        if (granted) RootGrant.remember(context, true)
        return granted
    }

    private fun rootRead(command: String, timeoutSeconds: Long): String? {
        val process = runCatching {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        return try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS) || process.exitValue() != 0) null
            else process.inputStream.use { String(it.readNBytes(257), Charsets.US_ASCII).trim() }
        } catch (_: Exception) {
            null
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
