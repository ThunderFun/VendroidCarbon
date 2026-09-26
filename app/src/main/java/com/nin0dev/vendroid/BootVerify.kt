package com.nin0dev.vendroid

import android.webkit.WebView
import com.nin0dev.vendroid.utils.VDELog
import com.nin0dev.vendroid.webview.UrlNormalizer

internal object BootVerify {
    // Boot-verify probe: checks for Vencord/VencordMobile globals and any
    // uncaught errors. Runs as a separate eval so it fires even when the
    // bundle died mid-script.
    //
    // __vdeUncaught entries are pre-formatted strings ("msg@src:line" from
    // VencordNative.bridgeBootstrapJs), so they are joined raw.
    //
    // localStorage diagnosis reports the type, the own-property descriptor
    // (native storage defines an accessor on window), the shim flag, and the
    // prelude's at-boot snapshot (__vdeLsBoot) to distinguish "storage never
    // worked" from "removed by in-page code". No self-heal: a silent repair
    // would erase the evidence of who removed it.
    //
    // fw/anim report the firewall gate (__vendroidFw) and the
    // animation/visibility gate (__vendroidAnimCtrl). Off means the page
    // never received the patches. The ok verdict ignores both: a missing
    // patch is a payload bug, not a failed Vencord boot.
    private val BOOT_VERIFY_JS =
            "(function(){try{" +
                "var u=(window.__vdeUncaught||[]).slice(0,5).join(' | ');" +
                "var lsv,thr=false;try{lsv=window.localStorage}catch(e){thr=true}" +
                "var ls='ls='+(thr?'throws':typeof lsv)" +
                    "+'|own='+(Object.getOwnPropertyDescriptor(window,'localStorage')?'y':'n')" +
                    "+'|shim='+(window.__vdeLsShim?'y':'n')" +
                    "+'|watch='+(window.__vdeLsWatch===undefined?'n':window.__vdeLsWatch)" +
                    "+'|boot0='+(window.__vdeLsBoot===undefined?'?':window.__vdeLsBoot);" +
                "var w=(typeof Vencord!=='undefined'&&Vencord&&Vencord.Webpack)?(Vencord.Webpack.wreq?'wreq-ok':'no-wreq'):'none';" +
                "return 'vencord='+typeof Vencord+'|webpack='+w+'|mobile='+typeof VencordMobile+'|'" +
                    "+'fw='+(window.__vendroidFw?'on':'off')+'|anim='+(window.__vendroidAnimCtrl?'on':'off')" +
                    "+'|'+ls+'|uncaught=['+u+']'" +
                    // Ported vendroidSheets patch counters: applied/missed
                    // per replacement spec make anchor rot visible in device
                    // reports. 'none' when the transition guard ran (old
                    // operator bundle still carrying the vendored plugins)
                    // or no chunk arrived yet.
                    "+'|vdep='+(window.__vdePatchStats?JSON.stringify(window.__vdePatchStats):'none');" +
                "}catch(e){return 'probe-failed:'+e.message}})()"

    internal fun runProbe(w: WebView, source: String, persist: (ok: Boolean, verdict: String) -> Unit) {
        w.evaluateJavascript(BOOT_VERIFY_JS) { raw ->
            val verdict = raw?.let { unquoteJsResult(it) } ?: "no-result"
            val ok = verdict.startsWith("vencord=object") && verdict.contains("|mobile=object")
            val safe = UrlNormalizer.redactForLog(verdict)
            if (ok) VDELog.i("Main", "Boot verify ($source): OK | $safe")
            else VDELog.e("Main", "Boot verify ($source): FAILED | $safe")
            persist(ok, verdict)
        }
    }

    /** Un-quotes the JSON string returned by evaluateJavascript. */
    private fun unquoteJsResult(raw: String): String =
        try {
            org.json.JSONArray("[$raw]").getString(0)
        } catch (_: Exception) {
            raw.trim('"')
        }
}
