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
 * Collects bank account details, validates them client-side, then calls
 * WalletSync.requestWithdrawal() (request_withdrawal RPC).
 *
 * EXTRA_AVAILABLE_BALANCE is required - the caller (WalletActivity)
 * already has the real balance loaded from the server, so this screen
 * doesn't need its own network round trip just to show "X available"
 * and to validate the amount doesn't exceed it. The RPC still
 * re-validates server-side regardless (see WalletSync.requestWithdrawal
 * doc comment) - this client-side check is just so the user gets
 * immediate feedback instead of a round trip for an amount that was
 * obviously too high.
 */
class WithdrawActivity : BaseActivity() {

    companion object {
        const val EXTRA_AVAILABLE_BALANCE = "available_balance"
        private const val MIN_WITHDRAWAL = 1_000L
    }

    private lateinit var availableText: TextView
    private lateinit var minHintText: TextView

    private lateinit var accountNumberLayout: TextInputLayout
    private lateinit var accountNumberInput: TextInputEditText
    private lateinit var bankNameLayout: TextInputLayout
    private lateinit var bankNameInput: TextInputEditText
    private lateinit var accountNameLayout: TextInputLayout
    private lateinit var accountNameInput: TextInputEditText
    private lateinit var amountLayout: TextInputLayout
    private lateinit var amountInput: TextInputEditText

    private lateinit var submitButton: Button
    private lateinit var progressBar: ProgressBar

    private var availableBalance: Long = 0L
    private var submitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        availableBalance = intent.getLongExtra(EXTRA_AVAILABLE_BALANCE, 0L)

        availableText = findViewById(R.id.withdrawAvailableText)
        minHintText = findViewById(R.id.withdrawMinHintText)

        accountNumberLayout = findViewById(R.id.withdrawAccountNumberLayout)
        accountNumberInput = findViewById(R.id.withdrawAccountNumberInput)
        bankNameLayout = findViewById(R.id.withdrawBankNameLayout)
        bankNameInput = findViewById(R.id.withdrawBankNameInput)
        accountNameLayout = findViewById(R.id.withdrawAccountNameLayout)
        accountNameInput = findViewById(R.id.withdrawAccountNameInput)
        amountLayout = findViewById(R.id.withdrawAmountLayout)
        amountInput = findViewById(R.id.withdrawAmountInput)

        submitButton = findViewById(R.id.withdrawSubmitButton)
        progressBar = findViewById(R.id.withdrawProgressBar)

        availableText.text = "${naira(availableBalance)} available"
        minHintText.text = "Minimum withdrawal ${naira(MIN_WITHDRAWAL)}"

        // Pre-fill with the full available balance so the common case
        // (withdraw everything) needs no typing - the user can still
        // edit it down.
        amountInput.setText(availableBalance.toString())

        // Clear each field's error the moment the user edits it, rather
        // than leaving a stale error showing after they've already
        // started fixing it.
        accountNumberInput.clearErrorOnEdit(accountNumberLayout)
        bankNameInput.clearErrorOnEdit(bankNameLayout)
        accountNameInput.clearErrorOnEdit(accountNameLayout)
        amountInput.clearErrorOnEdit(amountLayout)

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
        val amountText = amountInput.text.toString().trim()

        var hasError = false

        if (accountNumber.length < 10) {
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

        val amount = amountText.toLongOrNull()
        if (amount == null || amount <= 0) {
            amountLayout.error = "Enter an amount"
            hasError = true
        } else if (amount < MIN_WITHDRAWAL) {
            amountLayout.error = "Minimum withdrawal is ${naira(MIN_WITHDRAWAL)}"
            hasError = true
        } else if (amount > availableBalance) {
            amountLayout.error = "You only have ${naira(availableBalance)} available"
            hasError = true
        }

        if (hasError || amount == null) return

        setLoading(true)
        WalletSync.requestWithdrawal(
            this,
            accountNumber = accountNumber,
            bankName = bankName,
            accountName = accountName,
            amount = amount
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
