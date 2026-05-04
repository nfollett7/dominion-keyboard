package com.follett.keyboard.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.follett.keyboard.R
import com.follett.keyboard.data.model.KeystrokeLog
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.io.FileWriter

/**
 * HistoryActivity — Full keyboard input history dashboard.
 *
 * Features:
 *  - Scrollable list of all typed words, dictations, and translations
 *  - Real-time search filtering
 *  - CSV export
 *  - Clear all history with confirmation
 *  - Stats strip showing total entry count
 */
class HistoryActivity : AppCompatActivity() {

    private val viewModel: HistoryViewModel by viewModels()
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        setupButtons()
        observeData()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter()
        val rv = findViewById<RecyclerView>(R.id.rv_history)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
    }

    private fun setupSearch() {
        val searchField = findViewById<TextInputEditText>(R.id.et_search)
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupButtons() {
        // Export CSV
        findViewById<Button>(R.id.btn_export).setOnClickListener {
            viewModel.prepareExport()
        }

        // Clear All
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.history_confirm_clear))
                .setMessage("This will permanently delete all ${adapter.itemCount} logged entries.")
                .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                    viewModel.clearAll()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun observeData() {
        // Observe word logs
        viewModel.logs.observe(this) { logs ->
            adapter.submitList(logs)
            updateEmptyState(logs.isEmpty())
            updateCount(logs.size)
        }

        // Observe clear completion
        viewModel.clearComplete.observe(this) { cleared ->
            if (cleared == true) {
                Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
            }
        }

        // Observe export data
        viewModel.exportData.observe(this) { logs ->
            if (!logs.isNullOrEmpty()) {
                exportToCsv(logs)
            } else {
                Toast.makeText(this, "Nothing to export", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        val emptyView = findViewById<TextView>(R.id.tv_empty)
        val rv = findViewById<RecyclerView>(R.id.rv_history)
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rv.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateCount(count: Int) {
        val totalView = findViewById<TextView>(R.id.tv_total_count)
        totalView.text = "$count entries"
    }

    private fun exportToCsv(logs: List<KeystrokeLog>) {
        try {
            val file = File(cacheDir, "dominion_history_export.csv")
            val writer = FileWriter(file)

            // CSV header
            writer.write("ID,Type,Value,App,Timestamp\n")

            // Data rows
            logs.forEach { log ->
                val escapedValue = log.keystrokeValue.replace("\"", "\"\"")
                writer.write("${log.id},${log.keystrokeType},\"$escapedValue\",${log.appPackage},${log.timestamp}\n")
            }
            writer.close()

            // Share the file
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Dominion Keyboard History Export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Export keyboard history"))

        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
