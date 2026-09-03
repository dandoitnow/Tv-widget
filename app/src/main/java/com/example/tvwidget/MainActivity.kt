package com.example.tvwidget

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.example.tvwidget.data.CatalogueShow
import com.example.tvwidget.data.FavoriteShow
import com.example.tvwidget.data.TrackedShow
import com.example.tvwidget.data.TrackedShowsRepository
import com.example.tvwidget.data.TvMazeApi
import com.example.tvwidget.data.WidgetState
import com.example.tvwidget.widget.TvWidget
import com.example.tvwidget.work.AnticipatedSyncWorker
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking

/**
 * Host activity. The product is the home-screen widget; this screen exists so the app is
 * launchable, so a whole-row tap in the TODAY feed has somewhere to land, and so CATALOGUE — search,
 * Favorites, and Recommended — has somewhere to run at all. None of that can live in the widget: a
 * `RemoteViews` has no `EditText` for real search, and rendering Favorites' full detail plus a
 * browseable Recommended list there was more chrome than a home-screen widget can reasonably hold.
 */
class MainActivity : Activity() {

    private lateinit var resultsAdapter: ResultsAdapter
    private var selectedCatalogueTab = CatalogueTab.TRACKED

    /**
     * TRACKED's working set for this window only. Seeded once from [TrackedShowsRepository] the
     * first time TRACKED is shown; after that, tracking or
     * untracking a show updates this list in place — untracking flips a show's button back to
     * "+ TRACK" without removing the row, so it stays visible until this window is closed. A fresh
     * [MainActivity] instance (opening the app again) reseeds from the repository, which is the only
     * point an untracked show actually drops out of view.
     */
    private val sessionTrackedShows = mutableListOf<CatalogueShow>()
    private var sessionTrackedShowsSeeded = false

    private enum class CatalogueTab { TRACKED, FAVORITES }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val show = intent?.getStringExtra(EXTRA_SHOW_TITLE)
        val episode = intent?.getStringExtra(EXTRA_EPISODE_CODE)
        val openCatalogue = intent?.getBooleanExtra(EXTRA_OPEN_CATALOGUE, false) ?: false

