package com.example.tvwidget

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
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
import com.example.tvwidget.data.CatalogShow
import com.example.tvwidget.data.FavoriteShow
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.data.TrackedShow
import com.example.tvwidget.data.TrackedShowsRepository
import com.example.tvwidget.data.TvMazeApi
import com.example.tvwidget.data.WidgetState
import com.example.tvwidget.ui.AppTheme
import com.example.tvwidget.ui.AppTheme.clipToRoundedRect
import com.example.tvwidget.ui.AppTheme.display
import com.example.tvwidget.ui.AppTheme.dp
import com.example.tvwidget.ui.AppTheme.enterSoftly
import com.example.tvwidget.ui.AppTheme.goldLeaf
import com.example.tvwidget.ui.AppTheme.label
import com.example.tvwidget.widget.TvWidget
import com.example.tvwidget.work.AnticipatedSyncWorker
import kotlinx.coroutines.runBlocking

/**
 * Host activity. The product is the home-screen widget; this screen exists so the app is
 * launchable, so a title tap has somewhere to land, and so CATALOG — search, Tracked, Trending
 * and Favorites — has somewhere to run at all. None of that fits in a `RemoteViews`: no text field
 * for search, no room for Favorites' full detail, no motion.
 *
 * The whole screen is built in code rather than XML to stay consistent with how the rest of this
 * app is written, and styled through [AppTheme] so the app and the widget read as one product.
 */
class MainActivity : Activity() {

    private lateinit var resultsAdapter: ResultsAdapter
    private var selectedCatalogTab = CatalogTab.TRACKED

    /**
     * Bumped every time a tab load starts. A tab's fetch can outlive the tab being on screen —
     * TRENDING pools several days of schedule, so switching away mid-fetch and having the reply
     * land afterwards would drop trending rows into whatever tab you're now looking at.
     */
    private var tabToken = 0

    /** The Catalog result list, so async loaders can animate it in once data actually arrives. */
    private var catalogListRef: View? = null

    /** Sets the copy shown when the current list has nothing in it. */
    private var emptyMessage: (String, String) -> Unit = { _, _ -> }

    private enum class CatalogTab(val label: String) {
        TRACKED("Tracked"),
        TRENDING("Trending"),
        FAVORITES("Favorites"),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val show = intent?.getStringExtra(EXTRA_SHOW_TITLE)
        val episode = intent?.getStringExtra(EXTRA_EPISODE_CODE)
        val imdbId = intent?.getStringExtra(EXTRA_IMDB_ID)?.ifEmpty { null }
        val openCatalog = intent?.getBooleanExtra(EXTRA_OPEN_CATALOG, false) ?: false

        when {
            openCatalog -> setContentView(buildCatalogScreen())
            // Tapping a title in the widget opens that show's IMDb page — Glance's
            // actionStartActivity only launches a typed Activity, not an arbitrary Intent, so the
            // widget starts this Activity with the show extras and this is the pass-through that
            // actually fires the ACTION_VIEW intent.
            show != null -> resolveImdbIdThenOpen(show, episode, imdbId)
            else -> setContentView(buildHomeScreen())
        }
    }

    // -- IMDb ------------------------------------------------------------------------------------

    /**
     * If the id isn't already known (a show tracked before imdbId existed, or any Catalog row,
     * which doesn't carry one), this looks it up live rather than falling straight to a text search.
     * A brief delay for one request is a better trade than "this always goes to a search".
     *
     * [onUnresolved] defaults to the widget pass-through's placeholder screen (there's nothing else
     * on screen there); Catalog's callers pass a toast instead, since replacing the whole screen
     * over one failed lookup would blow away the tab the user was looking at.
     */
    private fun resolveImdbIdThenOpen(
        show: String,
        episode: String?,
        knownImdbId: String?,
        onUnresolved: () -> Unit = { setContentView(buildDeepLinkScreen(show, episode)) },
    ) {
        if (!knownImdbId.isNullOrBlank()) {
            if (!openImdb(show, episode, knownImdbId)) onUnresolved()
            return
        }
        Thread {
            val resolved = runCatching { runBlocking { TvMazeApi.imdbIdFor(show) } }.getOrNull()
            runOnUiThread {
                if (!openImdb(show, episode, resolved)) onUnresolved()
            }
        }.start()
    }

