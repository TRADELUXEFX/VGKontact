package com.vgkontact.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Placeholder screen for the upgrade flow.
 * Replace this with your actual pricing/checkout UI when ready.
 */
class UpgradePlanActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upgrade_plan)

        Toast.makeText(this, "Upgrade flow coming soon", Toast.LENGTH_SHORT).show()
    }
}