        when {
            openCatalogue -> setContentView(buildCatalogueScreen())
            // Tapping a TODAY row's title is meant to open that episode's IMDb page — Glance's
            // actionStartActivity only launches a typed Activity, not an arbitrary Intent, so the
            // widget starts this Activity with the show/episode extras and this is the pass-through
            // that actually fires the ACTION_VIEW intent. Falls back to the old placeholder screen
            // only if nothing on the device can handle it (no browser/IMDb app at all).
            show != null -> if (openImdbSearch(show, episode)) finish() else setContentView(buildDeepLinkScreen(show, episode))
            else -> setContentView(buildHomeScreen())
        }
    }

    /** Opens an IMDb search for the show + episode code; returns false if nothing could handle it. */
    private fun openImdbSearch(show: String, episode: String?): Boolean {
        val query = listOfNotNull(show, episode).joinToString(" ")
        val url = "https://www.imdb.com/find/?q=" + Uri.encode(query)
        return runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
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
                text = "OPEN CATALOGUE"
                setOnClickListener { setContentView(buildCatalogueScreen()) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 32; gravity = Gravity.CENTER }
            }
        )
        return root
    }

    /**
     * CATALOGUE: a search field, and two tabs underneath it — TRACKED (today's trending shows via
     * TVMaze, tracked ones pinned first, with one-tap track/untrack) and FAVORITES (favorited
     * episodes, read from and written back to the widget's own Glance state). Typing 2+ characters
     * into the search field covers whichever tab is showing with search results; clearing it back
     * out restores that tab.
     */
    private fun buildCatalogueScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            setPadding(32, 64, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "CATALOGUE"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })

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

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        val trackedTabButton = Button(this).apply {
            text = "TRACKED"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val favoritesTabButton = Button(this).apply {
            text = "FAVORITES"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tabRow.addView(trackedTabButton)
        tabRow.addView(favoritesTabButton)
        root.addView(tabRow)

        val favoritesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val favoritesScroll = ScrollView(this).apply {
            addView(favoritesContainer)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = 12 }
        }
        root.addView(favoritesScroll)

        resultsAdapter = ResultsAdapter()
        val catalogueList = ListView(this).apply {
            adapter = resultsAdapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = 12 }
        }
        root.addView(catalogueList)

        fun highlightTabs() {
            val selectedColor = Color.parseColor("#F2C81E")
            val unselectedColor = Color.parseColor("#444444")
            trackedTabButton.setBackgroundColor(
                if (selectedCatalogueTab == CatalogueTab.TRACKED) selectedColor else unselectedColor
            )
            favoritesTabButton.setBackgroundColor(
                if (selectedCatalogueTab == CatalogueTab.FAVORITES) selectedColor else unselectedColor
            )
        }

        fun showCurrentTab() {
            highlightTabs()
            when (selectedCatalogueTab) {
                CatalogueTab.TRACKED -> {
                    favoritesScroll.visibility = View.GONE
                    catalogueList.visibility = View.VISIBLE
                    loadTrackedAsync(status)
                }
                CatalogueTab.FAVORITES -> {
                    favoritesScroll.visibility = View.VISIBLE
                    catalogueList.visibility = View.GONE
                    loadFavoritesAsync(status, favoritesContainer)
                }
            }
        }

        trackedTabButton.setOnClickListener {
            selectedCatalogueTab = CatalogueTab.TRACKED
            if (input.text.length < 2) showCurrentTab() else highlightTabs()
        }
        favoritesTabButton.setOnClickListener {
            selectedCatalogueTab = CatalogueTab.FAVORITES
            if (input.text.length < 2) showCurrentTab() else highlightTabs()
        }

        showCurrentTab()

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
                    showCurrentTab()
                    return
                }
                favoritesScroll.visibility = View.GONE
                catalogueList.visibility = View.VISIBLE
                status.text = "Searching…"
                val runnable = Runnable {
                    Thread {
                        val results = runCatching { runBlocking { TvMazeApi.search(query) } }
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

    /** Populates [container] with favorited episodes grouped by show, reading the widget's Glance state. */
    private fun loadFavoritesAsync(status: TextView, container: LinearLayout) {
        status.text = "Loading…"
        Thread {
            val shows = runCatching { runBlocking { readWidgetFavoriteShows() } }.getOrDefault(emptyList())
            runOnUiThread {
                renderFavorites(container, shows)
                status.text = if (shows.isEmpty()) "No favorites yet." else "${shows.sumOf { it.episodes.size }} favorited."
            }
        }.start()
    }

    private suspend fun readWidgetFavoriteShows(): List<FavoriteShow> {
        val glanceId = GlanceAppWidgetManager(this).getGlanceIds(TvWidget::class.java).firstOrNull()
            ?: return emptyList()
        val prefs = getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)
        return WidgetState.favoriteShows(WidgetState.favorites(prefs), WidgetState.rewatchLog(prefs))
    }

    private fun renderFavorites(container: LinearLayout, shows: List<FavoriteShow>) {
        container.removeAllViews()
        if (shows.isEmpty()) {
            container.addView(label(text = "NO FAVORITES YET", sizeSp = 14f, color = Color.parseColor("#888888")))
            return
        }
        shows.forEach { show ->
            container.addView(TextView(this).apply {
                text = "${show.title}   ·   Watched x${show.rewatchCount}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, 28, 0, 8)
            })
            show.episodes.forEach { episode ->
                container.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(24, 8, 0, 8)
                        addView(TextView(this@MainActivity).apply {
                            text = "${episode.episodeCode} · ${episode.label}"
                            setTextColor(Color.parseColor("#AAAAAA"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        addView(Button(this@MainActivity).apply {
                            text = "★ UNFAVORITE"
                            setOnClickListener {
                                unfavoriteFromApp(show.title, episode.episodeCode) {
                                    val refreshed = shows.mapNotNull { s ->
                                        if (s.title != show.title) s
                                        else s.copy(episodes = s.episodes.filterNot { it.episodeCode == episode.episodeCode })
                                            .takeIf { it.episodes.isNotEmpty() }
                                    }
                                    renderFavorites(container, refreshed)
                                }
                            }
                        })
                    }
                )
            }
        }
    }

    /** Unfavorites one episode by writing straight to the widget's Glance state, then redraws it. */
    private fun unfavoriteFromApp(showTitle: String, episodeCode: String, onDone: () -> Unit) {
        Thread {
            runBlocking {
                val glanceIds = GlanceAppWidgetManager(this@MainActivity).getGlanceIds(TvWidget::class.java)
                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(this@MainActivity, glanceId) { prefs ->
                        val current = WidgetState.favorites(prefs)
                        val updated = current.filterNot { it.showTitle == showTitle && it.episodeCode == episodeCode }
                        prefs[WidgetState.FAVORITES] = WidgetState.encodeFavorites(updated)
                    }
                }
                TvWidget().updateAll(this@MainActivity)
            }
            runOnUiThread(onDone)
        }.start()
    }

    /** Populates the shared results list with today's trending shows, tracked ones pinned first. */
    private fun loadTrackedAsync(status: TextView) {
        if (!sessionTrackedShowsSeeded) {
            sessionTrackedShows += TrackedShowsRepository.list(this).map { show ->
                CatalogueShow(show.tvMazeId, show.title, show.network, "TRACKING", show.posterUrl, tracked = true)
            }
            sessionTrackedShowsSeeded = true
        }
        resultsAdapter.submit(sessionTrackedShows.toList())
        status.text = "Loading trending shows…"
        Thread {
            val browsed = runCatching { runBlocking { TvMazeApi.browse() } }.getOrDefault(emptyList())
            val trackedIds = sessionTrackedShows.map { it.tvMazeId }.toSet()
            val merged = sessionTrackedShows + browsed
                .filterNot { it.tvMazeId in trackedIds }
                .map { it.copy(tracked = TrackedShowsRepository.isTracked(this@MainActivity, it.tvMazeId)) }
            runOnUiThread {
                resultsAdapter.submit(merged)
                status.text = "${merged.size} shown."
            }
        }.start()
    }

    // -- CATALOGUE search/recommended results ---------------------------------------------------

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

            // Untracking never removes the row from view here — it flips back to "+ TRACK" and
            // stays, so the session's tracked-list snapshot only ever grows or has a flag flipped,
            // never shrinks. Closing this window (a fresh MainActivity re-seeding from
            // TrackedShowsRepository) is the only thing that actually drops it from the list.
            val sessionIndex = sessionTrackedShows.indexOfFirst { it.tvMazeId == show.tvMazeId }
            if (sessionIndex >= 0) {
                sessionTrackedShows[sessionIndex] = sessionTrackedShows[sessionIndex].copy(tracked = nowTracked)
            } else if (nowTracked) {
                sessionTrackedShows += show.copy(tracked = true)
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
        const val EXTRA_OPEN_CATALOGUE = "open_catalogue"
        private const val SEARCH_DEBOUNCE_MS = 300L
        private val ACCENT = Color.parseColor("#F2C81E")
    }
}
