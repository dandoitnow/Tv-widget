# TV Release Tracker — 5×2 home-screen widget

An Android home-screen widget that tracks TV episode releases, built with Jetpack Glance from the
`TV Widget 5x2` design handoff (option **1b**, the poster feed).

Three tabs live inside one 5×2 widget:

- **TODAY** — watchlist releases in chronological order, aired above and scheduled below.
- **ANTICIPATED** — an auto-curated premiere list, refreshed daily. Never the user's chosen shows.
- **FAVORITES** — saved episodes grouped by show, each with a rewatch counter and log.

## Layout

```
app/src/main/java/com/example/tvwidget/
  MainActivity.kt              Host activity + deep-link target for a whole-row tap
  data/
    Models.kt                  Release, AnticipatedShow, FavoriteEpisode, FavoriteShow, Tab
    SampleData.kt              Stand-in content, generated relative to the current day
    AnticipatedSource.kt       Seam for the TMDB/Trakt premiere feed
    Countdown.kt               HH:MM until the next air time
    WidgetState.kt             Everything persisted in the Glance DataStore
  widget/
    TvWidget.kt                GlanceAppWidget root; header + the selected tab
    TvWidgetReceiver.kt        Provider; arms/tears down the workers
    Header.kt                  Tab pills and the right-hand readout
    TodayFeed.kt               TODAY tab
    AnticipatedList.kt         ANTICIPATED tab
    FavoritesList.kt           FAVORITES tab
    Common.kt                  Poster block, star toggle, hairline
    WidgetActions.kt           Every ActionCallback the widget binds to
    Modifiers.kt               cornerRadiusCompat
  ui/Tokens.kt                 The handoff's design tokens, one per value
  work/
    AnticipatedSyncWorker.kt   Daily refresh of the premiere list
    CountdownTicker.kt         Per-minute redraw while TODAY is showing
```

## State

All widget state is persisted with `PreferencesGlanceStateDefinition`, so the widget restores
identically after a launcher restart: selected tab, favorites (keyed on show + episode code), the
rewatch log per show, which show is expanded, which log dropdown is open, the cached anticipated
list and its last sync time. The rewatch count is always derived from the log's length and is never
stored separately.

## Wiring in real data

Two seams, and nothing else in the widget changes:

- **TODAY** — replace `SampleData.releases()` with a query against the app's watchlist (episode-level
  air dates from TMDB/Trakt), over a window of at least −3 to +7 days.
- **ANTICIPATED** — implement `AnticipatedSource` against the app's network stack and assign it to
  `AnticipatedSyncWorker.source`. The worker caches the result in widget state; the tab always
  renders from disk.
- **Posters** — `Common.Poster` draws a placeholder. Point its `ImageProvider` at the cached key art
  (portrait, ~78 × 108px for 3×). Widgets cannot fetch images at draw time, so the bitmap has to be
  on disk already.

## Where the widget departs from the design, and why

The handoff is high fidelity and the colours, sizes, radii and row heights are implemented exactly.
Four behaviours in the HTML mock have no RemoteViews equivalent, so they are approximated:

1. **Resting scroll position.** The design pins TODAY to today's first release, scrollable back into
   aired episodes. Glance's `LazyColumn` exposes no scroll-position API. Instead the aired rows start
   collapsed behind a single `N AIRED` header row, so today's first release is the first thing drawn;
   tapping the header expands them in place, and leaving the tab re-collapses them so returning
   re-pins today. Chronology is preserved either way.
2. **Inertial scroll and 46dp snapping.** `LazyColumn` uses the platform `ListView` fling. Velocity
   decay and row-boundary settling are not configurable from Glance.
3. **Animation.** The 2s pulse on the live dot, the 300ms tab colour transition and the 90° caret
   rotation cannot be expressed in RemoteViews. The dot is a static accent dot, tab colours switch
   instantly, and the caret is two glyphs rather than one rotated one.
4. **Letter-spacing.** Glance has no tracking control, so the 0.08–0.18em on mono text is dropped.
   Sizes, weights and casing are exact.

Two more deliberate calls:

- **Countdown cadence.** The mock ticks `HH:MM:SS` every second. On device that is a battery cost for
  a readout nobody watches, so the header renders `HH:MM` and `CountdownTicker` re-arms an inexact
  alarm on each minute boundary — only while TODAY is showing.
- **Tap targets.** Every interactive area is padded to ≥ 40dp while keeping the visual at its
  documented size, except the rewatch `−`/`+` buttons: three 40dp targets do not fit beside the show
  name in a 46dp row, so those two get 20dp. Worth a look on device before sign-off.

The display face is the platform serif; the mock uses Caprasimo, which Glance cannot load. Swap
`Tokens.display` for the host app's display face when one exists.

## Build

```
./gradlew assembleDebug
```

Requires JDK 17 and an Android SDK with API 34. CI builds and lints the debug APK on every push.
