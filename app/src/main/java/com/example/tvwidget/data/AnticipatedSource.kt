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

/**
 * The live feed: notable episodes over the next fortnight, ranked by TVMaze's popularity weight.
 *
 * This is what POPULAR actually runs on. The bundled list it replaced held six shows with air dates
 * baked in when they were written, so by the time anyone read them they were describing premieres
 * that had already happened — and six rows is too few for a tab you scroll anyway.
 */
object TvMazeAnticipatedSource : AnticipatedSource {
    override suspend fun fetch(): List<AnticipatedShow> = TvMazeApi.popular()
}

/**
 * Bundled fallback. Still the answer when the network is down on a device that has never synced —
 * `AnticipatedSyncWorker` falls back to it rather than showing an empty tab.
 */
object BundledAnticipatedSource : AnticipatedSource {
    override suspend fun fetch(): List<AnticipatedShow> = SampleData.anticipated()
}
