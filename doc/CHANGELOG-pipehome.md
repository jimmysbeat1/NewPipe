# PipeHome changelog

Changes of this fork on top of NewPipe. Newest first.

## Unreleased — branch `claude/pipehome-modern-movies`

**Not yet built or run. See [modern-movies.md](modern-movies.md#test-status).**

### Added
* Movies tab: discovery of recent films through TMDB with the sections New releases, Free
  movies, Hollywood, Anime movies, Action, Horror, Comedy, Sci-Fi, Watchlist and Downloaded
  movies.
* Labels Free, Free with ads, Subscription, Rent and Buy for Australia, from where-to-watch
  data supplied by JustWatch through TMDB.
* Search, filters (way of watching, release year, minimum score), sorting, a watchlist and
  remembered viewing preferences, all stored on the device.
* Film details with every way to watch, a hand-over to the official provider, and trailer
  playback in the app.
* Entry and encrypted storage of the user's own TMDB key.
* TMDB and JustWatch attribution in the tab, in the film details and in *About movie data*.
* Tab type *Open-licence films* (id 104) for the previous Movies tab.
* Tests `TmdbClientTest`, `DiscoverQueryTest`, `DiscoverRepositoryTest`, `LocalMatchesTest`,
  `TmdbCredentialsTest`, two screen tests in `MainScreenSmokeTest`, and `TmdbLiveTest` with
  the optional CI job `tmdb-live`.

### Changed
* The default Movies tab (id 101) now opens the movie discovery. The open-licence film
  library and the YouTube movie channels moved to its menu and remain available as tabs.

### Not changed
* NewPipe's player, downloader, database, settings export and privacy behaviour.
* The film library, its licence audit and its downloads.

### Known gaps
* No APK: GitHub Actions is disabled on the repository.
* Android code not compiled; ktlint, lint and the screen tests not run.
* Not tried against the real TMDB API or on a device.
* The TMDB logo required by TMDB's terms is not included yet.

## 2026-11-16 — branch `claude/pipehome-film-library`
* Film library of downloadable full-length films in the Movies tab (see
  [film-library.md](film-library.md)).

## Earlier — branch `claude/newpipe-premium-privacy-9z952l`
* Fork identity, personalised Home, YouTube movie channels, Downloads tab (see
  [pipehome.md](pipehome.md)).
