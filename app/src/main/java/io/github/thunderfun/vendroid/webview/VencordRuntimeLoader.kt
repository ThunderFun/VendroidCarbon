package io.github.thunderfun.vendroid.webview

import android.content.SharedPreferences
import android.content.res.Resources
import io.github.thunderfun.vendroid.R
import java.io.File
import java.io.IOException

/**
 * Single owner of the Vencord runtime preload sequence: loads whichever
 * runtimes are missing from memory and publishes them through HttpClient's
 * compare-and-set helpers. VendroidApp's process-start preload and
 * MainActivity's safety-net task both delegate here. The publish-time
 * recheck breaks silently when duplicated, so it lives in this one place.
 *
 * Sequence:
 *
 *  1. Kill switch: nothing can publish when Vencord is disabled, so skip
 *     all reads.
 *  2. VencordMobile runtime (~65 KB raw resource). The outer null check is
 *     read-avoidance only; the CAS re-checks under the lock.
 *  3. Vencord runtime (~1 MB cached bundle). Skipped while
 *     [HttpClient.needsBundleRedownload] is pending so a stale bundle is
 *     never published. The stale file stays on disk; fetchVencord needs
 *     it as its offline fallback.
 *
 * The stillValid re-check passed to [HttpClient.setVencordRuntimeIfNull]
 * re-evaluates the guards at publish time. The ~1MB read can stall for
 * seconds on slow storage while a clientMod switch deletes the file and
 * forces a redownload; a stalled publish must not restore stale content.
 * needsBundleRedownload is re-evaluated inside the lambda rather than
 * hoisted, for the same reason. (readBundleFromDisk trusts the persisted
 * patched flag + patch-set key to skip the ~1MB regex scan; see its
 * KDoc.)
 *
 * Contract notes:
 *
 *  - A caller that loses the CAS returns normally with a false flag. The
 *    known producers are VendroidApp's preload thread and MainActivity's
 *    fetchExecutor task; fetchVencord's own read-then-CAS sites run
 *    serialized behind the same single-threaded executor.
 *  - Reading the cached bundle is not a freshness proof. Never set
 *    bundleCheckedThisSession or the last-bundle-check stamp here; a
 *    failed check must not count as fresh (see fetchVencord).
 *  - fetchVencord's own CAS sites use a weaker stillValid (file existence
 *    only) on purpose. The 304 or the download just confirmed bundle
 *    freshness, so only file existence needs re-checking. Do not unify
 *    the two stillValid lambdas.
 *
 * Preconditions: run after VendroidApp.onCreate's boot heals
 * (needsBundleRedownload resolves the vencordLocation pref). res and dir
 * are captured values; Activity-sourced Resources and filesDir do not
 * survive onDestroy, so background callers capture them before enqueueing.
 *
 * Failures propagate as [IOException] with the failing step named in the
 * message; callers log with their own tag and continue. Any runtime already
 * published stays published.
 */
internal object VencordRuntimeLoader {

    /**
     * Per-runtime publish results from [loadIfMissing]. A false flag means
     * the runtime was already present, a guard failed, or the CAS lost a
     * race. Callers must log and trigger injection from these flags, never
     * from memory state; a memory-derived check would fire injection on
     * every call once both runtimes are present.
     */
    data class Outcome(
        val mobilePublished: Boolean,
        val bundlePublished: Boolean
    ) {
        val publishedSomething: Boolean
            get() = mobilePublished || bundlePublished
    }

    /**
     * Loads and publishes whichever runtimes are missing. Synchronous;
     * callers supply threading. Concurrent calls are safe (the CAS
     * arbitrates). Throws [IOException] on read or patch failure.
     */
    fun loadIfMissing(
        sPrefs: SharedPreferences,
        res: Resources,
        dir: File
    ): Outcome {
        if (HttpClient.vencordDisabled) return Outcome(false, false)
        var mobilePublished = false
        if (HttpClient.VencordMobileRuntime == null) {
            try {
                val mobile = res.openRawResource(R.raw.vencord_mobile).use {
                    HttpClient.readAsText(it)
                }
                mobilePublished = HttpClient.setVencordMobileRuntimeIfNull(mobile)
            } catch (ex: Exception) {
                throw IOException("VencordMobile runtime read failed", ex)
            }
        }
        // Re-check the kill switch so a safe-mode raise mid-sequence
        // skips the ~1MB read; the CAS under the lock is authoritative
        // either way.
        var bundlePublished = false
        if (!HttpClient.vencordDisabled && HttpClient.VencordRuntime == null) {
            val vendroidFile = HttpClient.vendroidFile(dir)
            if (!HttpClient.needsBundleRedownload(sPrefs) && vendroidFile.exists()) {
                try {
                    bundlePublished = HttpClient.setVencordRuntimeIfNull(
                        HttpClient.readBundleFromDisk(sPrefs, vendroidFile)
                    ) {
                        !HttpClient.needsBundleRedownload(sPrefs) && vendroidFile.exists()
                    }
                } catch (ex: Exception) {
                    throw IOException("Vencord bundle read/patch failed", ex)
                }
            }
        }
        return Outcome(mobilePublished, bundlePublished)
    }
}
