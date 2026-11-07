package dev.quickreplay

import dev.quickreplay.update.ReleaseParser
import dev.quickreplay.update.Semver
import dev.quickreplay.update.UpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {

    // --- Semver ---

    @Test
    fun parsesTagsWithAndWithoutPrefix() {
        assertEquals(Semver(0, 2, 0), Semver.parse("v0.2.0"))
        assertEquals(Semver(1, 10, 3), Semver.parse("1.10.3"))
        assertEquals(Semver(0, 1, 1), Semver.parse(" v0.1.1 "))
    }

    @Test
    fun rejectsInvalidAndPreReleaseVersions() {
        for (bad in listOf("", "v", "0.2", "v0.2.0-beta", "v0.2.0.1", "latest", "va.b.c", "v-1.0.0")) {
            assertNull(bad, Semver.parse(bad))
        }
    }

    @Test
    fun comparesNumericallyNotLexically() {
        assertTrue(Semver.parse("0.10.0")!! > Semver.parse("0.9.9")!!)
        assertTrue(Semver.parse("1.0.0")!! > Semver.parse("0.99.99")!!)
        assertTrue(Semver.parse("0.1.2")!! > Semver.parse("0.1.1")!!)
        assertEquals(0, Semver.parse("v0.2.0")!!.compareTo(Semver.parse("0.2.0")!!))
        assertEquals("0.2.0", Semver(0, 2, 0).toString())
    }

    // --- Release JSON ---

    private fun releaseJson(
        tag: String = "v0.2.0",
        prerelease: Boolean = false,
        draft: Boolean = false,
        body: String? = "Fixes and the in-app update.",
        assets: String = """
            {"name": "quick-replay-pad-0.2.0.apk", "size": 4096,
             "browser_download_url": "https://github.com/IAmHassasin/quick-replay-PAD/releases/download/v0.2.0/quick-replay-pad-0.2.0.apk"}
        """,
    ) = """
        {
          "tag_name": "$tag",
          "name": "Quick Replay PAD $tag",
          "html_url": "https://github.com/IAmHassasin/quick-replay-PAD/releases/tag/$tag",
          "prerelease": $prerelease,
          "draft": $draft,
          "body": ${if (body == null) "null" else "\"$body\""},
          "assets": [
            {"name": "notes.txt", "size": 10, "browser_download_url": "https://github.com/x/notes.txt"},
            $assets
          ]
        }
    """.trimIndent()

    @Test
    fun parsesReleaseAndPicksTheApkAsset() {
        val release = ReleaseParser.parse(releaseJson())!!
        assertEquals("v0.2.0", release.tag)
        assertEquals(Semver(0, 2, 0), release.version)
        assertEquals("https://github.com/IAmHassasin/quick-replay-PAD/releases/tag/v0.2.0", release.htmlUrl)
        assertEquals("Fixes and the in-app update.", release.notes)
        assertFalse(release.prerelease)
        assertFalse(release.draft)
        val apk = release.apk!!
        assertEquals("quick-replay-pad-0.2.0.apk", apk.name)
        assertEquals(4096L, apk.size)
        assertEquals(
            "https://github.com/IAmHassasin/quick-replay-PAD/releases/download/v0.2.0/quick-replay-pad-0.2.0.apk",
            apk.url,
        )
    }

    @Test
    fun releaseWithoutMatchingAssetHasNoApk() {
        val otherName = """{"name": "Quick.Replay.PAD.0.2.0.apk", "size": 1, "browser_download_url": "https://github.com/a/b.apk"}"""
        assertNull(ReleaseParser.parse(releaseJson(assets = otherName))!!.apk)
        // Asset of another version is not picked either.
        val oldName = """{"name": "quick-replay-pad-0.1.1.apk", "size": 1, "browser_download_url": "https://github.com/a/b.apk"}"""
        assertNull(ReleaseParser.parse(releaseJson(assets = oldName))!!.apk)
    }

    @Test
    fun apkAssetOutsideGithubOrNotHttpsIsIgnored() {
        val http = """{"name": "quick-replay-pad-0.2.0.apk", "size": 1, "browser_download_url": "http://github.com/a/b.apk"}"""
        assertNull(ReleaseParser.parse(releaseJson(assets = http))!!.apk)
        val elsewhere = """{"name": "quick-replay-pad-0.2.0.apk", "size": 1, "browser_download_url": "https://evil.example/github.com/b.apk"}"""
        assertNull(ReleaseParser.parse(releaseJson(assets = elsewhere))!!.apk)
    }

    @Test
    fun missingBodyGivesEmptyNotesAndBadInputGivesNull() {
        assertEquals("", ReleaseParser.parse(releaseJson(body = null))!!.notes)
        assertNull(ReleaseParser.parse("not json"))
        assertNull(ReleaseParser.parse("""{"tag_name": "nightly", "assets": []}"""))
        assertNull(ReleaseParser.parse("""{"message": "API rate limit exceeded"}"""))
    }

    // --- Policy ---

    private val current = Semver(0, 1, 1)

    @Test
    fun newerStableReleaseIsAnUpdate() {
        val release = ReleaseParser.parse(releaseJson())!!
        assertNotNull(UpdatePolicy.availableUpdate(release, current, skipped = null))
    }

    @Test
    fun sameOrOlderReleaseIsNotAnUpdate() {
        val same = ReleaseParser.parse(releaseJson(tag = "v0.1.1"))!!
        val older = ReleaseParser.parse(releaseJson(tag = "v0.1.0"))!!
        assertNull(UpdatePolicy.availableUpdate(same, current, skipped = null))
        assertNull(UpdatePolicy.availableUpdate(older, current, skipped = null))
    }

    @Test
    fun preReleaseAndDraftAreNotUpdates() {
        assertNull(UpdatePolicy.availableUpdate(ReleaseParser.parse(releaseJson(prerelease = true))!!, current, null))
        assertNull(UpdatePolicy.availableUpdate(ReleaseParser.parse(releaseJson(draft = true))!!, current, null))
    }

    @Test
    fun skippedVersionIsHiddenUntilANewerOne() {
        val v020 = ReleaseParser.parse(releaseJson(tag = "v0.2.0"))!!
        val v021 = ReleaseParser.parse(releaseJson(tag = "v0.2.1"))!!
        assertNull(UpdatePolicy.availableUpdate(v020, current, skipped = "0.2.0"))
        assertNotNull(UpdatePolicy.availableUpdate(v021, current, skipped = "0.2.0"))
        // An unreadable stored value does not hide anything.
        assertNotNull(UpdatePolicy.availableUpdate(v020, current, skipped = "garbage"))
    }

    @Test
    fun automaticCheckRunsAtMostEverySixHours() {
        val hour = 60 * 60 * 1000L
        val now = 100 * hour
        assertTrue(UpdatePolicy.shouldCheck(now, lastCheckAt = 0L, manual = false))
        assertFalse(UpdatePolicy.shouldCheck(now, lastCheckAt = now - 5 * hour, manual = false))
        assertTrue(UpdatePolicy.shouldCheck(now, lastCheckAt = now - 6 * hour, manual = false))
        assertTrue(UpdatePolicy.shouldCheck(now, lastCheckAt = now - 1, manual = true))
        // A clock moved backwards does not block checks forever.
        assertTrue(UpdatePolicy.shouldCheck(now, lastCheckAt = now + 10 * hour, manual = false))
    }

    @Test
    fun apkFileNamesAndStaleFiles() {
        assertEquals("quick-replay-pad-0.2.0.apk", UpdatePolicy.apkFileName(Semver(0, 2, 0)))
        assertTrue(UpdatePolicy.isStaleApk("quick-replay-pad-0.1.1.apk", current))
        assertTrue(UpdatePolicy.isStaleApk("quick-replay-pad-0.1.0.apk", current))
        assertFalse(UpdatePolicy.isStaleApk("quick-replay-pad-0.2.0.apk", current))
        assertTrue(UpdatePolicy.isStaleApk("download.tmp", current))
    }

    @Test
    fun notesAreTrimmed() {
        assertEquals("", UpdatePolicy.shortNotes("  \n "))
        assertEquals("One\nTwo", UpdatePolicy.shortNotes("One\r\n\r\n\r\nTwo\n"))
        val long = "x".repeat(500)
        val short = UpdatePolicy.shortNotes(long, maxChars = 100)
        assertEquals(100, short.length)
        assertTrue(short.endsWith("…"))
    }

    @Test
    fun progressPercent() {
        assertEquals(0, UpdatePolicy.percent(0, 100))
        assertEquals(50, UpdatePolicy.percent(50, 100))
        assertEquals(100, UpdatePolicy.percent(150, 100))
        assertEquals(-1, UpdatePolicy.percent(10, 0)) // unknown size
    }
}
