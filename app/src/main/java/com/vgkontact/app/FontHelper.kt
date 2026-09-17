package com.vgkontact.app

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.core.content.res.ResourcesCompat
import androidx.core.provider.FontsContractCompat

/**
 * Applies the app's brand font (Poppins) asynchronously, with the
 * system default font as a guaranteed fallback.
 *
 * This exists because setting android:fontFamily="@font/poppins"
 * directly on the app theme would make font resolution part of every
 * Activity's setContentView() inflate path. Poppins is fetched at
 * runtime via the Google Play Services Fonts provider (see
 * res/font/poppins.xml), and on any environment where that provider
 * can't be reached synchronously (Secure Folder / work-profile GMS
 * sandboxing, restricted or outdated Play Services), inflate would
 * throw and crash the app on launch - every screen, every time, with
 * no local state to clear to fix it.
 *
 * Call applyPoppinsAsync() after setContentView() in an Activity,
 * passing the root view. It walks the view tree and swaps in Poppins
 * once (and only if) it loads successfully; if the provider fails or
 * times out, views are simply left on the system default font - never
 * a crash.
 */
object FontHelper {

    fun applyPoppinsAsync(context: Context, root: android.view.View) {
        try {
            val request = androidx.core.provider.FontRequest(
                "com.google.android.gms.fonts",
                "com.google.android.gms",
                "name=Poppins",
                R.array.com_google_android_gms_fonts_certs
            )
            val handler = Handler(Looper.getMainLooper())
            ResourcesCompat.getFont(
                context,
                R.font.poppins,
                object : ResourcesCompat.FontCallback() {
                    override fun onFontRetrieved(typeface: Typeface) {
                        applyTypefaceRecursively(root, typeface)
                    }

                    override fun onFontRetrievalFailed(reason: Int) {
                        // Leave system default font in place - not a crash,
                        // just a slightly different look.
                    }
                },
                handler
            )
        } catch (e: Exception) {
            // Any unexpected failure here (missing provider, security
            // exception, etc.) must never take the app down with it.
        }
    }

    private fun applyTypefaceRecursively(view: android.view.View, typeface: Typeface) {
        when (view) {
            is android.widget.TextView -> view.typeface = typeface
            is android.view.ViewGroup -> {
                for (i in 0 until view.childCount) {
                    applyTypefaceRecursively(view.getChildAt(i), typeface)
                }
            }
        }
    }
}
