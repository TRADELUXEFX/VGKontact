package com.vgkontact.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

/**
 * Key redemption screen. User types in a code (given to them by the admin);
 * on success, the unlocked groups get merged into their extra_groups server
 * side (see redeem_key() Postgres function / SheetSync.redeemKey()), and the
 * next Sync on the main menu will pull contacts from those groups too.
 */
class UpgradePlanActivity : AppCompatActivity() {

    private lateinit var keyCodeInput: TextInputEditText
    private lateinit var redeemKeyButton: Button
    private lateinit var redeemProgressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upgrade_plan)

        keyCodeInput = findViewById(R.id.keyCodeInput)
        redeemKeyButton = findViewById(R.id.redeemKeyButton)
        redeemProgressBar = findViewById(R.id.redeemProgressBar)

        redeemKeyButton.setOnClickListener {
            val code = keyCodeInput.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, getString(R.string.key_redeem_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!SheetSync.isOnline(this)) {
                Toast.makeText(this, getString(R.string.key_redeem_error), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            setLoading(true)
            SheetSync.redeemKey(this, code) { unlockedGroups ->
                runOnUiThread {
                    setLoading(false)
                    if (unlockedGroups != null && unlockedGroups.isNotEmpty()) {
                        Toast.makeText(
                            this,
                            getString(R.string.key_redeem_success, unlockedGroups.size),
                            Toast.LENGTH_LONG
                        ).show()
                        keyCodeInput.text?.clear()
                    } else {
                        Toast.makeText(this, getString(R.string.key_redeem_invalid), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        redeemKeyButton.isEnabled = !loading
        redeemProgressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
