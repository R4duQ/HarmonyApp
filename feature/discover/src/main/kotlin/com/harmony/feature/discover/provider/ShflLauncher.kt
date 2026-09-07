package com.harmony.feature.discover.provider

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.ColorInt
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import com.harmony.feature.discover.model.ShflUrlPolicy
import java.util.Locale

/** What happened when Harmony tried to hand a link to a browser. */
enum class ShflLaunchResult {
    /** A browser accepted the link. */
    OPENED,

    /** The URL failed validation. Nothing was opened and nothing left the app. */
    BLOCKED,

    /** The URL was fine, but no installed app can open a web page. */
    NO_BROWSER,
}

/**
 * Opens official SHFL pages in the user's own browser.
 *
 * Custom Tabs first, for one specific reason: SHFL has accounts, and login
 * state belongs to the browser the user already trusts. A Custom Tab shares
 * the browser's cookie jar and password manager, so a user who is signed in
 * stays signed in and Harmony never sits between them and the site. An
 * embedded WebView would do the opposite — it would put Harmony's process in
 * the path of a login form — which is why there isn't one here.
 *
 * When Custom Tabs are unavailable (no supporting browser installed, or the
 * device's browser is an older one) this falls back to a plain
 * [Intent.ACTION_VIEW], which any browser can handle. Either way the browser
 * opens in Harmony's own task, so closing it returns to the Discover screen
 * with the app's state intact rather than to the launcher.
 *
 * Nothing about the user is attached to the request. No query parameters are
 * added, none are permitted through [ShflUrlPolicy], and no identifier,
 * library contents or listening history is included — Harmony opens a public
 * page and nothing more.
 */
object ShflLauncher {

    /**
     * Validates and opens [url].
     *
     * [context] should be the Activity context (`LocalContext.current` inside
     * a Compose screen is exactly that). Passing an application context would
     * force `FLAG_ACTIVITY_NEW_TASK` and break the return-to-Harmony
     * behaviour, so it is not done here.
     *
     * @param toolbarColor optional ARGB color for the Custom Tab's toolbar, so
     *   the handoff carries Harmony's current section color instead of
     *   arriving as an unrelated white bar.
     */
    fun open(
        context: Context,
        url: String,
        @ColorInt toolbarColor: Int? = null,
    ): ShflLaunchResult {
        // Gate 1: the pure-JVM allowlist. It returns its own canonical string,
        // so what we open below is a value the policy produced rather than
        // whatever was passed in.
        val safeUrl = ShflUrlPolicy.sanitizeDestination(url) ?: return ShflLaunchResult.BLOCKED

        val uri = runCatching { Uri.parse(safeUrl) }.getOrNull() ?: return ShflLaunchResult.BLOCKED

        // Gate 2: re-check scheme and host on Android's parser. java.net.URI
        // and android.net.Uri do not always split hostile input identically,
        // and the cheapest defence against that class of bug is to require
        // both of them to agree.
        if (!ShflUrlPolicy.SCHEME.equals(uri.scheme, ignoreCase = true)) {
            return ShflLaunchResult.BLOCKED
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host == null || host !in ShflUrlPolicy.ALLOWED_HOSTS) {
            return ShflLaunchResult.BLOCKED
        }

        return openInCustomTab(context, uri, toolbarColor)
            ?: openInExternalBrowser(context, uri)
    }

    /** Returns null when Custom Tabs could not take the link, so the caller can fall back. */
    private fun openInCustomTab(
        context: Context,
        uri: Uri,
        @ColorInt toolbarColor: Int?,
    ): ShflLaunchResult? {
        val builder = CustomTabsIntent.Builder()
            .setShowTitle(true)
            // Sharing and downloads from inside the tab are the browser's
            // business, not Harmony's; the tab is here to show a page.
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
        if (toolbarColor != null) {
            builder.setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(toolbarColor)
                    .build(),
            )
        }
        val customTabsIntent = builder.build()
        customTabsIntent.intent.addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            customTabsIntent.launchUrl(context, uri)
            ShflLaunchResult.OPENED
        } catch (_: ActivityNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun openInExternalBrowser(context: Context, uri: Uri): ShflLaunchResult {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return try {
            context.startActivity(intent)
            ShflLaunchResult.OPENED
        } catch (_: ActivityNotFoundException) {
            ShflLaunchResult.NO_BROWSER
        } catch (_: SecurityException) {
            ShflLaunchResult.NO_BROWSER
        }
    }
}
