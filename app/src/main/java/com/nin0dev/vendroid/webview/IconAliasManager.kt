package com.nin0dev.vendroid.webview

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.nin0dev.vendroid.MainActivity
import com.nin0dev.vendroid.utils.VDELog

internal object IconAliasManager {
    // Launcher activity-alias names (manifest: <activity-alias
    // android:name=".${name}MainActivity">). Declared as a List because
    // reconcileIconState() uses the order as tie-breaker when several
    // aliases are enabled.
    internal val ICON_NAMES = listOf("Main", "Jolly", "Discord", "Retro", "TS12")
    // Launcher icon currently believed active. PackageManager component
    // state is the source of truth; this is only a cache. changeAppIcon()
    // commits it only after the enable call succeeds, never speculatively.
    // Null means not yet resolved for this process. All access happens
    // under iconLock.
    @Volatile
    internal var currentIcon: String? = null
    internal val iconLock = Any()

    // Alias class names in the manifest expand against the AGP namespace
    // ("com.nin0dev.vendroid"), not the runtime applicationId. Debug and
    // dev builds append an applicationIdSuffix, so their manifest package
    // is "com.nin0dev.vendroid.debug" while alias classes stay
    // "com.nin0dev.vendroid.${name}MainActivity" (verified in the merged
    // debug manifest). Deriving the class from pkg.packageName would name
    // components that do not exist and make every PM write throw
    // IllegalArgumentException. Must match `namespace` in build.gradle.
    private const val ICON_COMPONENT_CLASS_PREFIX = "com.nin0dev.vendroid."

    // The Context supplies the owning package (the applicationId) for the
    // ComponentName; the class part is the namespace-prefixed alias name.
    internal fun iconComponent(pkg: Context, name: String): ComponentName =
        ComponentName(pkg, "$ICON_COMPONENT_CLASS_PREFIX${name}MainActivity")

    // getComponentEnabledSetting documents no exceptions, but a component
    // missing from this build (flavor or manifest drift) must not break
    // reconciliation. A missing component reads as the manifest default.
    private fun iconComponentState(pm: PackageManager, pkg: Context, name: String): Int =
        try {
            pm.getComponentEnabledSetting(iconComponent(pkg, name))
        } catch (t: Throwable) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }

    private fun iconFromComponent(cn: ComponentName?): String? {
        val cls = cn?.className ?: return null
        if (!cls.startsWith(ICON_COMPONENT_CLASS_PREFIX)) return null
        val short = cls.removePrefix(ICON_COMPONENT_CLASS_PREFIX)
        return ICON_NAMES.find { short == "${it}MainActivity" }
    }

    /**
     * Reconcile the [currentIcon] cache with PackageManager state and
     * repair corruption in either direction:
     *  - the cache names an alias PM does not have enabled (a failed
     *    switch leaves this behind; it made "already active" lie and
     *    blocked retries), or
     *  - several aliases are enabled (a half-applied switch leaves this
     *    behind; it shows up as duplicate launcher entries).
     *
     * Resolution when several aliases are enabled: the cached icon (last
     * known intent) wins, else the alias this activity was launched from
     * (the entry the user actually tapped), else ICON_NAMES order. Every
     * other enabled alias is disabled, not just dropped from the cache.
     */
    internal fun reconcileIconState(act: MainActivity) {
        val pm = act.packageManager
        val pkg = act.applicationContext
        val states = ICON_NAMES.associateWith { iconComponentState(pm, pkg, it) }
        val enabled = ICON_NAMES.filter {
            states[it] == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        val cached = currentIcon
        val resolved = when {
            cached != null && enabled.contains(cached) -> cached
            enabled.size == 1 -> enabled[0]
            enabled.size > 1 ->
                iconFromComponent(act.intent?.component)?.takeIf { enabled.contains(it) }
                    ?: enabled.first()
            // No alias is explicitly enabled. Main normally sits at its
            // manifest default (android:enabled="true") and that is what
            // the launcher shows. If Main was also explicitly disabled,
            // the launcher has no entry at all and the switcher would
            // report "already active" forever, so restore the default.
            else -> {
                if (states["Main"] == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    try {
                        pm.setComponentEnabledSetting(
                            iconComponent(pkg, "Main"),
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        VDELog.w("VN", "Icon repair: launcher had no enabled entry; re-enabled Main")
                    } catch (t: Throwable) {
                        VDELog.e("VN", "Icon repair: could not re-enable Main", t)
                    }
                }
                "Main"
            }
        }
        if (resolved != cached) {
            // Assign before logging so a throwing logger cannot leave the
            // cache stale.
            currentIcon = resolved
            if (cached == null) {
                // First resolution this process, not a desync.
                VDELog.d("VN", "Icon cache resolved to '$resolved'")
            } else {
                VDELog.w("VN", "Icon cache desync (cache=$cached, pm=$resolved), repaired")
            }
        }
        for (name in enabled) {
            if (name == resolved) continue
            try {
                pm.setComponentEnabledSetting(
                    iconComponent(pkg, name),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                VDELog.w("VN", "Icon repair: disabled stale enabled launcher alias '$name'")
            } catch (t: Throwable) {
                VDELog.e("VN", "Icon repair: could not disable stale launcher alias '$name'", t)
            }
        }
    }

    /**
     * Called when a VencordNative (and thus a MainActivity) is created.
     * Refreshes the icon cache from PackageManager and repairs any
     * corruption found; this is what heals duplicate launcher entries
     * after a process restart. PM reads are cheap, and only actual
     * corruption triggers writes.
     */
    fun initCurrentIcon(activity: MainActivity?) {
        val act = activity ?: return
        try {
            synchronized(iconLock) { reconcileIconState(act) }
        } catch (t: Throwable) {
            // Runs from the class constructor (MainActivity.onCreate);
            // construction must never fail. A failed reconcile leaves the
            // cache unresolved; the next changeAppIcon reconciles again.
            VDELog.e("VN", "initCurrentIcon: reconcile failed", t)
        }
    }
}
