package dev.quickreplay.update

import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/** A plain `major.minor.patch` version. Pre-release suffixes (`-beta`) are not accepted. */
data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {

    override fun compareTo(other: Semver): Int =
        compareValuesBy(this, other, Semver::major, Semver::minor, Semver::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("^v?(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})$")

        /** Parses "0.2.0" or a tag like "v0.2.0"; null for anything else. */
        fun parse(value: String): Semver? {
            val match = PATTERN.matchEntire(value.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            return Semver(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

/** The release APK asset (`quick-replay-pad-<version>.apk`). */
data class ApkAsset(val name: String, val url: String, val size: Long)

/** The fields of a GitHub release the update check needs. */
data class Release(
    val tag: String,
    val version: Semver,
    val htmlUrl: String,
    val notes: String,
    val prerelease: Boolean,
    val draft: Boolean,
    /** Null when the release has no APK named by the release convention. */
    val apk: ApkAsset?,
)

/** Parses the GitHub REST API "get the latest release" response. */
object ReleaseParser {

    /** Null when the JSON is invalid or the tag is not a `v<major>.<minor>.<patch>` version. */
    fun parse(json: String): Release? = try {
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name")
        Semver.parse(tag)?.let { version ->
            Release(
                tag = tag,
                version = version,
                htmlUrl = obj.optString("html_url").takeIf(::isGithubHttps) ?: RELEASES_PAGE,
                notes = if (obj.isNull("body")) "" else obj.optString("body"),
                prerelease = obj.optBoolean("prerelease"),
                draft = obj.optBoolean("draft"),
                apk = findApk(obj, version),
            )
        }
    } catch (e: JSONException) {
        null
    }

    private fun findApk(release: JSONObject, version: Semver): ApkAsset? {
        val assets = release.optJSONArray("assets") ?: return null
        val wanted = UpdatePolicy.apkFileName(version)
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") != wanted) continue
            val url = asset.optString("browser_download_url")
            if (!isGithubHttps(url)) continue
            return ApkAsset(wanted, url, asset.optLong("size", -1L))
        }
        return null
    }

    /** Downloads and links are only followed to https://github.com (which redirects to its CDN). */
    internal fun isGithubHttps(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com"
    } catch (e: Exception) {
        false
    }

    const val RELEASES_PAGE = "https://github.com/IAmHassasin/quick-replay-PAD/releases"
}

/** Pure rules of the update check, unit-tested on the JVM. */
object UpdatePolicy {
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val APK_PREFIX = "quick-replay-pad-"
    private const val APK_SUFFIX = ".apk"

    /** Automatic checks run at most once per [CHECK_INTERVAL_MS]; manual checks always run. */
    fun shouldCheck(now: Long, lastCheckAt: Long, manual: Boolean): Boolean =
        manual || now < lastCheckAt || now - lastCheckAt >= CHECK_INTERVAL_MS

    /** [release] if it is a stable release newer than [current] and not the [skipped] version. */
    fun availableUpdate(release: Release, current: Semver, skipped: String?): Release? = release.takeIf {
        !it.draft && !it.prerelease && it.version > current && it.version != skipped?.let(Semver::parse)
    }

    fun apkFileName(version: Semver) = "$APK_PREFIX$version$APK_SUFFIX"

    /** A downloaded file that is not an APK of a version newer than [current] can be deleted. */
    fun isStaleApk(fileName: String, current: Semver): Boolean {
        if (!fileName.startsWith(APK_PREFIX) || !fileName.endsWith(APK_SUFFIX)) return true
        val version = Semver.parse(fileName.removePrefix(APK_PREFIX).removeSuffix(APK_SUFFIX)) ?: return true
        return version <= current
    }

    /** Release notes for the banner: blank lines collapsed, cut to [maxChars] with an ellipsis. */
    fun shortNotes(body: String, maxChars: Int = 300): String {
        val text = body.replace("\r\n", "\n").lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n")
        return if (text.length <= maxChars) text else text.take(maxChars - 1).trimEnd() + "…"
    }

    /** Download progress 0..100, or -1 when the total size is unknown. */
    fun percent(done: Long, total: Long): Int =
        if (total <= 0) -1 else (done * 100 / total).coerceIn(0, 100).toInt()
}
