package com.vgkontact.app

import android.content.Context
import android.content.SharedPreferences

object UserPrefs {
    private const val PREF_NAME = "vgkontact_prefs"
    private const val KEY_WHATSAPP = "whatsapp"
    private const val KEY_REFERRAL = "referral"
    private const val KEY_IS_REGISTERED = "is_registered"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveUser(context: Context, whatsapp: String, referral: String) {
        getPrefs(context).edit().apply {
            putString(KEY_WHATSAPP, whatsapp)
            putString(KEY_REFERRAL, referral)
            putBoolean(KEY_IS_REGISTERED, true)
            apply()
        }
    }

    fun isRegistered(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_REGISTERED, false)
    }

    fun getWhatsapp(context: Context): String? {
        return getPrefs(context).getString(KEY_WHATSAPP, null)
    }

    fun getReferral(context: Context): String? {
        return getPrefs(context).getString(KEY_REFERRAL, null)
    }

    private const val KEY_SYNCED_NUMBERS = "synced_numbers"

    fun getSyncedNumbers(context: Context): MutableSet<String> {
        return HashSet(getPrefs(context).getStringSet(KEY_SYNCED_NUMBERS, emptySet()) ?: emptySet())
    }

    fun addSyncedNumbers(context: Context, numbers: Set<String>) {
        val current = getSyncedNumbers(context)
        current.addAll(numbers)
        getPrefs(context).edit().putStringSet(KEY_SYNCED_NUMBERS, current).apply()
    }

    private const val KEY_CONTACT_COUNTER = "contact_counter"

    fun getContactCounter(context: Context): Int {
        return getPrefs(context).getInt(KEY_CONTACT_COUNTER, 0)
    }

    fun setContactCounter(context: Context, value: Int) {
        getPrefs(context).edit().putInt(KEY_CONTACT_COUNTER, value).apply()
    }
}
