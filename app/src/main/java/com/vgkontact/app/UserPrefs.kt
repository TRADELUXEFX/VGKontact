package com.vgkontact.app

import android.content.Context

/**
 * Stores the user's own WhatsApp number and referral number on-device
 * (SharedPreferences) so the Main Menu can display it and the Sync screen
 * can pre-fill it. Nothing here reads the phone's contact list — this is
 * only ever the two numbers the user themselves typed in.
 */
object UserPrefs {

    private const val PREFS_NAME = "vg_kontact_prefs"
    private const val KEY_WHATSAPP = "whatsapp_number"
    private const val KEY_REFERRAL = "referral_number"

    fun save(context: Context, whatsapp: String, referral: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WHATSAPP, whatsapp)
            .putString(KEY_REFERRAL, referral)
            .apply()
    }

    fun getWhatsapp(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WHATSAPP, null)

    fun getReferral(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REFERRAL, null)

    fun hasSavedNumber(context: Context): Boolean =
        !getWhatsapp(context).isNullOrBlank()
}
