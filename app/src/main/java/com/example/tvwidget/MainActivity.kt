package com.example.tvwidget

import android.app.Activity
import android.content.Intent
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
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.data.TrackedShow
import com.example.tvwidget.data.TrackedShowsRepository
import com.example.tvwidget.data.TvMazeApi
import com.example.tvwidget.data.WidgetState
import com.example.tvwidget.widget.TvWidget
import com.example.tvwidget.work.AnticipatedSyncWorker
import kotlinx.coroutines.runBlocking

/**
 * Host activity. The product is the home-screen widget; this screen exists so the app is
 * launchable, so a whole-row tap in the TODAY feed has somewhere to land, and so CATALOGUE — search,
 * Tracked, Trending, and Favorites — has somewhere to run at all. None of that can live in the
 * widget: a `RemoteViews` has no `EditText` for real search, and rendering Favorites' full detail
 * plus a browseable Trending list there was more chrome than a home-screen widget can reasonably
 * hold.
 */
class MainActivity : Activity() {

    private lateinit var resultsAdapter: ResultsAdapter
    private var selectedCatalogueTab = CatalogueTab.TRACKED

    private enum class CatalogueTab { TRACKED, TRENDING, FAVORITES }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val show = intent?.getStringExtra(EXTRA_SHOW_TITLE)
        val episode = intent?.getStringExtra(EXTRA_EPISODE_CODE)
        val imdbId = intent?.getStringExtra(EXTRA_IMDB_ID)?.ifEmpty { null }
        val openCatalogue = intent?.getBooleanExtra(EXTRA_OPEN_CATALOGUE, false) ?: false

