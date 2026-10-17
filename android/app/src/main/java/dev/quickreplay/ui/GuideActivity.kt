package dev.quickreplay.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.quickreplay.R
import dev.quickreplay.overlay.TouchMarkers

/** In-app user guide. Texts live in res/values/strings.xml (guide_*) so they can be translated. */
class GuideActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(context, 16)
            setPadding(pad, Ui.dp(context, 24), pad, pad)
        }
        content.addView(Ui.text(this, getString(R.string.guide), 22f, bold = true))
        content.addView(Ui.text(this, getString(R.string.guide_intro), 14f, Ui.MUTED), Ui.spacedParams(this, 6))

        section(content, R.string.guide_service_title, R.string.guide_service_body)
        section(content, R.string.guide_record_title, R.string.guide_record_body).apply {
            addView(Ui.text(context, getString(R.string.guide_legend_title), 14f, bold = true), Ui.spacedParams(context, 14))
            legend(this, TouchMarkers.TAP_COLOR, R.string.guide_legend_tap)
            legend(this, TouchMarkers.LONG_PRESS_COLOR, R.string.guide_legend_long)
            legend(this, TouchMarkers.SWIPE_COLOR, R.string.guide_legend_swipe)
            legend(this, BUSY_COLOR, R.string.guide_legend_busy)
            addView(Ui.text(context, getString(R.string.guide_legend_label), 13f, Ui.MUTED), Ui.spacedParams(context, 8))
        }
        section(content, R.string.guide_play_title, R.string.guide_play_body)
        section(content, R.string.guide_panel_title, R.string.guide_panel_body)
        section(content, R.string.guide_repeat_title, R.string.guide_repeat_body)
        section(content, R.string.guide_combo_title, R.string.guide_combo_body)
        section(content, R.string.guide_files_title, R.string.guide_files_body)
        section(content, R.string.guide_tips_title, R.string.guide_tips_body)
        content.addView(Ui.buttonRow(this, Ui.button(this, "Back", primary = true) { finish() }), Ui.spacedParams(this, 20))

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Ui.BG)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun section(parent: LinearLayout, title: Int, body: Int): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.text(this, getString(title), 16f, bold = true))
        card.addView(Ui.text(this, getString(body), 14f).apply { setLineSpacing(0f, 1.25f) }, Ui.spacedParams(this, 8))
        parent.addView(card, Ui.spacedParams(this, 14))
        return card
    }

    private fun legend(parent: LinearLayout, color: Int, text: Int) {
        val row = Ui.row(this).apply {
            gravity = Gravity.TOP
            // Baseline alignment would push the wrapped text down and clip its last line.
            isBaselineAligned = false
        }
        row.addView(Ui.text(this, "●", 18f, color), Ui.gapParams(this))
        row.addView(
            Ui.text(this, getString(text), 13f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        parent.addView(row, Ui.spacedParams(this, 6))
    }

    private companion object {
        const val BUSY_COLOR = 0xFFFFE066.toInt()
    }
}