    /**
     * Opens IMDb at the show's own page when [imdbId] is known (TVMaze exposes no per-episode IMDb
     * crosswalk, so the show page is as precise as this gets). Falls back to a text search only when
     * no id could be found at all.
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

    private fun showImdbUnavailable() {
        Toast.makeText(this, "Couldn't open IMDb for that show", Toast.LENGTH_SHORT).show()
    }

    /**
     * Washes a card's leading edge with a colour taken from its own poster.
     *
     * A `LayerDrawable` rather than a second view: an overlay stacked on the row would be the child
     * that receives the touch, and the card is tappable. The wash is kept low and gone by a third of
     * the way across — past that it stops reading as light on a surface and starts reading as a
     * coloured panel, which looks like a mistake rather than a decision.
     */
    private fun tintRow(row: View, accent: Int, tracked: Boolean) {
        val radius = 20.dp.toFloat()
        val base = if (tracked) {
            AppTheme.liftedSurface(0xFF272017.toInt(), 0xFF1B1713.toInt(), radius)
        } else {
            AppTheme.liftedSurface(AppTheme.SurfaceRaised, AppTheme.Surface, radius)
        }
        val wash = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.argb(58, Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.TRANSPARENT,
            ),
        ).apply { cornerRadius = radius }
        val layered = android.graphics.drawable.LayerDrawable(arrayOf(base, wash))
        row.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(AppTheme.accent(0.16f)),
            layered,
            null,
        )
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    /**
     * A short confirmation tick. Tracking a show is a state change with a very small visual
     * footprint — one pill flips — and a tap that changes something should be felt as well as seen.
     */
    private fun confirmHaptic(view: View) {
        view.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.view.HapticFeedbackConstants.CONFIRM
            } else {
                android.view.HapticFeedbackConstants.VIRTUAL_KEY
            }
        )
    }

    // -- Screens ---------------------------------------------------------------------------------

    private fun buildDeepLinkScreen(show: String, episode: String?): View {
        val root = screenRoot(centered = true)
        root.addView(TextView(this).apply {
            text = show
            display(26f)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = episode.orEmpty()
            label(12f, AppTheme.TextSecondary, tracking = 0.18f)
            gravity = Gravity.CENTER
            setPadding(0, 8.dp, 0, 0)
        })
        return root
    }

    private fun buildHomeScreen(): View {
        val root = screenRoot(centered = true)
        root.addView(TextView(this).apply {
            text = "TV RELEASES"
            label(11f, AppTheme.Accent, tracking = 0.34f, bold = true)
            gravity = Gravity.CENTER
            goldLeaf()
        })
        root.addView(TextView(this).apply {
            text = "Everything you follow,\non your home screen."
            display(28f)
            gravity = Gravity.CENTER
            setLineSpacing(6.dp.toFloat(), 1f)
            setPadding(0, 14.dp, 0, 0)
        })
        root.addView(primaryButton("Open catalog") {
            setContentView(buildCatalogScreen())
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 32.dp
        })
        return root
    }

    /**
     * CATALOG. A search field over a segmented control over content:
     *  - Tracked: only what's currently tracked. Untracking removes the row immediately — a tab whose
     *    point is "what I'm tracking" shouldn't keep showing something that isn't.
     *  - Trending: today's popular currently-running shows. Track/untrack flips the button in place;
     *    a show's trending status doesn't depend on whether you follow it.
     *  - Favorites: favorited episodes, read from (and written back to) the widget's Glance state.
     *
     * Typing 2+ characters covers whichever tab is showing with search results; clearing restores it.
     */
    private fun buildCatalogScreen(): View {
        val root = screenRoot()
        // Animates the masthead's collapse and return, and the status line's changes, without any
        // of it being animated by hand.
        root.layoutTransition = android.animation.LayoutTransition().apply {
            setDuration(180L)
            enableTransitionType(android.animation.LayoutTransition.CHANGING)
        }

        // -- Masthead ------------------------------------------------------------------------
        //
        // Grouped so it can leave as one thing. On a phone with the keyboard up, this screen's fixed
        // chrome — wordmark, title, field, segmented control, status — used to eat roughly 250dp and
        // leave room for one and a half results. The masthead is the half of that which is pure
        // identity: worth having when you arrive, worth nothing at all while you are typing.
        val masthead = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "TV RELEASES"
                label(10f, AppTheme.Accent, tracking = 0.36f, bold = true)
                goldLeaf()
            })
            addView(TextView(this@MainActivity).apply {
                text = "Catalog"
                display(32f)
                setPadding(0, 6.dp, 0, 0)
            })
        }
        root.addView(masthead)

        // -- Search ---------------------------------------------------------------------------
        val input = EditText(this).apply {
            hint = "Search every show"
            setHintTextColor(AppTheme.TextMuted)
            setTextColor(AppTheme.TextPrimary)
            typeface = Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            // Enter closes the keyboard rather than doing nothing. The results are already live by
            // the time it is pressed; what the user wants back at that point is the screen.
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        }

        // The masthead follows the keyboard, not the text: it leaves the moment the field is
        // focused, so the results area is already at full height by the time the first character
        // lands, and comes back when focus goes away and the query is empty.
        fun setMastheadCollapsed(collapsed: Boolean) {
            val target = if (collapsed) View.GONE else View.VISIBLE
            if (masthead.visibility != target) masthead.visibility = target
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            setMastheadCollapsed(hasFocus || input.text.isNotEmpty())
        }

        root.addView(
            searchField(input) {
                input.clearFocus()
                hideKeyboard()
                setMastheadCollapsed(false)
            }
        )

        // -- Segmented control -----------------------------------------------------------------
        val status = TextView(this).apply {
            label(10.5f, AppTheme.TextMuted, tracking = 0.2f)
            setPadding(2.dp, 14.dp, 0, 10.dp)
        }
        lateinit var showCurrentTab: () -> Unit
        val segmented = segmentedControl { tab ->
            selectedCatalogTab = tab
            if (input.text.length < 2) showCurrentTab()
        }
        root.addView(segmented.view)
        root.addView(status)

        // -- Content ---------------------------------------------------------------------------
        val favoritesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = android.animation.LayoutTransition()
        }
        val favoritesScroll = ScrollView(this).apply {
            addView(favoritesContainer)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, 24.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(favoritesScroll)

        resultsAdapter = ResultsAdapter()
        val catalogList = ListView(this).apply {
            adapter = resultsAdapter
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, 24.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // Scrolling is a clear signal that the user is done typing and wants to read. Holding the
        // keyboard open past that point costs half the screen for nothing.
        catalogList.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) {
                if (state == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                    hideKeyboard()
                    input.clearFocus()
                }
            }
            override fun onScroll(v: android.widget.AbsListView?, f: Int, vc: Int, t: Int) = Unit
        })
        root.addView(catalogList)
        catalogListRef = catalogList

        // A ListView with nothing in it draws nothing, which is how TRACKED ended up as a status
        // line floating over a screen of black. ListView.setEmptyView swaps the two automatically,
        // so every tab gets a real answer instead of a void.
        val emptyTitle = TextView(this).apply {
            display(20f, AppTheme.TextSecondary)
            gravity = Gravity.CENTER
        }
        val emptyDetail = TextView(this).apply {
            label(11.5f, AppTheme.TextMuted, tracking = 0.08f)
            gravity = Gravity.CENTER
            setPadding(0, 8.dp, 0, 0)
        }
        val emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp, 40.dp, 24.dp, 24.dp)
            visibility = View.GONE
            addView(emptyTitle)
            addView(emptyDetail)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(emptyView)
        catalogList.emptyView = emptyView
        emptyMessage = { title, detail ->
            emptyTitle.text = title
            emptyDetail.text = detail
        }

        showCurrentTab = {
            // A new tab starts at the top. Landing halfway down a different list because the last
            // one was scrolled there is disorienting in a way nobody ever asks for.
            catalogList.setSelection(0)
            when (selectedCatalogTab) {
                CatalogTab.TRACKED -> {
                    favoritesScroll.visibility = View.GONE
                    catalogList.visibility = View.VISIBLE
                    catalogList.enterSoftly()
                    resultsAdapter.removeOnUntrack = true
                    loadTrackedAsync(status)
                }
                CatalogTab.TRENDING -> {
                    favoritesScroll.visibility = View.GONE
                    catalogList.visibility = View.VISIBLE
                    // No enterSoftly here: this tab fetches, so the list is empty at this point and
                    // the animation would play on nothing. It runs when the rows land instead.
                    resultsAdapter.removeOnUntrack = false
                    loadTrendingAsync(status)
                }
                CatalogTab.FAVORITES -> {
                    favoritesScroll.visibility = View.VISIBLE
                    catalogList.visibility = View.GONE
                    favoritesScroll.enterSoftly()
                    loadFavoritesAsync(status, favoritesContainer)
                }
            }
        }
        segmented.select(selectedCatalogTab, animate = false)
        showCurrentTab()

        // -- Search behaviour --------------------------------------------------------------------
        val debounceHandler = android.os.Handler(mainLooper)
        var pendingSearch: Runnable? = null
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                val query = editable?.toString().orEmpty().trim()
                // Search shares [tabToken] with the tab loaders rather than keeping its own: they all
                // write the same list, so "who owns the list right now" has to be one answer. A
                // keystroke invalidates a pending TRENDING fetch and vice versa.
                val token = ++tabToken

                // Debounced: a fresh keystroke cancels whatever run was still waiting, so a fast
                // typist never triggers one network request per character.
                pendingSearch?.let(debounceHandler::removeCallbacks)
                if (query.length < 2) {
                    showCurrentTab()
                    return
                }
                favoritesScroll.visibility = View.GONE
                catalogList.visibility = View.VISIBLE
                resultsAdapter.removeOnUntrack = false // a search hit shouldn't vanish on untrack

                // Answer from what is already on the device, immediately, before any request is
                // even scheduled. The debounce plus a round trip means roughly half a second where
                // the screen otherwise says SEARCHING and shows nothing; matching the tracked list
                // locally fills that with real, relevant rows on the very first keystroke. Remote
                // results replace these when they arrive.
                val local = TrackedShowsRepository.list(this@MainActivity)
                    .filter { it.title.contains(query, ignoreCase = true) }
                    .map {
                        CatalogShow(it.tvMazeId, it.title, it.network, "TRACKING", it.posterUrl, true, it.imdbId)
                    }
                if (local.isNotEmpty()) {
                    resultsAdapter.submit(local)
                    status.text = "${local.size} TRACKED · SEARCHING"
                } else {
                    resultsAdapter.submitLoading()
                    status.text = "SEARCHING"
                }

                val runnable = Runnable {
                    Thread {
                        val results = runCatching { runBlocking { TvMazeApi.search(query) } }
                            .getOrDefault(emptyList())
                            .map { it.copy(tracked = TrackedShowsRepository.isTracked(this@MainActivity, it.tvMazeId)) }
                        runOnUiThread {
                            if (token != tabToken) return@runOnUiThread // superseded by a keystroke or tab switch
                            if (results.isEmpty() && local.isEmpty()) {
                                emptyMessage(
                                    "No show called \"$query\"",
                                    "Try fewer words, or the original title if the show was renamed locally.",
                                )
                                resultsAdapter.submit(emptyList())
                                status.text = "NOTHING MATCHES \"${query.uppercase()}\""
                            } else {
                                resultsAdapter.submit(results.ifEmpty { local })
                                catalogList.enterSoftly()
                                status.text = "${results.ifEmpty { local }.size} RESULTS"
                            }
                        }
                    }.start()
                }
                pendingSearch = runnable
                debounceHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
            }
        })

        return root
    }

    // -- Content loading -------------------------------------------------------------------------

    /** TRACKED: only what's currently tracked — nothing to discover here, just manage it. */
    private fun loadTrackedAsync(status: TextView) {
        tabToken++ // reads from disk, so it lands immediately — but still cancels any pending fetch
        val tracked = TrackedShowsRepository.list(this).map { show ->
            CatalogShow(show.tvMazeId, show.title, show.network, "TRACKING", show.posterUrl, tracked = true, show.imdbId)
        }
        emptyMessage(
            "Nothing tracked yet",
            "Search above, or open Trending, and tap TRACK on anything you want the widget to follow.",
        )
        resultsAdapter.submit(tracked)
        status.text = if (tracked.isEmpty()) "NOTHING TRACKED YET" else "${tracked.size} TRACKED"
    }

    /**
     * TRENDING: popular currently-running shows you *aren't* already tracking.
     *
     * Tracked shows are filtered out rather than shown with a flipped button: this tab is for
     * discovery, and TRACKED next door already lists everything you follow, so leaving them in just
     * spends the top of the list — the most popular slots — re-showing things you've already found.
     *
     * The filter runs at load time only. Tracking something from here leaves it in place with its
     * button flipped, so the row doesn't vanish out from under the finger that just tapped it; it's
     * gone the next time the tab is opened.
     */
    private fun loadTrendingAsync(status: TextView) {
        val token = ++tabToken
        // Clear first. Leaving the previous tab's rows up during the fetch means TRACKED's rows sit
        // under a "LOADING" label on the TRENDING tab — which reads as trending showing you things
        // you already follow, the exact opposite of what this tab is for.
        resultsAdapter.submit(emptyList())
        status.text = "LOADING"
        Thread {
            val trending = runCatching { runBlocking { TvMazeApi.browse() } }.getOrDefault(emptyList())
                .filterNot { TrackedShowsRepository.isTracked(this@MainActivity, it.tvMazeId) }
            runOnUiThread {
                if (token != tabToken) return@runOnUiThread // user moved on while this was in flight
                resultsAdapter.submit(trending)
                catalogListRef?.enterSoftly()
                status.text = when {
                    trending.isEmpty() -> "COULDN'T LOAD TRENDING"
                    else -> "${trending.size} TRENDING"
                }
            }
        }.start()
    }

    /** Populates [container] with favorited episodes grouped by show, from the widget's Glance state. */
    private fun loadFavoritesAsync(status: TextView, container: LinearLayout) {
        val token = ++tabToken
        status.text = "LOADING"
        Thread {
            val shows = runCatching { runBlocking { readWidgetFavoriteShows() } }.getOrDefault(emptyList())
            runOnUiThread {
                if (token != tabToken) return@runOnUiThread // user moved on while this was in flight
                renderFavorites(container, shows)
                val episodes = shows.sumOf { it.episodes.size }
                status.text = if (shows.isEmpty()) "NOTHING SAVED YET" else "$episodes SAVED"
            }
        }.start()
    }

    private suspend fun readWidgetFavoriteShows(): List<FavoriteShow> {
        val glanceId = GlanceAppWidgetManager(this).getGlanceIds(TvWidget::class.java).firstOrNull()
            ?: return emptyList()
        val prefs = getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)
        return WidgetState.favoriteShows(WidgetState.favorites(prefs), WidgetState.rewatchLog(prefs))
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

    // -- Favorites rendering ---------------------------------------------------------------------

    private fun renderFavorites(container: LinearLayout, shows: List<FavoriteShow>) {
        container.removeAllViews()
        if (shows.isEmpty()) {
            container.addView(emptyState("Nothing saved yet", "Star an episode in the widget to keep it here."))
            return
        }
        shows.forEach { show ->
            container.addView(favoriteCard(container, shows, show))
        }
    }

    private fun favoriteCard(
        container: LinearLayout,
        allShows: List<FavoriteShow>,
        show: FavoriteShow,
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AppTheme.liftedSurface(AppTheme.SurfaceRaised, AppTheme.Surface, 20.dp.toFloat())
            setPadding(18.dp, 16.dp, 18.dp, 8.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp }
        }

        card.addView(TextView(this).apply {
            text = show.title
            display(19f)
            maxLines = 1
            setOnClickListener {
                resolveImdbIdThenOpen(show.title, null, null, onUnresolved = ::showImdbUnavailable)
            }
        })
        card.addView(TextView(this).apply {
            text = "WATCHED ×${show.rewatchCount}  ·  ${show.episodes.size} SAVED"
            label(10f, AppTheme.Accent, tracking = 0.22f)
            setPadding(0, 5.dp, 0, 0)
        })

        show.episodes.forEach { episode ->
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12.dp, 0, 4.dp)
                addView(TextView(this@MainActivity).apply {
                    text = episode.episodeCode
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(AppTheme.TextPrimary)
                })
                addView(TextView(this@MainActivity).apply {
                    text = episode.label
                    label(10.5f, AppTheme.TextMuted, tracking = 0.14f)
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = 12.dp }
                })
                addView(pillButton(text = "REMOVE", active = false) {
                    unfavoriteFromApp(show.title, episode.episodeCode) {
                        val refreshed = allShows.mapNotNull { s ->
                            if (s.title != show.title) s
                            else s.copy(episodes = s.episodes.filterNot { it.episodeCode == episode.episodeCode })
                                .takeIf { it.episodes.isNotEmpty() }
                        }
                        renderFavorites(container, refreshed)
                    }
                })
            })
        }
        return card
    }

    // -- Catalog rows --------------------------------------------------------------------------

    private inner class ResultsAdapter : BaseAdapter() {
        private var items: List<CatalogShow> = emptyList()

        /**
         * When true the list draws placeholder cards instead of rows.
         *
         * A spinner or the word LOADING tells you to wait; skeleton rows tell you what you are
         * waiting *for*, and the list does not jump when the real content replaces them because the
         * shape is already correct. They pulse rather than shimmer — a gradient sweeping across a
         * dark surface reads as a glare artifact, where a slow breath reads as pending.
         */
        private var loading = false

        /**
         * True only for TRACKED's own listing: untracking there removes the row immediately, since
         * the tab's entire point is "what I'm tracking". Everywhere else (TRENDING, search) it just
         * flips the row's button — the show still belongs in that list either way.
         */
        var removeOnUntrack = false

        fun submit(newItems: List<CatalogShow>) {
            items = newItems
            loading = false
            notifyDataSetChanged()
        }

        /** Shows placeholder cards until real rows arrive. */
        fun submitLoading(count: Int = 4) {
            items = emptyList()
            loading = true
            skeletonCount = count
            notifyDataSetChanged()
        }

        private var skeletonCount = 4

        override fun getCount() = if (loading) skeletonCount else items.size
        override fun getItem(position: Int) = if (loading) Unit else items[position]
        override fun getItemId(position: Int) =
            if (loading) position.toLong() else items[position].tvMazeId.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            if (loading) return skeletonRow()
            val show = items[position]

            // A ListView's children are laid out with AbsListView.LayoutParams, which has no
            // margins — so the air between cards comes from a padded wrapper around each card
            // rather than a margin on it.
            val wrapper = FrameLayout(this@MainActivity).apply { setPadding(0, 0, 0, 10.dp) }

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // A tracked show's card sits a touch warmer than the rest. State shouldn't rest
                // entirely on one small pill at the far edge of the row — carrying it in the surface
                // means you can see what you follow while scanning, without reading a single word.
                background = AppTheme.tappable(
                    if (show.tracked) {
                        AppTheme.liftedSurface(0xFF272017.toInt(), 0xFF1B1713.toInt(), 20.dp.toFloat())
                    } else {
                        AppTheme.liftedSurface(AppTheme.SurfaceRaised, AppTheme.Surface, 20.dp.toFloat())
                    }
                )
                setPadding(12.dp, 12.dp, 14.dp, 12.dp)
                // The whole card opens IMDb; the track button below consumes its own taps.
                setOnClickListener {
                    resolveImdbIdThenOpen(show.title, null, show.imdbId, onUnresolved = ::showImdbUnavailable)
                }
            }

            val poster = ImageView(this@MainActivity).apply {
                // 2:3, the standard poster ratio — key art is the most valuable thing in the row,
                // so it gets real presence rather than a thumbnail's worth.
                layoutParams = LinearLayout.LayoutParams(60.dp, 90.dp)
                background = AppTheme.liftedSurface(0xFF272220.toInt(), 0xFF171412.toInt(), 10.dp.toFloat())
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToRoundedRect(10.dp.toFloat())
            }
            row.addView(poster)
            loadPosterAsync(show, poster)

            val textColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 14.dp }
            }
            textColumn.addView(TextView(this@MainActivity).apply {
                // Joined rather than concatenated: TVMaze leaves the network blank often enough that
                // a hardcoded separator left rows reading "TO BE DETERMINED ·" with a dangling dot.
                text = listOf(show.status, show.network)
                    .filter { it.isNotBlank() && it != "UNKNOWN" }
                    .joinToString(" · ")
                label(9.5f, AppTheme.TextMuted, tracking = 0.2f)
                maxLines = 1
            })
            textColumn.addView(TextView(this@MainActivity).apply {
                text = show.title
                display(17f)
                maxLines = 2
                setPadding(0, 4.dp, 0, 0)
            })
            row.addView(textColumn)

            row.addView(pillButton(
                text = if (show.tracked) "TRACKING" else "TRACK",
                active = show.tracked,
            ) { button -> toggleTrack(show, button) }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = 10.dp
            })

            wrapper.addView(row)
            return wrapper
        }

        /**
         * One placeholder card, breathing gently. Same geometry as a real row, so nothing shifts
         * when the content arrives.
         */
        private fun skeletonRow(): View {
            val wrapper = FrameLayout(this@MainActivity).apply { setPadding(0, 0, 0, 10.dp) }
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = AppTheme.liftedSurface(
                    AppTheme.SurfaceRaised, AppTheme.Surface, 20.dp.toFloat(),
                )
                setPadding(12.dp, 12.dp, 14.dp, 12.dp)
            }
            fun block(w: Int, h: Int, radius: Float) = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(w, h)
                background = AppTheme.surface(AppTheme.neutral(0.07f), radius)
            }
            row.addView(block(60.dp, 90.dp, 10.dp.toFloat()))
            row.addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 14.dp }
                addView(block(90.dp, 8.dp, 4f))
                addView(block(170.dp, 15.dp, 5f).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = 9.dp
                })
            })
            android.animation.ObjectAnimator.ofFloat(row, "alpha", 0.45f, 0.9f).apply {
                duration = 780L
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
            wrapper.addView(row)
            return wrapper
        }

        /**
         * Loads through [PosterStore] — the same on-disk cache the widget uses — so a poster fetched
         * once never blanks out again on a later visit. An in-memory map scoped to this Activity
         * (what this used to be) was thrown away every time the screen closed.
         */
        private fun loadPosterAsync(show: CatalogShow, target: ImageView) {
            target.setImageDrawable(null)
            val key = PosterStore.keyFor(show.title)
            Thread {
                val bitmap = runBlocking {
                    if (!PosterStore.has(this@MainActivity, key)) {
                        PosterStore.ensureCached(this@MainActivity, key, show.posterUrl)
                    }
                    PosterStore.loadBitmapsBlocking(this@MainActivity, listOf(key))[key]
                }
                val accent = PosterStore.loadAccentsBlocking(this@MainActivity, listOf(key))[key]
                if (bitmap != null) runOnUiThread {
                    target.setImageBitmap(bitmap)
                    target.enterSoftly()
                    // The same trick the widget's rows use, carried into the app so the two read as
                    // one product: each card is washed from its leading edge with the dominant
                    // colour of its own artwork, so a list of shows looks like a list of different
                    // shows rather than repeated furniture.
                    if (accent != null) (target.parent as? View)?.let { tintRow(it, accent, show.tracked) }
                }
            }.start()
        }

        private fun toggleTrack(show: CatalogShow, button: TextView) {
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
            stylePill(button, text = if (nowTracked) "TRACKING" else "TRACK", active = nowTracked)
            confirmHaptic(button)
            AnticipatedSyncWorker.runOnce(this@MainActivity)
        }
    }

    // -- Components ------------------------------------------------------------------------------

    private fun screenRoot(centered: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (centered) gravity = Gravity.CENTER
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(AppTheme.BackgroundLift, AppTheme.Background, AppTheme.Background),
        )
        setPadding(24.dp, 30.dp, 24.dp, 0)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    /**
     * The search field: a soft capsule with a leading glyph, a clear button, and no underline.
     *
     * Deliberately large. The previous version set 15sp text inside a shallow capsule, which made
     * the one control on this screen you actually type into the smallest thing on it — and it read
     * as a caption rather than an input. Type at 18sp in a 56dp-tall capsule is a search field; the
     * old one was a label that happened to accept text.
     *
     * The clear button matters more than it looks: without it, correcting a search on a phone means
     * holding backspace, and the only other way back to the tab you were browsing is deleting every
     * character one at a time.
     */
    private fun searchField(input: EditText, onClear: () -> Unit): View {
        val clear = TextView(this).apply {
            text = "✕"
            setTextColor(AppTheme.TextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            val touch = 40.dp
            layoutParams = LinearLayout.LayoutParams(touch, touch)
            background = AppTheme.tappable(
                AppTheme.surface(Color.TRANSPARENT, touch / 2f),
                AppTheme.neutral(0.10f),
            )
            setOnClickListener {
                input.setText("")
                onClear()
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                clear.visibility = if (editable.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AppTheme.surface(
                color = AppTheme.Surface,
                radius = 28.dp.toFloat(),
                strokeColor = AppTheme.neutral(0.07f),
            )
            setPadding(18.dp, 4.dp, 8.dp, 4.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 56.dp
            ).apply { topMargin = 18.dp }

            addView(TextView(this@MainActivity).apply {
                text = "⌕"
                setTextColor(AppTheme.Accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
                setPadding(0, 0, 12.dp, 0)
            })
            addView(input)
            addView(clear)
        }
    }

    /**
     * A segmented control: three labels over a rounded track, with a gold ember indicator that
     * slides between them.
     *
     * Chosen over plain buttons (which look unstyled) and over underline tabs (which read as web
     * navigation). A physical-feeling control with real motion is the single most "designed" element
     * on the screen, and the slide is the one animation that carries actual meaning here — it shows
     * where you came from and where you landed.
     */
    private fun segmentedControl(onSelect: (CatalogTab) -> Unit): Segmented {
        val tabs = CatalogTab.entries
        val inset = 4.dp

        val indicator = View(this).apply {
            background = AppTheme.liftedSurface(0xFF4A3A1C.toInt(), 0xFF2E2413.toInt(), 999f)
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val labelViews = tabs.map { tab ->
            TextView(this).apply {
                text = tab.label.uppercase()
                label(11f, AppTheme.TextMuted, tracking = 0.16f, bold = true)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }.also(labels::addView)
        }

        val track = FrameLayout(this).apply {
            background = AppTheme.surface(AppTheme.Surface, 999f, AppTheme.neutral(0.06f))
            addView(indicator, FrameLayout.LayoutParams(0, 0))
            addView(labels)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 46.dp
            ).apply { topMargin = 18.dp }
        }

        val segmented = Segmented(track, indicator, labelViews, tabs)
        labelViews.forEachIndexed { index, view ->
            view.setOnClickListener {
                segmented.select(tabs[index])
                onSelect(tabs[index])
            }
        }

        // The indicator can only be sized once the track has a width, so its first placement waits
        // for layout; after that, selection just animates translationX. Guarded on the measured
        // width actually changing — re-assigning layoutParams unconditionally here would request a
        // fresh layout on every layout pass, which is a loop.
        track.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            val segmentWidth = if (width > 0) width / tabs.size else 0
            if (segmentWidth <= 0 || segmentWidth == segmented.segmentWidth) return@addOnLayoutChangeListener
            segmented.segmentWidth = segmentWidth
            indicator.layoutParams = FrameLayout.LayoutParams(
                segmentWidth - inset, track.height - inset * 2
            ).apply { topMargin = inset; leftMargin = inset / 2 }
            segmented.select(segmented.current, animate = false)
        }
        return segmented
    }

    /** Holds the segmented control's views so selection can move the indicator and recolour labels. */
    private inner class Segmented(
        val view: View,
        private val indicator: View,
        private val labels: List<TextView>,
        private val tabs: List<CatalogTab>,
    ) {
        var segmentWidth: Int = 0
        var current: CatalogTab = CatalogTab.TRACKED
            private set

        fun select(tab: CatalogTab, animate: Boolean = true) {
            current = tab
            val index = tabs.indexOf(tab).coerceAtLeast(0)
            val target = (segmentWidth * index).toFloat()
            if (animate && segmentWidth > 0) {
                indicator.animate().translationX(target).setDuration(260L).start()
            } else {
                indicator.translationX = target
            }
            labels.forEachIndexed { i, view ->
                view.setTextColor(if (i == index) AppTheme.Accent else AppTheme.TextMuted)
            }
        }
    }

    /** A compact outlined pill — the track/untrack and remove control. */
    private fun pillButton(text: String, active: Boolean, onClick: (TextView) -> Unit): TextView =
        TextView(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            stylePill(this, text, active)
            setOnClickListener { onClick(this) }
        }

    /**
     * Outlined rather than filled: posters are the colour in these rows, and three filled gold
     * buttons down a list would fight them. The tracked state earns a tinted fill so the difference
     * is unmistakable without being loud.
     */
    private fun stylePill(view: TextView, text: String, active: Boolean) {
        view.text = text
        view.label(
            sizeSp = 10f,
            color = if (active) AppTheme.Accent else AppTheme.TextSecondary,
            tracking = 0.16f,
            bold = true,
        )
        view.setPadding(14.dp, 9.dp, 14.dp, 9.dp)
        view.background = AppTheme.tappable(
            AppTheme.surface(
                color = if (active) AppTheme.accent(0.12f) else Color.TRANSPARENT,
                radius = 999f,
                strokeColor = if (active) AppTheme.accent(0.45f) else AppTheme.neutral(0.16f),
            )
        )
    }

    private fun primaryButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text.uppercase()
        label(11f, AppTheme.Accent, tracking = 0.2f, bold = true)
        gravity = Gravity.CENTER
        setPadding(28.dp, 15.dp, 28.dp, 15.dp)
        background = AppTheme.tappable(
            AppTheme.surface(AppTheme.accent(0.10f), 999f, AppTheme.accent(0.42f))
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        setOnClickListener { onClick() }
    }

    /** Empty states get a voice rather than a shrug — a serif line and one plain sentence under it. */
    private fun emptyState(title: String, detail: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(24.dp, 48.dp, 24.dp, 24.dp)
        addView(TextView(this@MainActivity).apply {
            this.text = title
            display(20f, AppTheme.TextSecondary)
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            this.text = detail
            label(11.5f, AppTheme.TextMuted, tracking = 0.08f)
            gravity = Gravity.CENTER
            setPadding(0, 8.dp, 0, 0)
        })
    }

    companion object {
        const val EXTRA_SHOW_TITLE = "show_title"
        const val EXTRA_EPISODE_CODE = "episode_code"
        const val EXTRA_IMDB_ID = "imdb_id"
        const val EXTRA_OPEN_CATALOG = "open_catalog"
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}
