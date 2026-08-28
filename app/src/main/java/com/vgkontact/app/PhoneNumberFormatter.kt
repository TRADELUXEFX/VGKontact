package com.vgkontact.app

import android.text.Editable
import android.text.TextWatcher
import com.google.android.material.textfield.TextInputEditText

/**
 * Live-formats a Nigerian phone number as the user types:
 *   08108709628  ->  0810 870 9628   (shown in the box)
 *
 * The EditText itself keeps the spaces (that's what "live formatting" means),
 * but callers should always read the number through rawDigits(), never
 * through editText.text directly, so what gets saved to Supabase / UserPrefs
 * is the unformatted "08108709628".
 */
object PhoneNumberFormatter {

    /** Strips everything except digits. Use this before saving/sending anywhere. */
    fun rawDigits(formatted: String): String = formatted.filter { it.isDigit() }

    /** Formats raw digits as "0810 870 9628" (groups of 4-3-4). */
    fun format(raw: String): String {
        val digits = raw.filter { it.isDigit() }.take(11)
        val sb = StringBuilder()
        for (i in digits.indices) {
            if (i == 4 || i == 7) sb.append(' ')
            sb.append(digits[i])
        }
        return sb.toString()
    }

    /**
     * Attaches live formatting to a TextInputEditText.
     * Call this once, e.g. in onCreate(): PhoneNumberFormatter.attachTo(whatsappInput)
     */
    fun attachTo(editText: TextInputEditText) {
        editText.addTextChangedListener(object : TextWatcher {
            private var isEditing = false
            private var previousRaw = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isEditing || s == null) return
                isEditing = true

                val raw = s.toString().filter { it.isDigit() }.take(11)
                if (raw == previousRaw) {
                    isEditing = false
                    return
                }
                previousRaw = raw

                val formatted = format(raw)
                if (formatted != s.toString()) {
                    editText.setText(formatted)
                    editText.setSelection(formatted.length)
                }
                isEditing = false
            }
        })
    }
}
