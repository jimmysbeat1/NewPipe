# Movie discovery (Movies tab)

> **Status: preview, not yet built.** The code on this branch has not been compiled into an
> APK and has not run on a device or emulator. See [Test status](#test-status) for exactly what
> was and was not verified, and [Finishing the release](#finishing-the-release) for the steps
> that remain.

The Movies tab lists recent, recognisable films and says for each one how it can be watched in
Australia. It replaces the previous Movies tab, which listed old films from the Internet
Archive and Wikimedia Commons; that library is still part of the app (see
[What happened to the old Movies tab](#what-happened-to-the-old-movies-tab)).

* [What it does](#what-it-does)
* [What it deliberately does not do](#what-it-deliberately-does-not-do)
* [Setting it up: your TMDB key](#setting-it-up-your-tmdb-key)
* [How films are chosen and labelled](#how-films-are-chosen-and-labelled)
* [Watching a film](#watching-a-film)
* [Attribution and terms](#attribution-and-terms)
* [Privacy and network activity](#privacy-and-network-activity)
* [Test status](#test-status)
* [Finishing the release](#finishing-the-release)
* [Known limitations](#known-limitations)

## What it does

| Section | Content |
| --- | --- |
| New releases | Films released in the last 12 months, newest first |
| Free movies | Films that are free or free with ads in Australia |
| Hollywood | Popular American, English-language films |
| Anime movies | Japanese animated films |
| Action, Horror, Comedy, Sci-Fi | Films of that genre |
| Watchlist | Films you saved, stored on the device |
| Downloaded movies | Open-licence films you downloaded for offline viewing |

Every poster carries one label: **Free**, **Free with ads**, **Subscription**, **Rent** or
**Buy**, with the name of the provider underneath. The details of a film show its description,
release year, running time, genres, TMDB score, every way of watching it in Australia grouped
by cost, a trailer and a watchlist button.

Search, filters and sorting:

| Control | Choices | Default |
| --- | --- | --- |
| Search | By title, through TMDB | |
| How you can watch | Free only; free or subscription; anything watchable | Anything watchable |
| Release year | Last three years (2024–2026 at the time of writing); 2020 and later; any year | 2020 and later |
| Sort by | Recommended; most popular; newest; top rated | Recommended |
| Minimum TMDB score | Any; 6 or higher; 7 or higher | Any |

The chosen filters and the last section are remembered on the device.

## What it deliberately does not do

* It does **not** play, download or record films of Tubi, Plex, SBS On Demand, Netflix, Stan or
  any other streaming service. Those films are protected and licensed for the provider's own
  player only. The app opens the official provider instead.
* It does **not** bypass DRM, subscriptions, rentals, purchases, regional restrictions or ads.
* It does **not** label a film free unless the availability data says it is free or free with
  ads in Australia. A film that needs a subscription, a rental or a purchase always says so.
* It does **not** list a film it could not check. No availability data means no listing.
* It does **not** include a TMDB key. See below.

## Setting it up: your TMDB key

TMDB requires each user of an open-source app to use their own API key. Keys are free for
personal use.

1. Create an account at <https://www.themoviedb.org/> and request an API key under
   *Settings → API* (<https://www.themoviedb.org/settings/api>).
2. In PipeHome open *Movies* and choose *Enter TMDB key* (or *menu → TMDB key*).
3. Paste either the "API Key" (32 characters) or the "API Read Access Token" and choose *Check
   and save*. The key is checked with TMDB before it is stored.

Without a key the tab explains this and shows no films from TMDB. Watchlist, Downloaded
movies, the open-licence film library and the YouTube movie channels work without one.

How the key is handled:

* It is encrypted with an AES-256-GCM key held in the Android Keystore, which cannot leave
  the device, and written to the app's no-backup directory. It is not in the NewPipe settings
  export, not in Android backups, and never shown again after saving.
* If the device cannot encrypt it, it is kept in memory until the app is closed and not
  written to storage at all. The app tells you when that happens.
* It is sent only to `api.themoviedb.org`, over HTTPS. Error messages and logs never contain
  it. *menu → TMDB key → Remove saved key* deletes it.
* It is **not** built into the APK and there is no build option to do so: a key inside a
  published APK can be extracted by anyone.

## How films are chosen and labelled

1. **Candidates.** The app asks TMDB's `discover/movie` endpoint for feature films (60 minutes
   or longer, not adult, already released) that have an offer in Australia of a kind the
   filters accept (`watch_region=AU` with `with_watch_monetization_types`). To favour
   recognisable titles, films with very few ratings are left out (at least 25 votes; 100 for
   Hollywood; 10 for Free movies and Anime; 200 when sorting by score).
2. **Check.** TMDB's list does not say *how* each film can be watched, and its filter is not
   reliable enough to label by. So the app looks up every candidate with
   `movie/{id}/watch/providers` and reads the entry for Australia.
3. **Label.** The label is the cheapest way found: `free` → Free, `ads` → Free with ads,
   `flatrate` → Subscription, `rent` → Rent, `buy` → Buy. Unknown categories are ignored, so
   they can never be mistaken for free.
4. **Filter.** A film without any Australian offer, or whose only offers the filters exclude,
   is dropped. In a search the number of dropped matches is shown instead of the matches.

Availability is cached for 24 hours and lists for 6 hours; lookups run four at a time, and
one load never fetches more than three TMDB pages. Pull down to refresh.

These thresholds are a first choice made without access to live data. Whether they produce
well-filled sections for Australia has to be checked with a real key (see
[Finishing the release](#finishing-the-release)).

## Watching a film

| Situation | What the app offers |
| --- | --- |
| Film is on a streaming service or in a store | The main button names the cheapest option, e.g. *Watch free with ads on Tubi TV* or *Rent from Apple TV*. It opens TMDB's where-to-watch page for the film in your browser, which links to the official provider with links supplied by JustWatch. |
| Provider is SBS On Demand or YouTube | The button opens that provider's own search for the title (in its app if installed). |
| Film has a trailer on YouTube | *Play trailer in PipeHome* plays it with the NewPipe player. |
| Film is also in the open-licence library | *Watch or download in PipeHome (open licence)* opens it there, with streaming and downloads. |
| Film is published by a known distributor on YouTube | *Watch in PipeHome* plays that upload with the NewPipe player. |

The last two only appear when the title matches exactly (ignoring case, punctuation, accents
and a leading article) **and** both sides state a release year at most one apart, so a remake
is not offered as the original. For films from 2020 onwards such matches will be rare.

Why the watch page and not a direct link: TMDB does not publish links into providers, and the
only search addresses that could be confirmed while writing this were those of SBS On Demand
and YouTube. Guessed addresses for Tubi, Plex and others were left out on purpose.

**Downloads** are offered only for films of the open-licence library, exactly as before
([film-library.md](film-library.md)). They appear under *Downloaded movies*.

## Attribution and terms

* The header of the tab shows "Film data: TMDB • Where to watch: JustWatch". Tapping it, or
  *menu → About movie data*, shows the notice TMDB requires — "This product uses the TMDB API
  but is not endorsed or certified by TMDB." — and names JustWatch as the source of the
  where-to-watch data, with links to both. The details of every film repeat the attribution.
* **Not done: the TMDB logo.** TMDB's terms also ask for one of its approved logos. The logo
  file could not be fetched while writing this and is not in the app. Add an approved logo
  from <https://www.themoviedb.org/about/logos-attribution> to the About dialog before giving
  builds to other people.
* TMDB's API terms allow free use for non-commercial purposes; commercial use needs TMDB's
  agreement. Read <https://www.themoviedb.org/api-terms-of-use> before distributing the app.
* This is a description of what the app does, not legal advice.

## Privacy and network activity

* No account, no telemetry, no new permission, no background activity.
* The tab contacts `api.themoviedb.org` (lists, search, details, availability; carries your
  key) and `image.tmdb.org` (posters; carries no key). TMDB sees your IP address, your key
  and what you browse or search for. JustWatch is not contacted by the app.
* Opening a provider or the where-to-watch page hands over to your browser or the provider's
  app, where their own terms and tracking apply.
* Watchlist, filters, cached lists and cached availability are JSON files in the app's
  private storage (`files/personalisation/discover_*.json`). They are not part of the NewPipe
  database or its export.

## Test status

"Local" means the Kotlin compiler and JUnit on the machine this was written on. That machine
has no Android SDK, so only code without Android dependencies could be compiled there.

| What | Status | Evidence |
| --- | --- | --- |
| Labelling from availability data; paid and unknown offers never read as free; other countries ignored | **Tested locally** | `TmdbClientTest`, `DiscoverRepositoryTest` |
| Requests for every section, year/sort/score filters, Free movies only asking for free offers | **Tested locally** | `DiscoverQueryTest` |
| Films without Australian offers, or whose lookup failed, are not listed; rejected key and unreachable service are reported | **Tested locally** | `DiscoverRepositoryTest` |
| Paging limit, availability cache (24 h), list cache, search with hidden-count, watchlist, preferences | **Partially tested locally** — the logic ran against an in-memory store; writing the same data as JSON files was not exercised | `DiscoverRepositoryTest` |
| Key formats, key sent as parameter or header, key never in error messages, requests only to the TMDB API | **Tested locally** | `TmdbClientTest` |
| Key storage logic (sealed on disk, removed, memory-only when encryption fails) | **Tested locally** with a stand-in for the Keystore | `TmdbCredentialsTest` |
| Strict matching against the open-licence library and YouTube catalogue; provider links | **Tested locally** | `LocalMatchesTest` |
| Encryption with the real Android Keystore | **Not tested** | Code only |
| The whole Android part: Movies tab, poster grid, dialogs, filters, key dialog, tab wiring | **Not compiled, not tested** | Code only; resource and view references were cross-checked by script |
| Robolectric screen tests for the new tab (`MainScreenSmokeTest`, two new tests) and the updated device test (`MainTabsTest`) | **Written, never run** | |
| ktlint, checkstyle, Android lint | **Not run** | |
| Real TMDB API: that the requests are accepted, that sections are well filled for Australia, provider names | **Not tested** — no key was available | `TmdbLiveTest` exists for this |
| APK build | **Not done** | GitHub Actions is disabled on the repository; no run was started for this branch |
| Anything on a phone, including GrapheneOS | **Not tested** | |

All existing tests of the film library, the YouTube movie channels and the Home feed were
left in place; whether they still pass is unknown until CI runs.

## Finishing the release

1. **Re-enable GitHub Actions** for `jimmysbeat1/NewPipe` (repository *Settings → Actions*).
   The API reports "Actions has been disabled for this repository", so the push of this branch
   started no build.
2. **Run CI** on `claude/pipehome-modern-movies` (*Actions → CI → Run workflow*, or push any
   commit). Expect compile, ktlint or test failures on the first run: the Android part of
   this branch has never been compiled. Fix what it reports.
3. **Download the APK** from the run: artifact `PipeHome-debug-apk` (installs next to other
   builds) or `PipeHome-apk`.
4. **Optional, recommended:** add a TMDB key as repository secret `PIPEHOME_TMDB_KEY`. The CI
   job `tmdb-live` then runs `TmdbLiveTest` against the real API and uploads a report of what
   each section returned for Australia. The secret is used by that test only and is never
   built into an APK. Locally:
   `PIPEHOME_TMDB_KEY=... ./gradlew :app:testDebugUnitTest --tests "*TmdbLiveTest*"`
5. **On a phone:** enter the key, open every section, apply each filter, search, open details,
   use the main button for a Tubi, a Plex, an SBS On Demand and a YouTube title, play a
   trailer, add and remove a watchlist entry, restart the app and confirm the key, watchlist
   and filters are still there, check small and large display scaling and TalkBack labels.
6. **Before sharing builds:** add the TMDB logo (see above).

## Known limitations

* The main button usually leads to TMDB's where-to-watch page, one tap away from the
  provider, rather than straight into the provider's app.
* Availability comes from JustWatch through TMDB and can be out of date or incomplete. Free
  catalogues change often. The provider has the final word.
* The country is fixed to Australia.
* Films cannot be cast, and trailers are the only TMDB content played in the app.
* The watchlist of the new tab is separate from the watchlist of the YouTube movie channels,
  and neither is part of NewPipe's export.
* Texts of the section are English only.
* The first load of a section makes up to about 60 small requests (one per candidate film).

## What happened to the old Movies tab

Nothing was removed.

* **Open-licence film library** (Internet Archive, Wikimedia Commons, with downloads):
  *Movies → menu → Open-licence film library*, or add the tab *Open-licence films* under
  *Settings → Content → Content of main page*. Its downloads also appear under *Downloaded
  movies*. See [film-library.md](film-library.md).
* **YouTube movie channels** and **Purchased & rented movies**: *Movies → menu → YouTube movie
  channels*, or add that tab in the same setting.

## Code map

| Path (under `app/src/main/java/org/schabi/newpipe/movies/discover`) | Content |
| --- | --- |
| `DiscoverModels.kt` | Films, offers, availability; the `Access` levels |
| `TmdbClient.kt` | TMDB API client, key formats, error kinds, JSON parsing |
| `DiscoverQuery.kt` | Sections, filters, and the request each section makes |
| `DiscoverRepository.kt` | Labelling, filtering, paging, caches, watchlist, preferences |
| `TmdbCredentials.kt` | Encrypted storage of the key |
| `LocalMatches.kt` | Matching against films the app can play; provider links |
| `DiscoverFragment.kt`, `DiscoverViewModel.kt`, `DiscoverAdapter.kt` | The tab |
| `MovieDetailDialog.kt`, `DiscoverFilterDialog.kt`, `TmdbKeyDialog.kt` | Dialogs |
