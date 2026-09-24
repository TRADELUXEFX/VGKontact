package com.vgkontact.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.NumberFormat
import java.util.Locale

/**
 * Withdraw request form - reached from WalletActivity's Withdraw button.
 * Collects bank account details only; the request always covers the
 * user's entire available balance (no partial withdrawals). Validates
 * fields client-side, then calls WalletSync.requestWithdrawal()
 * (request_withdrawal RPC), which re-validates everything server-side.
 */
class WithdrawActivity : BaseActivity() {

    companion object {
        const val EXTRA_AVAILABLE_BALANCE = "available_balance"
    }

    private lateinit var availableText: TextView
    private lateinit var minHintText: TextView

    private lateinit var accountNumberLayout: TextInputLayout
    private lateinit var accountNumberInput: TextInputEditText
    private lateinit var bankNameLayout: TextInputLayout
    private lateinit var bankNameInput: TextInputEditText
    private lateinit var accountNameLayout: TextInputLayout
    private lateinit var accountNameInput: TextInputEditText

    private lateinit var submitButton: Button
    private lateinit var progressBar: ProgressBar

    private var submitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        val availableBalance = intent.getLongExtra(EXTRA_AVAILABLE_BALANCE, 0L)

        availableText = findViewById(R.id.withdrawAvailableText)
        minHintText = findViewById(R.id.withdrawMinHintText)

        accountNumberLayout = findViewById(R.id.withdrawAccountNumberLayout)
        accountNumberInput = findViewById(R.id.withdrawAccountNumberInput)
        bankNameLayout = findViewById(R.id.withdrawBankNameLayout)
        bankNameInput = findViewById(R.id.withdrawBankNameInput)
        accountNameLayout = findViewById(R.id.withdrawAccountNameLayout)
        accountNameInput = findViewById(R.id.withdrawAccountNameInput)

        submitButton = findViewById(R.id.withdrawSubmitButton)
        progressBar = findViewById(R.id.withdrawProgressBar)

        availableText.text = "${naira(availableBalance)} available"
        minHintText.text = "Your full available balance will be withdrawn"

        accountNumberInput.clearErrorOnEdit(accountNumberLayout)
        bankNameInput.clearErrorOnEdit(bankNameLayout)
        accountNameInput.clearErrorOnEdit(accountNameLayout)

        submitButton.setOnClickListener { submit() }
    }

    private fun TextInputEditText.clearErrorOnEdit(layout: TextInputLayout) {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                layout.error = null
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun submit() {
        if (submitting) return

        val accountNumber = accountNumberInput.text.toString().trim()
        val bankName = bankNameInput.text.toString().trim()
        val accountName = accountNameInput.text.toString().trim()

        var hasError = false

        if (accountNumber.length != 10 || !accountNumber.all { it in '0'..'9' }) {
            accountNumberLayout.error = "Enter a valid account number"
            hasError = true
        }
        if (bankName.isEmpty()) {
            bankNameLayout.error = "Enter your bank name"
            hasError = true
        }
        if (accountName.isEmpty()) {
            accountNameLayout.error = "Enter the name on the account"
            hasError = true
        }

        if (hasError) return

        setLoading(true)
        WalletSync.requestWithdrawal(
            this,
            accountNumber = accountNumber,
            bankName = bankName,
            accountName = accountName
        ) { result ->
            runOnUiThread {
                setLoading(false)
                when (result) {
                    is WalletSync.WithdrawResult.Success -> {
                        Toast.makeText(
                            this,
                            "Withdrawal request submitted",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    is WalletSync.WithdrawResult.Rejected -> {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                    is WalletSync.WithdrawResult.NetworkError -> {
                        Toast.makeText(
                            this,
                            "No internet connection. Check your connection and try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is WalletSync.WithdrawResult.ServerError -> {
                        // The connection worked but the server errored. The
                        // request may have been saved, so send the user to
                        // check the wallet instead of blindly retrying.
                        Toast.makeText(
                            this,
                            "Something went wrong on our side. Please check your wallet before trying again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        submitting = loading
        submitButton.isEnabled = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun naira(amount: Long): String =
        "₦" + NumberFormat.getNumberInstance(Locale.US).format(amount)
}
