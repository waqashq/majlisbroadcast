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
    }

    override fun onResume() {
        super.onResume()
        // Rebuild on every resume, not just first create -- launchMode=
        // "singleTask" means returning to this tab reuses this instance, so
        // this is the only reliable place to pick up sessions logged (or
        // history cleared) while this screen was in the background.
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.STUDIO_BG)
        }

        val header = studioHeader(getString(R.string.history_title)) { finish() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 48)
        }

        val entries = SessionHistory.list(this)
        if (entries.isNotEmpty()) {
            val clearButton = studioPillButton(getString(R.string.btn_clear_all), 13f).apply {
                setTextColor(UiTheme.STUDIO_STOP_RED)
                background = UiTheme.outlinePillBackground(UiTheme.STUDIO_STOP_RED)
            }
            clearButton.setOnClickListener { confirmClearHistory() }
            content.addView(
                clearButton,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.END
                    bottomMargin = 20
                }
            )
        }
        if (entries.isEmpty()) {
            val empty = studioCard(28)
            empty.addView(studioCardBody().apply {
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
                val row = studioCard(28)
                row.addView(
                    TextView(this).apply {
                        text = dateFormat.format(Date(entry.startedAtMs))
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
                    }
                )
                row.addView(
                    studioCardBody().apply {
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
        val nav = buildBottomNav(NavTab.HISTORY)

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun confirmClearHistory() {
        androidx.appcompat.app.AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle(getString(R.string.history_clear_confirm_title))
            .setMessage(getString(R.string.history_clear_confirm_message))
            .setPositiveButton(getString(R.string.btn_clear_all)) { _, _ ->
                SessionHistory.clear(this)
                buildUi()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // header/card/cardBody/topMarginParams/formatDuration/formatMegabytes
    // moved to StudioUiKit.kt (shared with SettingsActivity/
    // RecordingsActivity, which had their own near-identical copies) --
    // refactor only, no behavior change.
}
