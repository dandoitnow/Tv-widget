package com.example.tvwidget.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin client for the [TVMaze API](https://www.tvmaze.com/api) — free, keyless, and enough to back
 * the search screen and TODAY's real episode dates. Plain `HttpURLConnection` + `org.json` on
 * purpose: both ship with the platform, so this needs no new Gradle dependency.
 *
 * Every call is wrapped by its caller in `runCatching`; a network failure here should degrade to
 * "nothing new to show", never crash a sync.
 *
 * [browse] and [schedule] are short-TTL memoized: `AnticipatedSyncWorker.runOnce` fires on every
 * track/untrack tap, and without this, tracking three shows back-to-back would redo the entire daily
 * schedule fetch plus every already-tracked show's episode lookup, three times over, just because one
 * show changed. A cache this process-lifetime-only and this short only exists to collapse that kind
 * of back-to-back re-triggering — it is not a freshness/offline strategy.
 */
object TvMazeApi {

    private const val TAG = "TvMazeApi"
    private const val BASE = "https://api.tvmaze.com"
    private const val SCHEDULE_TTL_MS = 15 * 60_000L
    private const val BROWSE_TTL_MS = 10 * 60_000L

    /** How many days of schedule [browse] pools together. See its doc for why this isn't 1. */
    private const val BROWSE_DAYS = 4

    /**
     * How far ahead [popular] looks. Long enough that the window reliably contains real premieres
     * rather than only mid-season episodes, and short enough to stay affordable: each day costs two
     * requests of roughly 100-200KB, so this is the length where "enough premieres to be worth
     * scrolling" stops being cheap.
     */
    private const val POPULAR_DAYS = 10

    /**
     * Much longer than [BROWSE_TTL_MS], because this is the expensive call and the least volatile
     * thing the app fetches. A ten-day schedule does not meaningfully change within a few hours, and
     * `runOnce` fires on every single track/untrack tap — without a long TTL, tracking four shows in
     * a row would re-pull twenty requests four times over to produce the same list.
     */
    private const val POPULAR_TTL_MS = 6 * 60 * 60_000L

    data class EpisodeInfo(
        val airDate: LocalDate,
        val airTime: String,
        val code: String,
        val season: Int = 0,
        val number: Int = 0,
    )
    data class ShowSchedule(
        val previous: EpisodeInfo?,
        val next: EpisodeInfo?,
        val imdbId: String? = null,
        /** Episodes per season number, for the season-progress bar. */
        val seasonLengths: Map<Int, Int> = emptyMap(),
    )

    private val scheduleCache = ConcurrentHashMap<Int, Pair<Long, ShowSchedule>>()

    @Volatile
    private var browseCache: Pair<Long, List<CatalogueShow>>? = null

    @Volatile
    private var popularCache: Pair<Long, List<AnticipatedShow>>? = null

    /**
     * Free-text search, used by the search screen in `MainActivity`. Results are re-ranked by
     * TVMaze's own `weight` field (its internal popularity signal) rather than left in the API's
     * default relevance order, so a query like "love island" surfaces the well-known original ahead
     * of its many regional spin-offs and unrelated same-named shows.
     */
    suspend fun search(query: String): List<CatalogueShow> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = get("$BASE/search/shows?q=$encoded") ?: return@withContext emptyList()
        val results = JSONArray(body)
        (0 until results.length())
            .mapNotNull { i -> results.optJSONObject(i)?.optJSONObject("show") }
            .sortedByDescending { it.optInt("weight", 0) }
            .map(::toCatalogueShow)
    }

    /**
     * A "trending" slice for CATALOGUE's TRENDING tab. TVMaze has no dedicated trending endpoint, so
     * this approximates one: the web/TV schedule (i.e. shows actually airing something right now,
     * not TVMaze's full multi-thousand-show index) ranked by its own `weight` field — the same
     * popularity signal [search] re-ranks by. "Currently running" plus "popular" together is a
     * reasonable proxy for trending without a real trending API to call.
     *
     * The window spans [BROWSE_DAYS] days rather than just today. A single day's schedule yields a
     * thin list — most of it one-off talk and news programming once you get past the first handful —
     * which left the tab with almost nothing to scroll. Widening the window deepens the pool without
     * giving up the "currently running" property that makes the ranking mean anything.
     *
     * The full ranked list is what gets cached; [limit] is applied to the *result*, so callers
     * asking for different depths share one fetch instead of poisoning each other's cache entry.
     *
     * @throws java.io.IOException if every underlying request failed, so the caller (whose
     *   `runCatching` drives `AnticipatedSyncWorker`'s retry-and-preserve-old-data behavior) can tell
     *   "the network is down" apart from "fetched fine, nothing is scheduled" — [get] swallows its
     *   own failures into a `null` body, so that distinction has to be made here.
     */
    suspend fun browse(limit: Int = 120): List<CatalogueShow> = withContext(Dispatchers.IO) {
        browseCache?.let { (fetchedAt, cached) ->
            if (System.currentTimeMillis() - fetchedAt < BROWSE_TTL_MS) {
                return@withContext cached.take(limit)
            }
        }
        val today = LocalDate.now()
        val urls = (0 until BROWSE_DAYS).flatMap { offset ->
            val date = today.plusDays(offset.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            listOf("$BASE/schedule/web?date=$date", "$BASE/schedule?date=$date")
        }
        // In parallel: serially this would be BROWSE_DAYS * 2 round trips stacked end to end, which
        // is long enough to be felt as a stall when the tab opens.
        val bodies = coroutineScope { urls.map { async { get(it) } }.awaitAll() }.filterNotNull()
        if (bodies.isEmpty()) throw java.io.IOException("All TVMaze schedule requests failed")

        val shows = LinkedHashMap<Int, JSONObject>()
        bodies.forEach { body ->
            val entries = JSONArray(body)
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                // Web schedule embeds the show under "_embedded.show"; TV schedule inlines it.
                val showJson = entry.optJSONObject("show")
                    ?: entry.optJSONObject("_embedded")?.optJSONObject("show")
                    ?: continue
                shows.putIfAbsent(showJson.optInt("id"), showJson)
            }
        }
        shows.values
            .sortedByDescending { it.optInt("weight", 0) }
            .map(::toCatalogueShow)
            .also { browseCache = System.currentTimeMillis() to it }
            .take(limit)
    }

    /**
     * The POPULAR tab's feed: notable episodes landing over the next couple of weeks, ranked by
     * TVMaze's own `weight` popularity signal.
     *
     * This replaced a hardcoded list of six shows whose air dates were baked in at authoring time
     * and had long since drifted into the past. Six is also simply too few for a tab you scroll.
     *
     * The window is wider than [browse]'s because the two tabs want different things. TRENDING is
     * "what is on right now", so a few days is the whole point. POPULAR is "what is coming", which
     * needs enough runway to actually contain premieres — most weeks have none at all inside four
     * days. [POPULAR_DAYS] is where that stops being true without the request count getting silly.
     *
     * One entry per show, keeping its earliest upcoming episode, so a show airing daily doesn't
     * occupy half the list. Episodes airing earlier today are dropped: this list is about what is
     * still ahead.
     */
    suspend fun popular(limit: Int = 40): List<AnticipatedShow> = withContext(Dispatchers.IO) {
        popularCache?.let { (fetchedAt, cached) ->
            if (System.currentTimeMillis() - fetchedAt < POPULAR_TTL_MS) return@withContext cached.take(limit)
        }
        val today = LocalDate.now()
        val urls = (0 until POPULAR_DAYS).flatMap { offset ->
            val date = today.plusDays(offset.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            listOf("$BASE/schedule/web?date=$date", "$BASE/schedule?date=$date")
        }
        val bodies = coroutineScope { urls.map { async { get(it) } }.awaitAll() }.filterNotNull()
        if (bodies.isEmpty()) throw java.io.IOException("All TVMaze schedule requests failed")

        // Keyed by show id, keeping whichever episode airs soonest.
        val soonest = HashMap<Int, Pair<JSONObject, JSONObject>>()
        bodies.forEach { body ->
            val entries = JSONArray(body)
            for (i in 0 until entries.length()) {
                val episode = entries.optJSONObject(i) ?: continue
                val show = episode.optJSONObject("show")
                    ?: episode.optJSONObject("_embedded")?.optJSONObject("show")
                    ?: continue
                val airDate = runCatching { LocalDate.parse(episode.optString("airdate")) }.getOrNull()
                    ?: continue
                if (airDate.isBefore(today)) continue
                val id = show.optInt("id")
                val existing = soonest[id]
                if (existing == null || airDate.isBefore(airDateOf(existing.second))) {
                    soonest[id] = show to episode
                }
            }
        }

        soonest.values
            .sortedByDescending { (show, episode) -> rankOf(show, episode) }
            .map { (show, episode) -> toAnticipatedShow(show, episode, today) }
            .also { popularCache = System.currentTimeMillis() to it }
            .take(limit)
    }

    /**
     * Ordering score for POPULAR. Popularity alone is not enough: TVMaze's weight tops out at 100
     * for a great many long-running shows, so a straight weight sort fills the head of the list with
     * `Cops`, `WWE`, `The Daily Show` and `Big Brother` — all genuinely popular, none of them
     * something to tell someone about, and collectively indistinguishable from what TRENDING already
     * shows. A real sample of the next fortnight put only two premieres in the top twenty-five.
     *
     * So premieres get a boost. The size of it matters more than it looks: a larger boost (+55/+40
     * was the first attempt) swamps the weight entirely, and the list then ranks a weight-85 foreign
     * reality pilot above a weight-99 return of a flagship drama, which is plainly wrong. These
     * values tilt the ordering without overriding it — premieres lead, but popularity still decides
     * the order among them.
     *
     * The score orders the list only. [AnticipatedShow.hypePercent] keeps the raw weight, because
     * the hype bar is showing popularity and should not show something invented.
     */
    private fun rankOf(show: JSONObject, episode: JSONObject): Int {
        val weight = show.optInt("weight", 0)
        val season = episode.optInt("season", 0)
        val number = episode.optInt("number", 0)
        return weight + when {
            number == 1 && season <= 1 -> 24 // a brand new series is the most interesting row here
            number == 1 -> 18 // a returning season
            else -> 0
        }
    }

    private fun airDateOf(episode: JSONObject): LocalDate =
        runCatching { LocalDate.parse(episode.optString("airdate")) }.getOrDefault(LocalDate.MAX)

    private fun toAnticipatedShow(show: JSONObject, episode: JSONObject, today: LocalDate): AnticipatedShow {
        val airDate = airDateOf(episode)
        val season = episode.optInt("season", 0)
        val number = episode.optInt("number", 0)
        val network = show.optJSONObject("network")?.optString("name")
            ?: show.optJSONObject("webChannel")?.optString("name")
            ?: "UNKNOWN"
        return AnticipatedShow(
            title = show.optString("name"),
            // A first episode is the interesting case, and season 1 of it doubly so — those are the
            // rows worth putting a list like this in front of someone for.
            kind = when {
                number == 1 && season <= 1 -> "NEW SERIES"
                number == 1 -> "S%02d PREMIERE".format(season)
                else -> "S%02dE%02d".format(season, number)
            },
            network = network.uppercase(java.util.Locale.US),
            premiereDate = airDate.format(DateTimeFormatter.ofPattern("dd MMM", java.util.Locale.US))
                .uppercase(java.util.Locale.US),
            daysAway = java.time.temporal.ChronoUnit.DAYS.between(today, airDate).toInt(),
            // TVMaze's weight is already a 0..100 popularity score, which is exactly what the hype
            // bar wants — no rescaling, and no invented number pretending to be a measurement.
            hypePercent = show.optInt("weight", 0).coerceIn(0, 100),
            posterUrl = show.optJSONObject("image")?.optString("medium")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Poster lookup for shows we only know by title (the bundled demo content) — see
     * [singleSearchVerified] for why the candidate's name is checked before trusting its data.
     */
    suspend fun posterFor(title: String): String? =
        singleSearchVerified(title)?.optJSONObject("image")?.optString("medium")?.takeIf { it.isNotBlank() }

    /**
     * IMDb id lookup for a show we only know by title — the live fallback `MainActivity` uses when a
     * TODAY row's title is tapped before a sync has ever backfilled [Release.imdbId] for it. See
     * [singleSearchVerified] for why the candidate's name is checked before trusting its data.
     */
    suspend fun imdbIdFor(title: String): String? = singleSearchVerified(title)?.let(::imdbIdFrom)

    /**
     * `singlesearch` returns TVMaze's best guess even when nothing actually matches, so a loose or
     * generic title ("Silo", "Wednesday") can come back with an unrelated show. The candidate's own
     * name is checked against [title] (case/punctuation-insensitive) before its data is trusted;
     * anything that doesn't match closely enough returns `null` rather than a confident-looking wrong
     * answer.
     */
    private suspend fun singleSearchVerified(title: String): JSONObject? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val body = get("$BASE/singlesearch/shows?q=$encoded") ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        json.takeIf { namesMatch(title, it.optString("name")) }
    }

    /** Loose equality: case, punctuation, and whitespace differences don't count as a mismatch. */
    private fun namesMatch(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(value: String): String =
        value.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]"), "")

    /** Previous/next aired episode for one show, used to build TODAY rows for tracked shows. */
    suspend fun schedule(tvMazeId: Int): ShowSchedule = withContext(Dispatchers.IO) {
        scheduleCache[tvMazeId]?.let { (fetchedAt, cached) ->
            if (System.currentTimeMillis() - fetchedAt < SCHEDULE_TTL_MS) return@withContext cached
        }
        // A failed fetch (null body) deliberately isn't cached — only a real answer is worth
        // remembering; caching "no data" would mask a network blip as "this show has no episodes"
        // for the next 15 minutes.
        // `seasons` rides along on this same response: one more embed, no extra request, and it is
        // the only place TVMaze reports how many episodes a season actually holds.
        val body = get("$BASE/shows/$tvMazeId?embed[]=previousepisode&embed[]=nextepisode&embed[]=seasons")
            ?: return@withContext ShowSchedule(null, null)
        // The show's own IMDb id (externals.imdb) rides along on this same response for free — no
        // extra request needed to give a tracked show's TODAY rows a direct IMDb link.
        val json = JSONObject(body)
        val embedded = json.optJSONObject("_embedded")
        ShowSchedule(
            previous = embedded?.optJSONObject("previousepisode")?.let(::toEpisodeInfo),
            next = embedded?.optJSONObject("nextepisode")?.let(::toEpisodeInfo),
            imdbId = imdbIdFrom(json),
            seasonLengths = seasonLengthsFrom(embedded),
        ).also { scheduleCache[tvMazeId] = System.currentTimeMillis() to it }
    }

    /** Season number to episode count. `episodeOrder` is null for a season still being ordered. */
    private fun seasonLengthsFrom(embedded: JSONObject?): Map<Int, Int> {
        val seasons = embedded?.optJSONArray("seasons") ?: return emptyMap()
        val out = HashMap<Int, Int>()
        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val order = season.optInt("episodeOrder", 0)
            if (order > 0) out[season.optInt("number")] = order
        }
        return out
    }

    private fun imdbIdFrom(json: JSONObject): String? =
        json.optJSONObject("externals")?.optString("imdb")?.takeIf { it.isNotBlank() && it != "null" }

    private fun toCatalogueShow(json: JSONObject): CatalogueShow {
        val network = json.optJSONObject("network")?.optString("name")
            ?: json.optJSONObject("webChannel")?.optString("name")
            ?: "UNKNOWN"
        return CatalogueShow(
            tvMazeId = json.optInt("id"),
            title = json.optString("name"),
            network = network.uppercase(java.util.Locale.US),
            status = json.optString("status", "UNKNOWN").uppercase(java.util.Locale.US),
            posterUrl = json.optJSONObject("image")?.optString("medium")?.takeIf { it.isNotBlank() },
            tracked = false,
            imdbId = imdbIdFrom(json),
        )
    }

    private fun toEpisodeInfo(json: JSONObject): EpisodeInfo? {
        val airstamp = json.optString("airstamp").takeIf { it.isNotBlank() } ?: return null
        val dateTime = runCatching { LocalDateTime.parse(airstamp, DateTimeFormatter.ISO_DATE_TIME) }
            .getOrNull() ?: return null
        val season = json.optInt("season")
        val number = json.optInt("number")
        return EpisodeInfo(
            season = season,
            number = number,
            airDate = dateTime.toLocalDate(),
            airTime = dateTime.format(DateTimeFormatter.ofPattern("HH:mm")),
            code = "S%02dE%02d".format(season, number),
        )
    }

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "GET $url failed: ${t.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
