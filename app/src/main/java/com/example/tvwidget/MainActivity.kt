package com.example.tvwidget

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.example.tvwidget.data.CatalogueShow
import com.example.tvwidget.data.TrackedShow
import com.example.tvwidget.data.TrackedShowsRepository
import com.example.tvwidget.data.TvMazeApi
import com.example.tvwidget.work.AnticipatedSyncWorker
import java.net.HttpURLConnection
import java.net.URL

/**
 * Host activity. The product is the home-screen widget; this screen exists so the app is
 * launchable, so a whole-row tap in the TODAY feed has somewhere to land, and — since a home-screen
 * widget cannot host a text field — so CATALOGUE's free-text search has somewhere to run.
 */
class MainActivity : Activity() {

    private lateinit var resultsAdapter: ResultsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val show = intent?.getStringExtra(EXTRA_SHOW_TITLE)
        val episode = intent?.getStringExtra(EXTRA_EPISODE_CODE)
        val openSearch = intent?.getBooleanExtra(EXTRA_OPEN_SEARCH, false) ?: false

        when {
            openSearch -> setContentView(buildSearchScreen())
            show != null -> setContentView(buildDeepLinkScreen(show, episode))
            else -> setContentView(buildHomeScreen())
        }
    }

    // -- Screens -------------------------------------------------------------------------------

    private fun buildDeepLinkScreen(show: String, episode: String?): View {
        val root = centeredColumn()
        root.addView(label(text = "$show${episode?.let { " · $it" }.orEmpty()}", sizeSp = 22f, color = Color.WHITE))
        root.addView(label(text = "Deep link received from the widget.", sizeSp = 13f, color = ACCENT))
        return root
    }

    private fun buildHomeScreen(): View {
        val root = centeredColumn()
        root.addView(label(text = getString(R.string.app_name), sizeSp = 22f, color = Color.WHITE))
        root.addView(label(text = "Add the 5x2 \"TV Releases\" widget to your home screen.", sizeSp = 13f, color = ACCENT))
        root.addView(
            Button(this).apply {
                text = "SEARCH ALL SHOWS"
                setOnClickListener { setContentView(buildSearchScreen()) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 32; gravity = Gravity.CENTER }
            }
        )
        return root
    }

    /** CATALOGUE's search: the reason this Activity exists at all — widgets cannot host `EditText`. */
    private fun buildSearchScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            setPadding(32, 64, 32, 32)
        }

        val title = TextView(this).apply {
            text = "SEARCH SHOWS"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        root.addView(title)

        val input = EditText(this).apply {
            hint = "Show title…"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }
        root.addView(input)

        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#888888"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 12, 0, 0)
        }
        root.addView(status)

        resultsAdapter = ResultsAdapter()
        val list = ListView(this).apply {
            adapter = resultsAdapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = 12 }
        }
        root.addView(list)

        var searchToken = 0
        val debounceHandler = android.os.Handler(mainLooper)
        var pendingSearch: Runnable? = null
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                val query = editable?.toString().orEmpty().trim()
                val token = ++searchToken

                // Debounced: a fresh keystroke cancels whatever run was still waiting to fire, so a
                // fast typist never triggers one network request per character.
                pendingSearch?.let(debounceHandler::removeCallbacks)
                if (query.length < 2) {
                    resultsAdapter.submit(emptyList())
                    status.text = ""
                    return
                }
                status.text = "Searching…"
                val runnable = Runnable {
                    Thread {
                        val results = runCatching { kotlinx.coroutines.runBlocking { TvMazeApi.search(query) } }
                            .getOrDefault(emptyList())
                            .map { it.copy(tracked = TrackedShowsRepository.isTracked(this@MainActivity, it.tvMazeId)) }
                        runOnUiThread {
                            if (token != searchToken) return@runOnUiThread // a newer keystroke superseded this one
                            resultsAdapter.submit(results)
                            status.text = if (results.isEmpty()) "No shows found." else "${results.size} result(s)."
                        }
                    }.start()
                }
                pendingSearch = runnable
                debounceHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
            }
        })

        return root
    }

    // -- CATALOGUE search results --------------------------------------------------------------

    private inner class ResultsAdapter : BaseAdapter() {
        private var items: List<CatalogueShow> = emptyList()
        private val posterCache = HashMap<Int, Bitmap?>()

        fun submit(newItems: List<CatalogueShow>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].tvMazeId.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val show = items[position]
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 16)
            }

            val poster = ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(96, 132)
                setBackgroundColor(Color.parseColor("#262626"))
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            row.addView(poster)
            loadPosterAsync(show, poster)

            val textColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 20 }
            }
            textColumn.addView(TextView(this@MainActivity).apply {
                text = show.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            textColumn.addView(TextView(this@MainActivity).apply {
                text = "${show.status} · ${show.network}"
                setTextColor(Color.parseColor("#888888"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
            row.addView(textColumn)

            row.addView(Button(this@MainActivity).apply {
                text = if (show.tracked) "− UNTRACK" else "+ TRACK"
                setOnClickListener { toggleTrack(show, this) }
            })

            return row
        }

        private fun loadPosterAsync(show: CatalogueShow, target: ImageView) {
            target.setImageDrawable(null)
            val url = show.posterUrl ?: return
            posterCache[show.tvMazeId]?.let { target.setImageBitmap(it); return }
            Thread {
                val bitmap = runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8_000
                    connection.inputStream.use(BitmapFactory::decodeStream)
                }.getOrNull()
                posterCache[show.tvMazeId] = bitmap
                if (bitmap != null) runOnUiThread { target.setImageBitmap(bitmap) }
            }.start()
        }

        private fun toggleTrack(show: CatalogueShow, button: Button) {
            val nowTracked = !show.tracked
            if (nowTracked) {
                TrackedShowsRepository.add(
                    this@MainActivity,
                    TrackedShow(show.tvMazeId, show.title, show.network, show.posterUrl),
                )
            } else {
                TrackedShowsRepository.remove(this@MainActivity, show.tvMazeId)
            }
            items = items.map { if (it.tvMazeId == show.tvMazeId) it.copy(tracked = nowTracked) else it }
            notifyDataSetChanged()
            button.text = if (nowTracked) "− UNTRACK" else "+ TRACK"
            AnticipatedSyncWorker.runOnce(this@MainActivity)
            Toast.makeText(
                this@MainActivity,
                if (nowTracked) "Tracking ${show.title}" else "Stopped tracking ${show.title}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // -- View helpers ----------------------------------------------------------------------------

    private fun centeredColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor("#0B0B0B"))
        setPadding(48, 48, 48, 48)
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
        const val EXTRA_OPEN_SEARCH = "open_search"
        private const val SEARCH_DEBOUNCE_MS = 300L
        private val ACCENT = Color.parseColor("#F2C81E")
    }
}
