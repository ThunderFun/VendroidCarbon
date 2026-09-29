package io.github.thunderfun.vendroid.webview

import android.net.Uri
import io.github.thunderfun.vendroid.utils.Constants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationPolicyTest {

    // ------------------------------------------------------------------
    //  isNavigationAllowedDomain
    // ------------------------------------------------------------------

    @Test fun navAllowed_discordApex() {
        assertTrue(Constants.isNavigationAllowedDomain("discord.com"))
    }

    @Test fun navAllowed_discordSubdomain() {
        assertTrue(Constants.isNavigationAllowedDomain("canary.discord.com"))
        assertTrue(Constants.isNavigationAllowedDomain("ptb.discord.com"))
    }

    @Test fun navAllowed_discordapp() {
        assertTrue(Constants.isNavigationAllowedDomain("discordapp.com"))
    }

    @Test fun navAllowed_discordGg() {
        assertTrue(Constants.isNavigationAllowedDomain("discord.gg"))
    }

    // Raw-content CDN hosts render as a bare image/video document with no
    // in-app back path (media hardlock), so main-frame navigation is
    // deliberately rejected and these hosts route through the link popup.
    // Subresource loads (<img> etc.) are unaffected; the firewall governs
    // those separately.
    @Test fun navAllowed_rawContentCdnRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("discord.media"))
        assertFalse(Constants.isNavigationAllowedDomain("media.discord.media"))
        assertFalse(Constants.isNavigationAllowedDomain("discordapp.net"))
        assertFalse(Constants.isNavigationAllowedDomain("cdn.discordapp.net"))
        assertFalse(Constants.isNavigationAllowedDomain("media.discordapp.net"))
    }

    // Non-apex discordapp.com subdomains (cdn., media., ptb.) serve
    // attacker-uploaded content and get no in-app main-frame navigation;
    // only the apex is navigable. Matches the apex-only rule in
    // isDiscordAppOrigin.
    @Test fun navAllowed_discordappSubdomainsRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("ptb.discordapp.com"))
        assertFalse(Constants.isNavigationAllowedDomain("cdn.discordapp.com"))
        assertFalse(Constants.isNavigationAllowedDomain("media.discordapp.com"))
    }

    @Test fun navAllowed_discordsays() {
        assertTrue(Constants.isNavigationAllowedDomain("discordsays.com"))
    }

    @Test fun navAllowed_watchanimeattheoffice() {
        assertTrue(Constants.isNavigationAllowedDomain("watchanimeattheoffice.com"))
    }

    @Test fun navAllowed_lookalikeRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("evildiscord.com"))
        assertFalse(Constants.isNavigationAllowedDomain("discord.com.evil.com"))
    }

    @Test fun navAllowed_githubRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("github.com"))
    }

    @Test fun navAllowed_rawGithubRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("raw.githubusercontent.com"))
    }

    @Test fun navAllowed_hcaptchaRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("hcaptcha.com"))
    }

    @Test fun navAllowed_googleStorageRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("storage.googleapis.com"))
    }

    @Test fun navAllowed_emptyRejected() {
        assertFalse(Constants.isNavigationAllowedDomain(""))
    }

    // ------------------------------------------------------------------
    //  NavigationPolicy.decide: main frame
    // ------------------------------------------------------------------

    @Test fun decide_mainFrameDiscord_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide(Uri.parse("https://discord.com"), isForMainFrame = true)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }

    @Test fun decide_mainFrameDiscordAppPath_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide(Uri.parse("https://discord.com/app"), isForMainFrame = true)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }

    @Test fun decide_mainFrameDiscordBlog_showsPopup() {
        assertTrue(
            NavigationPolicy.decide(Uri.parse("https://discord.com/blog/inside-our-engineering"), isForMainFrame = true)
                == NavigationPolicy.Action.SHOW_POPUP
        )
    }

    @Test fun decide_mainFrameDiscordBlogRoot_showsPopup() {
        assertTrue(
            NavigationPolicy.decide(Uri.parse("https://discord.com/blog"), isForMainFrame = true)
                == NavigationPolicy.Action.SHOW_POPUP
        )
    }

    @Test fun decide_mainFrameUnknownHost_showsPopup() {
        assertTrue(
            NavigationPolicy.decide(Uri.parse("https://evil.com"), isForMainFrame = true)
                == NavigationPolicy.Action.SHOW_POPUP
        )
    }

    @Test fun decide_mainFrameNullHost_showsPopup() {
        assertTrue(
            NavigationPolicy.decide(Uri.parse("https://"), isForMainFrame = true)
                == NavigationPolicy.Action.SHOW_POPUP
        )
    }

    @Test fun decide_aboutScheme_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide(Uri.parse("about:blank"), isForMainFrame = true)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }

    @Test fun decide_mainFrameHttpDiscord_showsPopup() {
        // Cleartext http:// must never load as a main frame (MITM token risk),
        // even on a Discord host.
        assertTrue(
            NavigationPolicy.decide(Uri.parse("http://discord.com/app"), isForMainFrame = true)
                == NavigationPolicy.Action.SHOW_POPUP
        )
    }

    // ------------------------------------------------------------------
    //  NavigationPolicy.decide: subframe (iframe)
    // ------------------------------------------------------------------

    // Subframes load in-WebView before the URL is read, so host, scheme, and
    // path cannot change the action. One case pins the branch.
    @Test fun decide_subframeUnknownHost_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide(Uri.parse("https://evil.com"), isForMainFrame = false)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }
}