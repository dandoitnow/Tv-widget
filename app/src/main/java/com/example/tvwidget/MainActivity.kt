package com.example.tvwidget

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Host activity. The product is the home-screen widget; this screen exists so the app is
 * launchable and so a whole-row tap in the TODAY feed has somewhere to land.
 *
 * Replace the body with a navigation to the real episode screen, reading the show and episode from
 * the intent extras the widget attaches.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val show = intent?.getStringExtra(EXTRA_SHOW_TITLE)
        val episode = intent?.getStringExtra(EXTRA_EPISODE_CODE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            setPadding(48, 48, 48, 48)
        }

        root.addView(
            label(
                text = if (show != null) "$show${episode?.let { " · $it" }.orEmpty()}" else getString(R.string.app_name),
                sizeSp = 22f,
                color = Color.WHITE,
            )
        )
        root.addView(
            label(
                text = if (show != null) {
                    "Deep link received from the widget."
                } else {
                    "Add the 5x2 \"TV Releases\" widget to your home screen."
                },
                sizeSp = 13f,
                color = Color.parseColor("#F2C81E"),
            )
        )

        setContentView(root)
    }

    private fun label(text: String, sizeSp: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 16 }
    }

    companion object {
        const val EXTRA_SHOW_TITLE = "show_title"
        const val EXTRA_EPISODE_CODE = "episode_code"
    }
}
