package com.follett.keyboard.ui

import android.os.Bundle
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.follett.keyboard.R
import com.follett.keyboard.utils.PrefsManager

/**
 * SettingsActivity — Keyboard configuration screen.
 *
 * Allows the user to toggle:
 *  - Haptic feedback on key press
 *  - Sound feedback
 *  - Auto-capitalize first letter
 *  - Auto-punctuate (double-space = period)
 *  - Smart Compose (AI-powered suggestions)
 *  - Keystroke logging on/off
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PrefsManager(this)

        setupToolbar()
        loadSettings()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar_settings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun loadSettings() {
        bindSwitch(R.id.switch_haptic, prefs.isHapticEnabled()) { prefs.setHapticEnabled(it) }
        bindSwitch(R.id.switch_sound, prefs.isSoundEnabled()) { prefs.setSoundEnabled(it) }
        bindSwitch(R.id.switch_auto_cap, prefs.isAutoCapitalizeEnabled()) { prefs.setAutoCapitalize(it) }
        bindSwitch(R.id.switch_auto_punct, prefs.isAutoPunctuateEnabled()) { prefs.setAutoPunctuate(it) }
        bindSwitch(R.id.switch_smart_compose, prefs.isSmartComposeEnabled()) { prefs.setSmartCompose(it) }
        bindSwitch(R.id.switch_logging, prefs.isKeystrokeLoggingEnabled()) { enabled ->
            prefs.setKeystrokeLogging(enabled)
            val msg = if (enabled) "Keystroke logging enabled" else "Keystroke logging disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindSwitch(id: Int, initialValue: Boolean, onChange: (Boolean) -> Unit) {
        val switch = findViewById<Switch>(id) ?: return
        switch.isChecked = initialValue
        switch.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            onChange(isChecked)
        }
    }
}
