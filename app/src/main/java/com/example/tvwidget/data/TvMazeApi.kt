package com.example.tvwidget.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
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

    data class EpisodeInfo(val airDate: LocalDate, val airTime: String, val code: String)
    data class ShowSchedule(val previous: EpisodeInfo?, val next: EpisodeInfo?, val imdbId: String? = null)

    private val scheduleCache = ConcurrentHashMap<Int, Pair<Long, ShowSchedule>>()

    @Volatile
    private var browseCache: Pair<Long, List<CatalogueShow>>? = null

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
     * this approximates one: today's web/TV schedule (i.e. shows actually airing something right
     * now, not TVMaze's full multi-thousand-show index) ranked by its own `weight` field — the same
     * popularity signal [search] re-ranks by — and truncated to [limit]. "Currently running" plus
     * "popular" together is a reasonable proxy for trending without a real trending API to call.
     *
     * @throws java.io.IOException if both underlying requests failed, so the caller (whose
     *   `runCatching` drives `AnticipatedSyncWorker`'s retry-and-preserve-old-data behavior) can tell
     *   "the network is down" apart from "fetched fine, nothing is scheduled today" — [get] swallows
     *   its own failures into a `null` body, so that distinction has to be made here.
     */
    suspend fun browse(limit: Int = 25): List<CatalogueShow> = withContext(Dispatchers.IO) {
        browseCache?.let { (fetchedAt, cached) ->
            if (System.currentTimeMillis() - fetchedAt < BROWSE_TTL_MS) return@withContext cached
        }
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val webBody = get("$BASE/schedule/web?date=$today")
        val tvBody = get("$BASE/schedule?date=$today")
        if (webBody == null && tvBody == null) {
            throw java.io.IOException("Both TVMaze schedule endpoints failed")
        }
        val shows = LinkedHashMap<Int, JSONObject>()
        listOfNotNull(webBody, tvBody).forEach { body ->
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
            .take(limit)
            .map(::toCatalogueShow)
            .also { browseCache = System.currentTimeMillis() to it }
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
        val body = get("$BASE/shows/$tvMazeId?embed[]=previousepisode&embed[]=nextepisode")
            ?: return@withContext ShowSchedule(null, null)
        // The show's own IMDb id (externals.imdb) rides along on this same response for free — no
        // extra request needed to give a tracked show's TODAY rows a direct IMDb link.
        val json = JSONObject(body)
        val embedded = json.optJSONObject("_embedded")
        ShowSchedule(
            previous = embedded?.optJSONObject("previousepisode")?.let(::toEpisodeInfo),
            next = embedded?.optJSONObject("nextepisode")?.let(::toEpisodeInfo),
            imdbId = imdbIdFrom(json),
        ).also { scheduleCache[tvMazeId] = System.currentTimeMillis() to it }
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
