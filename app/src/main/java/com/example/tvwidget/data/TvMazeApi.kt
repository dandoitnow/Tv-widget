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

/**
 * Thin client for the [TVMaze API](https://www.tvmaze.com/api) — free, keyless, and enough to back
 * CATALOGUE's search/browse and TODAY's real episode dates. Plain `HttpURLConnection` + `org.json`
 * on purpose: both ship with the platform, so this needs no new Gradle dependency.
 *
 * Every call is wrapped by its caller in `runCatching`; a network failure here should degrade to
 * "nothing new to show", never crash a sync.
 */
object TvMazeApi {

    private const val TAG = "TvMazeApi"
    private const val BASE = "https://api.tvmaze.com"

    data class EpisodeInfo(val airDate: LocalDate, val airTime: String, val code: String)
    data class ShowSchedule(val previous: EpisodeInfo?, val next: EpisodeInfo?)

    /** Free-text search, used by the CATALOGUE search screen in `MainActivity`. */
    suspend fun search(query: String): List<CatalogueShow> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = get("$BASE/search/shows?q=$encoded") ?: return@withContext emptyList()
        val results = JSONArray(body)
        (0 until results.length()).mapNotNull { i ->
            results.optJSONObject(i)?.optJSONObject("show")?.let(::toCatalogueShow)
        }
    }

    /**
     * A browseable "all shows" slice for the CATALOGUE tab's default list, before the user has
     * searched for anything: today's web/TV schedule, deduplicated by show. TVMaze has no dedicated
     * "trending" endpoint, so the daily schedule is the closest keyless proxy for "shows people are
     * currently watching".
     */
    suspend fun browse(limit: Int = 25): List<CatalogueShow> = withContext(Dispatchers.IO) {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val webBody = get("$BASE/schedule/web?date=$today")
        val tvBody = get("$BASE/schedule?date=$today")
        val shows = LinkedHashMap<Int, CatalogueShow>()
        listOfNotNull(webBody, tvBody).forEach { body ->
            val entries = JSONArray(body)
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                // Web schedule embeds the show under "_embedded.show"; TV schedule inlines it.
                val showJson = entry.optJSONObject("show")
                    ?: entry.optJSONObject("_embedded")?.optJSONObject("show")
                    ?: continue
                val show = toCatalogueShow(showJson)
                shows.putIfAbsent(show.tvMazeId, show)
            }
        }
        shows.values.take(limit)
    }

    /** Poster lookup for shows we only know by title (the bundled demo content). */
    suspend fun posterFor(title: String): String? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val body = get("$BASE/singlesearch/shows?q=$encoded") ?: return@withContext null
        runCatching { JSONObject(body).optJSONObject("image")?.optString("medium") }.getOrNull()
    }

    /** Previous/next aired episode for one show, used to build TODAY rows for tracked shows. */
    suspend fun schedule(tvMazeId: Int): ShowSchedule = withContext(Dispatchers.IO) {
        val body = get("$BASE/shows/$tvMazeId?embed[]=previousepisode&embed[]=nextepisode")
            ?: return@withContext ShowSchedule(null, null)
        val embedded = JSONObject(body).optJSONObject("_embedded")
        ShowSchedule(
            previous = embedded?.optJSONObject("previousepisode")?.let(::toEpisodeInfo),
            next = embedded?.optJSONObject("nextepisode")?.let(::toEpisodeInfo),
        )
    }

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
