package com.vgkontact.app

import android.Manifest
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vgkontact.app.databinding.ActivityOnboardingBinding
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    companion object {
        private const val MAX_DIGITS = 11
    }

    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> readPickedContactNumber(uri) }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op: permissions are optional, flow continues either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (UserPrefs.hasSavedNumber(this)) {
            goToMainMenu()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pickContactButton.setOnClickListener {
            launchContactPicker()
        }

        binding.continueButton.setOnClickListener {
            onContinueClicked()
        }

        attachAutoFormat(binding.whatsappInput)
        attachAutoFormat(binding.referralInput)
    }

    private fun attachAutoFormat(input: EditText) {
        input.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return
                isFormatting = true

                val formatted = formatPhoneNumber(s.toString())
                if (formatted != s.toString()) {
                    input.setText(formatted)
                    input.setSelection(formatted.length)
                }

                isFormatting = false
            }
        })
    }

    private fun formatPhoneNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }.take(MAX_DIGITS)
        val part1 = digits.take(4)
        val part2 = digits.drop(4).take(3)
        val part3 = digits.drop(7).take(4)
        return listOf(part1, part2, part3).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun launchContactPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        pickContactLauncher.launch(intent)
    }

    private fun readPickedContactNumber(contactUri: Uri) {
        val cursor: Cursor? = contentResolver.query(
            contactUri, null, null, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex >= 0) {
                    val number = it.getString(numberIndex)
                    binding.referralInput.setText(formatPhoneNumber(number))
                    binding.referralInput.setSelection(binding.referralInput.text?.length ?: 0)
                }
            }
        }
    }

    private fun onContinueClicked() {
        val whatsapp = binding.whatsappInput.text?.toString()?.trim().orEmpty()
        val referral = binding.referralInput.text?.toString()?.trim().orEmpty()

        binding.whatsappLayout.error = null
        binding.referralLayout.error = null

        var hasError = false

        val whatsappError = validatePhoneNumber(whatsapp, R.string.error_whatsapp_required)
        if (whatsappError != null) {
            binding.whatsappLayout.error = whatsappError
            hasError = true
        }

        val referralError = validatePhoneNumber(referral, R.string.error_referral_required)
        if (referralError != null) {
            binding.referralLayout.error = referralError
            hasError = true
        }

        if (hasError) return

        requestRuntimePermissions()
        syncAndContinue(normalizePhoneNumber(whatsapp), normalizePhoneNumber(referral))
    }

    private fun validatePhoneNumber(raw: String, requiredErrorRes: Int): String? {
        val digits = raw.filter { it.isDigit() }

        if (digits.isEmpty()) {
            return getString(requiredErrorRes)
        }
        if (digits.length < MAX_DIGITS) {
            return getString(R.string.error_number_too_short)
        }
        if (digits.length > MAX_DIGITS) {
            return getString(R.string.error_number_too_long)
        }

        return null
    }

    private fun normalizePhoneNumber(raw: String): String {
        return raw.filter { it.isDigit() }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun setButtonLoading(isLoading: Boolean) {
        binding.continueButton.isEnabled = !isLoading
        binding.continueButton.text = if (isLoading) "" else getString(R.string.btn_continue)
        binding.progressBar.visibility = if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun syncAndContinue(whatsapp: String, referral: String) {
        setButtonLoading(true)

        lifecycleScope.launch {
            val result = SheetSync.submit(whatsapp, referral)

            setButtonLoading(false)

            result.onSuccess {
                UserPrefs.save(this@OnboardingActivity, whatsapp, referral)
                goToMainMenu()
            }.onFailure {
                Toast.makeText(
                    this@OnboardingActivity,
                    it.message ?: getString(R.string.sync_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun goToMainMenu() {
        startActivity(Intent(this, MainMenuActivity::class.java))
        finish()
    }
}
