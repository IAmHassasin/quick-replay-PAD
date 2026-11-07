package dev.quickreplay.update

import android.content.Context
import android.util.Log
import dev.quickreplay.store.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the update banner shows. */
sealed class UpdateState {
    data object Hidden : UpdateState()

    /** A newer release; [error] is the last download failure, if any. */
    data class Available(val release: Release, val current: Semver, val error: String? = null) : UpdateState()

    /** [percent] is -1 while the size is unknown. */
    data class Downloading(val release: Release, val current: Semver, val percent: Int) : UpdateState()

    /** The APK is downloaded and checked; the system installer still has to be opened. */
    data class ReadyToInstall(val release: Release, val current: Semver, val apk: File) : UpdateState()
}

/** Result of a check, for the manual "Check for updates" toast. */
enum class CheckResult { UP_TO_DATE, AVAILABLE, FAILED }

/**
 * Checks GitHub Releases for a newer version and downloads its APK. Lives for the process, so
 * the banner and a running download survive activity recreation; a new process starts hidden
 * ("Later" lasts until the app is opened again and checks).
 */
object UpdateController {
    private const val TAG = "QuickReplayUpdate"
    private const val DIR = "updates"
    private const val PARTIAL = "download.tmp"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Hidden)
    val state: StateFlow<UpdateState> = _state
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** Identifies the running download; progress of a cancelled one is dropped. Main thread only. */
    private var downloadToken: Any? = null

    fun currentVersion(context: Context): Semver? =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.let(Semver::parse)

    fun apkDir(context: Context) = File(context.cacheDir, DIR)

    /**
     * Checks the latest release unless an automatic check ran less than 6 hours ago. Network
     * errors are only logged; [onResult] (manual checks) gets [CheckResult.FAILED]. A manual check
     * also shows a version the user skipped, since they asked explicitly.
     */
    fun check(context: Context, manual: Boolean, onResult: (CheckResult) -> Unit = {}) {
        val app = context.applicationContext
        val settings = AppSettings(app)
        if (checkJob?.isActive == true) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.ReadyToInstall) {
            if (manual) onResult(CheckResult.AVAILABLE)
            return
        }
        if (!UpdatePolicy.shouldCheck(System.currentTimeMillis(), settings.lastUpdateCheckAt, manual)) return
        val current = currentVersion(app) ?: return
        checkJob = scope.launch {
            val result = try {
                val json = withContext(Dispatchers.IO) { UpdateClient.fetchLatestRelease(userAgent(current)) }
                settings.lastUpdateCheckAt = System.currentTimeMillis()
                val release = json?.let(ReleaseParser::parse)
                val update = release?.let {
                    UpdatePolicy.availableUpdate(it, current, if (manual) null else settings.skippedUpdateVersion)
                }
                if (update != null) {
                    _state.value = UpdateState.Available(update, current)
                    CheckResult.AVAILABLE
                } else {
                    _state.value = UpdateState.Hidden
                    CheckResult.UP_TO_DATE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "Update check failed: ${e.javaClass.simpleName}: ${e.message}")
                CheckResult.FAILED
            }
            onResult(result)
        }
    }

    /** Downloads the APK of the available release, or reuses one already downloaded. */
    fun download(context: Context) {
        val available = _state.value as? UpdateState.Available ?: return
        val apk = available.release.apk ?: return
        val app = context.applicationContext
        _state.value = UpdateState.Downloading(available.release, available.current, -1)
        val token = Any()
        downloadToken = token
        downloadJob = scope.launch {
            val dir = apkDir(app)
            val target = File(dir, apk.name)
            val partial = File(dir, PARTIAL)
            try {
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    if (!isValidApk(app, target, available.release, apk)) {
                        target.delete()
                        var lastPercent = Int.MIN_VALUE
                        UpdateClient.download(apk.url, partial, userAgent(available.current)) { done, total ->
                            val percent = UpdatePolicy.percent(done, total)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                // Applied on the main thread, where cancel and dismiss run, so a
                                // late progress update cannot bring back a cancelled download.
                                scope.launch {
                                    if (downloadToken === token) {
                                        _state.value = UpdateState.Downloading(available.release, available.current, percent)
                                    }
                                }
                            }
                        }
                        if (!partial.renameTo(target)) throw IllegalStateException("Cannot save the APK")
                        kotlin.check(isValidApk(app, target, available.release, apk)) {
                            "The downloaded file is not Quick Replay PAD ${available.release.version}"
                        }
                    }
                }
                downloadToken = null
                _state.value = UpdateState.ReadyToInstall(available.release, available.current, target)
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { partial.delete() }
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "Update download failed: ${e.javaClass.simpleName}: ${e.message}")
                withContext(Dispatchers.IO) {
                    partial.delete()
                    target.delete()
                }
                downloadToken = null
                _state.value = available.copy(error = e.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload() {
        val downloading = _state.value as? UpdateState.Downloading ?: return
        downloadToken = null
        downloadJob?.cancel()
        _state.value = UpdateState.Available(downloading.release, downloading.current)
    }

    /** "Later": hide until the next check. */
    fun dismiss() {
        downloadToken = null
        downloadJob?.cancel()
        _state.value = UpdateState.Hidden
    }

    /** "Skip this version": never show this version again (a newer one still shows). */
    fun skip(context: Context, version: Semver) {
        AppSettings(context.applicationContext).skippedUpdateVersion = version.toString()
        dismiss()
    }

    /** Deletes downloaded APKs that are not newer than the installed version (e.g. after updating). */
    fun deleteStaleApks(context: Context) {
        val current = currentVersion(context) ?: return
        if (_state.value is UpdateState.Downloading) return
        apkDir(context).listFiles()?.forEach { file ->
            if (UpdatePolicy.isStaleApk(file.name, current)) file.delete()
        }
    }

    /** Size from the release (when known), package name and version of the downloaded APK. */
    private fun isValidApk(context: Context, file: File, release: Release, asset: ApkAsset): Boolean {
        if (!file.isFile) return false
        if (asset.size > 0 && file.length() != asset.size) return false
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: return false
        return info.packageName == context.packageName && info.versionName == release.version.toString()
    }

    private fun userAgent(current: Semver) = "QuickReplayPAD/$current (Android)"
}
