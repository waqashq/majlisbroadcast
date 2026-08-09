package org.waqashq.majlisbroadcast

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 9: read-only log of past broadcast sessions (date, duration, peak
 * listeners, data used), sourced from SessionHistory. Purely a personal
 * record for the broadcaster -- nothing here is shared or synced anywhere.
 */
class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.STUDIO_BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 56, 32, 24)
        }
        val backArrow = TextView(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            setPadding(16, 8, 32, 8)
            isClickable = true
            setOnClickListener { finish() }
        }
        val headerTitle = TextView(this).apply {
            text = getString(R.string.history_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
        }
        header.addView(backArrow)
        header.addView(headerTitle)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 48)
        }

        val entries = SessionHistory.list(this)
        if (entries.isEmpty()) {
            val empty = card()
            empty.addView(cardBody().apply {
                text = getString(R.string.history_empty)
                gravity = Gravity.CENTER
            })
            content.addView(
                empty,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        } else {
            val dateFormat = SimpleDateFormat("MMM d, yyyy -- h:mm a", Locale.getDefault())
            entries.forEachIndexed { index, entry ->
                val row = card()
                row.addView(
                    TextView(this).apply {
                        text = dateFormat.format(Date(entry.startedAtMs))
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
                    }
                )
                row.addView(
                    cardBody().apply {
                        text = getString(
                            R.string.history_row_detail,
                            formatDuration(entry.durationMs),
                            entry.peakListeners,
                            formatMegabytes(entry.bytesUploaded)
                        )
                        gravity = Gravity.START
                        textSize = 12f
                    },
                    topMarginParams(6)
                )
                content.addView(
                    row,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = if (index == 0) 0 else 20
                    }
                )
            }
        }

        val scrollView = ScrollView(this).apply { addView(content) }

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun formatDuration(totalMs: Long): String {
        val totalSeconds = totalMs / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return String.format(Locale.US, "%.1f", mb)
    }

    // ================= Small studio-styled building blocks (mirrors SettingsActivity) =================

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = UiTheme.studioCard()
        setPadding(36, 28, 36, 28)
    }

    private fun cardBody(): TextView = TextView(this).apply {
        textSize = 13f
        setTextColor(UiTheme.STUDIO_TEXT_MUTED)
    }

    private fun topMarginParams(marginPx: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = marginPx
        }
}
