package com.example.tvwidget.data

/**
 * Where the ANTICIPATED tab gets its list. The design calls for an auto-curated premiere feed —
 * TMDB `tv/on_the_air` + `trending`, or Trakt `shows/anticipated` — and explicitly *not* the user's
 * chosen shows.
 *
 * Implement this against the app's existing network stack and swap it in at
 * [com.example.tvwidget.work.AnticipatedSyncWorker.source]; nothing else changes.
 */
fun interface AnticipatedSource {
    suspend fun fetch(): List<AnticipatedShow>
}

/** Bundled list, used until a remote source is wired up. */
object BundledAnticipatedSource : AnticipatedSource {
    override suspend fun fetch(): List<AnticipatedShow> = SampleData.anticipated()
}
