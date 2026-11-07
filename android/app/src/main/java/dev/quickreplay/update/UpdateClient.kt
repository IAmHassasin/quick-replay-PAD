package dev.quickreplay.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only network calls of the app: the latest GitHub release and its APK. Plain
 * [HttpURLConnection], no token, nothing about the user is sent beyond a normal HTTP request.
 * Call from a background dispatcher.
 */
internal object UpdateClient {
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/IAmHassasin/quick-replay-PAD/releases/latest"
    private const val TIMEOUT_MS = 15_000
    private const val MAX_JSON_BYTES = 1024 * 1024
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    /** The latest release JSON, or null when the repository has no release yet (HTTP 404). */
    fun fetchLatestRelease(userAgent: String): String? {
        val connection = open(LATEST_RELEASE_URL, userAgent).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.use { stream ->
                    val bytes = stream.readNBytesCompat(MAX_JSON_BYTES + 1)
                    if (bytes.size > MAX_JSON_BYTES) throw IOException("Release response is too large")
                    bytes.toString(Charsets.UTF_8)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("GitHub answered HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads [url] to [dest], reporting (bytes done, total or -1). Cancelling the calling
     * coroutine stops the download; the partial file is left for the caller to delete.
     */
    suspend fun download(url: String, dest: File, userAgent: String, onProgress: (Long, Long) -> Unit) {
        val connection = open(url, userAgent)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Download failed: HTTP $code")
            val total = connection.contentLengthLong
            if (total > MAX_APK_BYTES) throw IOException("The APK is larger than 200 MB")
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    onProgress(0, total)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done > MAX_APK_BYTES) throw IOException("The APK is larger than 200 MB")
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, userAgent: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true // github.com redirects release assets to its CDN (https only)
        setRequestProperty("User-Agent", userAgent)
    }

    /** InputStream.readNBytes is API 33+; minSdk is 26. */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
