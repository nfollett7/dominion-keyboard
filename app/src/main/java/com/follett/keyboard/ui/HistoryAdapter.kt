package com.follett.keyboard.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.follett.keyboard.R
import com.follett.keyboard.data.model.KeystrokeLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * HistoryAdapter — RecyclerView adapter for the keyboard history dashboard.
 *
 * Displays each logged word/dictation/translation with:
 *  - Type emoji badge
 *  - The actual text value
 *  - Source app package
 *  - Formatted timestamp
 */
class HistoryAdapter : ListAdapter<KeystrokeLog, HistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<KeystrokeLog>() {
            override fun areItemsTheSame(old: KeystrokeLog, new: KeystrokeLog) = old.id == new.id
            override fun areContentsTheSame(old: KeystrokeLog, new: KeystrokeLog) = old == new
        }

        private val TIME_FORMATTER = DateTimeFormatter
            .ofPattern("MMM d, h:mm a")
            .withZone(ZoneId.systemDefault())
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val typeBadge: TextView = itemView.findViewById(R.id.tv_type_badge)
        val value: TextView = itemView.findViewById(R.id.tv_value)
        val app: TextView = itemView.findViewById(R.id.tv_app)
        val timestamp: TextView = itemView.findViewById(R.id.tv_timestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = getItem(position)

        // Type badge emoji
        holder.typeBadge.text = when (log.keystrokeType) {
            "word_complete" -> "💬"
            "whisper_dictation" -> "🎤"
            "translation_es" -> "🌐"
            "suggestion" -> "✨"
            else -> "⌨️"
        }

        // Display value
        holder.value.text = log.keystrokeValue.ifBlank { "(empty)" }

        // App name — strip com. prefix for readability
        val appName = log.appPackage
            .removePrefix("com.")
            .split(".")
            .firstOrNull() ?: log.appPackage
        holder.app.text = appName

        // Format timestamp
        holder.timestamp.text = try {
            TIME_FORMATTER.format(Instant.parse(log.timestamp))
        } catch (e: Exception) {
            log.timestamp.take(16)
        }

        // Color code by type
        val context = holder.itemView.context
        holder.value.setTextColor(
            when (log.keystrokeType) {
                "whisper_dictation" -> context.getColor(R.color.primary)
                "translation_es" -> context.getColor(R.color.accent_light)
                else -> context.getColor(R.color.text_primary)
            }
        )
    }
}