        when {
            openCatalogue -> setContentView(buildCatalogueScreen())
            // Tapping a TODAY row's title is meant to open that show's IMDb page — Glance's
            // actionStartActivity only launches a typed Activity, not an arbitrary Intent, so the
            // widget starts this Activity with the show/episode extras and this is the pass-through
            // that actually fires the ACTION_VIEW intent.
            show != null -> resolveImdbIdThenOpen(show, episode, imdbId)
            else -> setContentView(buildHomeScreen())
        }
    }

    /**
     * If the widget didn't already know an imdbId (a show tracked before that field existed, whose
     * TRACKED_RELEASES hasn't been rewritten by a sync since — that sync backfill is a background
     * convenience, not something a tap should have to wait on), this looks it up live instead of
     * falling straight to a text search. A brief blank window while that one request completes is a
     * better trade than "this always goes to a search" — the whole point of an id lookup is to avoid
     * search whenever there's any reasonable way to.
     */
    private fun resolveImdbIdThenOpen(show: String, episode: String?, knownImdbId: String?) {
        if (!knownImdbId.isNullOrBlank()) {
            if (!openImdb(show, episode, knownImdbId)) setContentView(buildDeepLinkScreen(show, episode))
            return
        }
        Thread {
            val resolved = runCatching { runBlocking { TvMazeApi.imdbIdFor(show) } }.getOrNull()
            runOnUiThread {
                if (!openImdb(show, episode, resolved)) setContentView(buildDeepLinkScreen(show, episode))
            }
        }.start()
    }

    /**
     * Opens IMDb directly at the show's own page when [imdbId] is known (TVMaze doesn't expose a
     * per-episode IMDb crosswalk, so this is the closest a title tap gets to "that exact episode" —
     * the show's main page, not a text search). Falls back to a text search only when no id could be
     * found at all. Returns false if nothing on the device could handle the resulting intent.
     */
    private fun openImdb(show: String, episode: String?, imdbId: String?): Boolean {
        val url = if (!imdbId.isNullOrBlank()) {
            "https://www.imdb.com/title/$imdbId/"
        } else {
            "https://www.imdb.com/find/?q=" + Uri.encode(listOfNotNull(show, episode).joinToString(" "))
        }
        val success = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
        if (success) finish()
        return success
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
     * CATALOGUE: a search field, and three tabs underneath it.
     *  - TRACKED: only the shows currently being tracked — nothing else. Untracking here removes
     *    the row immediately; a tab whose whole point is "what I'm tracking" shouldn't keep showing
     *    something that no longer is.
     *  - TRENDING: today's popular currently-running shows via [TvMazeApi.browse] (TVMaze's `weight`
     *    field ranking a "what's actually airing right now" pool — see that function's doc for why).
     *    Tracking/untracking here just flips a row's button in place; the list is "what's trending"
     *    regardless of what you've tracked, so a row never appears or disappears because of it.
     *  - FAVORITES: favorited episodes, read from and written back to the widget's own Glance state.
     *
     * Typing 2+ characters into the search field covers whichever tab is showing with search
     * results; clearing it back out restores that tab.
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
        val trendingTabButton = Button(this).apply {
            text = "TRENDING"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val favoritesTabButton = Button(this).apply {
            text = "FAVORITES"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tabRow.addView(trackedTabButton)
        tabRow.addView(trendingTabButton)
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
            val selectedColor = Color.parseColor("#D4AF37")
            val unselectedColor = Color.parseColor("#444444")
            trackedTabButton.setBackgroundColor(
                if (selectedCatalogueTab == CatalogueTab.TRACKED) selectedColor else unselectedColor
            )
            trendingTabButton.setBackgroundColor(
                if (selectedCatalogueTab == CatalogueTab.TRENDING) selectedColor else unselectedColor
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
                    resultsAdapter.removeOnUntrack = true
                    loadTrackedAsync(status)
                }
                CatalogueTab.TRENDING -> {
                    favoritesScroll.visibility = View.GONE
                    catalogueList.visibility = View.VISIBLE
                    resultsAdapter.removeOnUntrack = false
                    loadTrendingAsync(status)
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
        trendingTabButton.setOnClickListener {
            selectedCatalogueTab = CatalogueTab.TRENDING
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
                resultsAdapter.removeOnUntrack = false // a search hit shouldn't vanish on untrack either
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

    /** TRACKED: only what's currently tracked — nothing to discover here, just manage it. */
    private fun loadTrackedAsync(status: TextView) {
        val tracked = TrackedShowsRepository.list(this).map { show ->
            CatalogueShow(show.tvMazeId, show.title, show.network, "TRACKING", show.posterUrl, tracked = true, show.imdbId)
        }
        resultsAdapter.submit(tracked)
        status.text = if (tracked.isEmpty()) "No shows tracked yet." else "${tracked.size} tracked."
    }

    /** TRENDING: today's popular currently-running shows, independent of what's tracked. */
    private fun loadTrendingAsync(status: TextView) {
        status.text = "Loading trending shows…"
        Thread {
            val trending = runCatching { runBlocking { TvMazeApi.browse() } }.getOrDefault(emptyList())
                .map { it.copy(tracked = TrackedShowsRepository.isTracked(this@MainActivity, it.tvMazeId)) }
            runOnUiThread {
                resultsAdapter.submit(trending)
                status.text = if (trending.isEmpty()) "Couldn't load trending shows." else "${trending.size} trending."
            }
        }.start()
    }

    // -- CATALOGUE search/tracked/trending results -----------------------------------------------

    private inner class ResultsAdapter : BaseAdapter() {
        private var items: List<CatalogueShow> = emptyList()

        /**
         * True only for TRACKED's own listing: untracking there removes the row immediately, since
         * the tab's entire point is "what I'm tracking". Everywhere else (TRENDING, search results)
         * untracking just flips the row's button — the show still belongs in that list regardless of
         * whether it's tracked.
         */
        var removeOnUntrack = false

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

        /**
         * Loads through [PosterStore] — the same on-disk cache the widget uses — instead of a plain
         * in-memory map scoped to this Activity instance. The in-memory map used to mean every poster
         * reloaded from the network (and sat blank in the meantime) each time this screen was opened;
         * a disk cache means a show whose poster was already fetched (by this screen or by a widget
         * sync) never shows an empty poster again.
         */
        private fun loadPosterAsync(show: CatalogueShow, target: ImageView) {
            target.setImageDrawable(null)
            val key = PosterStore.keyFor(show.title)
            Thread {
                val bitmap = runBlocking {
                    if (!PosterStore.has(this@MainActivity, key)) {
                        PosterStore.ensureCached(this@MainActivity, key, show.posterUrl)
                    }
                    PosterStore.loadBitmaps(this@MainActivity, listOf(key))[key]
                }
                if (bitmap != null) runOnUiThread { target.setImageBitmap(bitmap) }
            }.start()
        }

        private fun toggleTrack(show: CatalogueShow, button: Button) {
            val nowTracked = !show.tracked
            if (nowTracked) {
                TrackedShowsRepository.add(
                    this@MainActivity,
                    TrackedShow(show.tvMazeId, show.title, show.network, show.posterUrl, show.imdbId),
                )
            } else {
                TrackedShowsRepository.remove(this@MainActivity, show.tvMazeId)
            }

            items = if (removeOnUntrack && !nowTracked) {
                items.filterNot { it.tvMazeId == show.tvMazeId }
            } else {
                items.map { if (it.tvMazeId == show.tvMazeId) it.copy(tracked = nowTracked) else it }
            }
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
        const val EXTRA_IMDB_ID = "imdb_id"
        const val EXTRA_OPEN_CATALOGUE = "open_catalogue"
        private const val SEARCH_DEBOUNCE_MS = 300L
        private val ACCENT = Color.parseColor("#D4AF37")
    }
}
