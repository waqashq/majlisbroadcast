package org.waqashq.majlisbroadcast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The four-tab bottom nav (Broadcast/Recordings/History/Settings), shared
 * across all four top-level screens so it stays visible no matter which tab
 * you're on -- previously only MainActivity built one, so navigating to
 * Recordings/History/Settings lost it entirely.
 *
 * Each of those four Activities is declared launchMode="singleTask" in the
 * manifest, and tapping a tab here targets it with FLAG_ACTIVITY_CLEAR_TOP:
 * if that tab's Activity is already running anywhere in the task it's
 * brought to front (state preserved, no new instance, nothing above it left
 * stacked) instead of piling up a new copy every time you switch tabs.
 * Transition animation is disabled so switching feels like one persistent
 * bar rather than four separate screens.
 */
enum class NavTab { BROADCAST, RECORDINGS, HISTORY, SETTINGS }

fun Context.buildBottomNav(active: NavTab): LinearLayout {
    val nav = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(UiTheme.STUDIO_CARD_BG)
    }
    val tabParams = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
    nav.addView(navTab(R.drawable.ic_radio, getString(R.string.nav_broadcast), NavTab.BROADCAST, active), tabParams())
    nav.addView(navTab(R.drawable.ic_mic, getString(R.string.nav_recordings), NavTab.RECORDINGS, active), tabParams())
    nav.addView(navTab(R.drawable.ic_history, getString(R.string.nav_history), NavTab.HISTORY, active), tabParams())
    nav.addView(navTab(R.drawable.ic_settings_sliders, getString(R.string.btn_settings), NavTab.SETTINGS, active), tabParams())
    return nav
}

private fun Context.navTab(iconRes: Int, label: String, tab: NavTab, active: NavTab): LinearLayout {
    val isActive = tab == active
    val tabView = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, 22, 0, 22)
        isClickable = !isActive
        isFocusable = !isActive
    }
    val icon = ImageView(this).apply {
        setImageResource(iconRes)
        setColorFilter(if (isActive) UiTheme.STUDIO_BORDER_TEAL else UiTheme.STUDIO_TEXT_MUTED)
        layoutParams = LinearLayout.LayoutParams(44, 44)
    }
    val text = TextView(this).apply {
        text = label
        textSize = 11f
        setTypeface(typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(if (isActive) UiTheme.STUDIO_BORDER_TEAL else UiTheme.STUDIO_TEXT_MUTED)
        gravity = Gravity.CENTER
    }
    tabView.addView(icon, LinearLayout.LayoutParams(44, 44))
    tabView.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6 })
    if (!isActive) {
        tabView.setOnClickListener { navigateToTab(tab) }
    }
    return tabView
}

private fun Context.navigateToTab(tab: NavTab) {
    val cls = when (tab) {
        NavTab.BROADCAST -> MainActivity::class.java
        NavTab.RECORDINGS -> RecordingsActivity::class.java
        NavTab.HISTORY -> HistoryActivity::class.java
        NavTab.SETTINGS -> SettingsActivity::class.java
    }
    val intent = Intent(this, cls).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    startActivity(intent)
    if (this is Activity) {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
