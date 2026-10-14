package com.raqamsaree.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePress()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePress()
    }

    private fun handlePress() {
        val service = RaqamAccessibilityService.instance
        if (service == null) {
            Toast.makeText(
                this,
                "فعّل خدمة \"رقم سريع\" من الخدمات المثبتة، ثم اضغط بكسبي من جديد",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            service.toggleSelectionMode()
        }
        finish()
    }
}
