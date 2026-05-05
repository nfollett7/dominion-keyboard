package com.follett.keyboard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.follett.keyboard.R
import com.follett.keyboard.data.repository.KeyboardRepository
import com.follett.keyboard.utils.PrefsManager
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * MainActivity — App home screen and setup wizard.
 *
 * Guides the user through:
 *  1. Enabling Dominion Keyboard as an input method
 *  2. Setting Dominion Keyboard as the default keyboard
 *  3. Entering their OpenAI API key
 *
 * Also shows live usage statistics and navigation to history.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefsManager: PrefsManager
    private lateinit var repository: KeyboardRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefsManager = PrefsManager(this)
        repository = KeyboardRepository(this)

        setupButtons()
        loadApiKey()
        loadStats()
        requestMicPermission()
    }

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    private fun setupButtons() {
        // Step 1: Open Input Method Settings
        findViewById<Button>(R.id.btn_enable_keyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        // Step 2: Show Input Method Picker (set as default)
        findViewById<Button>(R.id.btn_set_default).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // Step 3: Save API Key
        findViewById<Button>(R.id.btn_save_api_key).setOnClickListener {
            val key = findViewById<TextInputEditText>(R.id.et_api_key).text?.toString()?.trim()
            if (!key.isNullOrBlank()) {
                prefsManager.setApiKey(key)
                Toast.makeText(this, getString(R.string.api_key_saved), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter a valid API key", Toast.LENGTH_SHORT).show()
            }
        }

        // Navigate to History
        findViewById<Button>(R.id.btn_view_history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Navigate to Settings
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun loadApiKey() {
        val key = prefsManager.getApiKey()
        if (!key.isNullOrBlank()) {
            // Show masked key as hint, don't fill the field
            // This prevents users from accidentally saving the masked version
            val masked = "sk-..." + key.takeLast(4)
            val editText = findViewById<TextInputEditText>(R.id.et_api_key)
            editText.hint = "Key saved: $masked"
            editText.setText("")  // Keep field empty so they don't re-save masked text
        }
    }

    private fun loadStats() {
        lifecycleScope.launch {
            try {
                val stats = repository.getStats()
                findViewById<TextView>(R.id.stat_keystrokes).text =
                    formatNumber(stats.totalKeystrokes)
                findViewById<TextView>(R.id.stat_words).text =
                    formatNumber(stats.totalWords)
                findViewById<TextView>(R.id.stat_sessions).text =
                    formatNumber(stats.totalSessions)
            } catch (e: Exception) {
                // Stats are non-critical
            }
        }
    }

    private fun formatNumber(n: Long): String = when {
        n >= 1_000_000 -> "${n / 1_000_000}M"
        n >= 1_000 -> "${n / 1_000}K"
        else -> n.toString()
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
        }
    }
}
