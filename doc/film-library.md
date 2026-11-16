# The film library (Movies section)

The Movies section lists full-length films that may be downloaded, lets you stream them,
download them in a quality of your choice and watch the downloads offline.

* [What is shown](#what-is-shown)
* [Licence audit](#licence-audit)
* [Sources and APIs](#sources-and-apis)
* [Downloads and offline playback](#downloads-and-offline-playback)
* [Privacy and network activity](#privacy-and-network-activity)
* [Test status](#test-status)
* [Known limitations](#known-limitations)

## What is shown

A film is listed only if **all** of the following hold. The rules are code
(`movies/library/FilmRules.kt`, `LicenceAudit.kt` and the two `…Source.kt` files) and are covered
by unit tests.

| Rule | How it is checked |
| --- | --- |
| Full length | The running time of the video file itself is at least 40 minutes, the threshold the Academy, the AFI and the BFI use for a feature film. The running time comes from the file metadata of the source, not from a description. |
| Permission to download | The source declares, in machine-readable form, a Creative Commons licence or public-domain status. See [Licence audit](#licence-audit). |
| A film, not something else | Internet Archive: the item is in the *Feature Films* collection and in none of the TV, short-film, trailer or cartoon collections. Wikimedia Commons: the file is in the category tree of film videos. |
| No trailers, excerpts, shorts | Titles, subjects and categories are checked for trailer, teaser, clip, excerpt, short film and similar. |
| No TV shows or serials | Titles and labels are checked for episode, season, `S01E02`, chapter, part, serial, television series and similar. Internet Archive items that hold several long videos of different lengths (series, bundles) are rejected. |
| Playable on Android | Only MP4, WebM and MKV files are offered. Films that exist only as MPEG-2, AVI or Ogg Theora are not listed, because the offline player could not play them. |
| Not withdrawn or disputed | Dark (withdrawn) or access-restricted Internet Archive items and Commons files in deletion-request, missing-permission or licence-review categories are rejected. |
| Not adult material | Items labelled as pornographic, erotic or nudist films are left out. |

Candidates that fail are remembered together with the reason, so they are neither shown nor
fetched again. Right before every download the film is audited once more at its source; if it
no longer qualifies, the download is refused and the film is removed from the library.

## Licence audit

### What "verified permission" means here

The app verifies **what the source declares**. It accepts a film only when the declaration is
one of the following, and rejects everything else, including items with no licence, free-text
claims such as "public domain" in a description, custom terms, and licences it does not know:

| Accepted | Recognised from |
| --- | --- |
| Public domain | `creativecommons.org/publicdomain/mark/1.0/`, the retired `creativecommons.org/licenses/publicdomain/`, Commons licence codes `pd` and `pd-…` |
| CC0 | `creativecommons.org/publicdomain/zero/1.0/`, Commons `cc0` |
| CC BY, CC BY-SA | `creativecommons.org/licenses/by/…`, `…/by-sa/…`, Commons `cc-by-…`, `cc-by-sa-…` |
| CC BY-NC, CC BY-NC-SA | `…/by-nc/…`, `…/by-nc-sa/…` |
| CC BY-ND, CC BY-NC-ND | `…/by-nd/…`, `…/by-nc-nd/…` |

Every one of these permits anyone to download and keep a copy. The Creative Commons licences
attach conditions to **sharing** a film (attribution; for NC no commercial use; for ND no
modified versions; for SA the same licence). The app shows the licence of each film with a link
to its deed, and a link to the page of the film at its source, where the attribution is.

The app **cannot** verify that a declaration is correct:

* On the Internet Archive the licence is chosen by the person who uploads an item. The
  Internet Archive does not review it. Wrong public-domain claims exist.
* Wikimedia Commons reviews licences as a community and deletes files that turn out not to be
  free, which makes it the more reliable of the two, but not infallible.
* "Public domain" usually refers to the United States. A film can be in the public domain
  there and still be protected in other countries, **including Australia**. Commons marks such
  cases on the file page.

So this is a technical audit of declared licences, not legal advice and not a guarantee. If
the status of a film matters to you, open its page at the source from the film details.

### What the sources contained when this was built

Measured on 16 November 2026 through the APIs:

* Internet Archive, *Feature Films* collection: 28,536 items, of which 9,133 declare a
  Creative Commons licence or public-domain mark. Only those 9,133 are candidates.
  All licence values found in a sample of 3,000 of them were `creativecommons.org` URLs of the
  accepted kinds: 82% public domain, 3% CC0, 15% CC BY variants (most often BY-NC-ND).
* First page of candidates (the 40 most downloaded): 37 accepted, 3 rejected (a 20-minute
  short, a TV compilation, a bundle of several films).
* Wikimedia Commons, first two result pages (60 large film videos): 30 accepted, 25 rejected
  as shorter than 40 minutes, 5 rejected for disputed status.

### Licence of the app itself

PipeHome and NewPipe are licensed under the GPL v3 or later. The film library adds no
dependency: it uses OkHttp, kotlinx.serialization, jsoup, Coil and ExoPlayer, which NewPipe
already ships and lists under *About → Licences*. The films are not part of the app and are
not redistributed by it; they are fetched by the user's device from the sources.

## Sources and APIs

Only documented, public, unauthenticated APIs are used. No pages are scraped.

| Source | API | Used for |
| --- | --- | --- |
| Internet Archive | [Advanced search](https://archive.org/advancedsearch.php) `advancedsearch.php?q=…&output=json` | Finding candidates: `mediatype:movies AND collection:feature_films AND licenseurl:http*creativecommons.org*`, minus excluded collections, optionally narrowed by words or by genre subjects, sorted by downloads |
| Internet Archive | [Metadata API](https://archive.org/developers/md-read.html) `metadata/{identifier}` | The audit: licence, collections, subjects, and the list of files with length, height, size and SHA-1 |
| Internet Archive | `download/{identifier}/{file}` and [item image](https://archive.org/developers/) `services/img/{identifier}` | Streaming, downloading, artwork |
| Wikimedia Commons | [MediaWiki Action API](https://www.mediawiki.org/wiki/API:Main_page) `action=query&generator=search` with [CirrusSearch](https://www.mediawiki.org/wiki/Help:CirrusSearch) keywords `filetype:video filesize:>100000 deepcat:"Videos of films by decade"` | Finding candidates |
| Wikimedia Commons | `prop=videoinfo` with `extmetadata` and `derivatives` | The audit: duration, licence, categories, the original file and its WebM transcodes |

Requests identify the app with a `User-Agent` of the form
`PipeHome/<version> (https://github.com/jimmysbeat1/NewPipe; film library)`, as the
[Wikimedia User-Agent policy](https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy)
requires. At most four requests run in parallel, results are cached for a week, and nothing is
polled in the background.

Genres come from the subjects and collections (Internet Archive) or categories (Commons) of a
film, mapped onto the app's genre list. Films from before 1970 are also listed under
*Classics*.

Posters are the artwork the sources provide: the item image of the Internet Archive and a
frame of the video from Commons. These are often stills rather than theatrical posters.

## Downloads and offline playback

* **Quality.** *Download* lists every playable rendition with resolution, container and size,
  for example `480p • MP4 • 325 MB`. Sizes of Commons transcodes are estimates computed from
  their bitrate and are marked "about".
* **Progress.** Shown in the film details (bar, bytes, percent), as a badge on the poster, and
  in a notification with a *Pause* action. Downloads run in a foreground service, one at a
  time, and continue when you leave the app.
* **Resume.** A paused, failed or interrupted download continues from where it stopped (HTTP
  range request). Up to four automatic retries on network errors.
* **Integrity.** The Internet Archive publishes a SHA-1 checksum for every file, and Commons
  for original files. Downloads are verified against it; a mismatch discards the file. The
  details then say *Downloaded and checksum verified*. Commons transcodes have no published
  checksum and are only checked for completeness.
* **Storage.** Files are stored in the app's own storage area on the device
  (`Android/data/<app id>/files/Movies`). No storage permission is needed and nothing outside
  the app is touched. Uninstalling the app removes them. Free space is checked first.
* **Metered connections.** On mobile data you are asked before a download starts.
* **Offline playback.** *Play offline* plays the downloaded file in the built-in film player,
  without a connection. Playback resumes where you stopped. *Stream* plays from the source
  without downloading (up to 720p).
* **Delete.** The bin icon in the film details deletes the file after confirmation.

The film player is a small ExoPlayer-based player of its own. NewPipe's player is built
around NewPipe Extractor's services and is deliberately left untouched, which is why
background and popup playback are not available for library films.

## Privacy and network activity

* No account, no telemetry, no new permission. The existing notification permission is asked
  for when you start your first download, so that progress can be shown; downloads work
  without it.
* The section contacts only `archive.org` (and its download servers `*.archive.org`),
  `commons.wikimedia.org`, `upload.wikimedia.org` and `thumb.wikimedia.org`, over HTTPS, and
  only when you open or refresh the section, search, open the last rows of the list, or
  stream or download a film. Those services see your IP address and the request.
* The library, the audit results and the list of downloads are JSON files in the app's
  private storage. Playback positions are kept in a private preferences file.

## Test status

| What | Status | Evidence |
| --- | --- | --- |
| Licence audit, full-length and exclusion rules, genre mapping, title and duration parsing | Tested | JVM unit tests `LicenceAuditTest`, `FilmRulesTest` |
| Internet Archive and Commons response handling, query building, batched API responses | Tested against fixtures modelled on real responses | `InternetArchiveSourceTest`, `CommonsSourceTest` |
| Downloader: full download, resume, cancel and continue, restart when ranges are not supported, checksum mismatch, HTTP errors | Tested against a local HTTP server | `FilmDownloaderTest` |
| Library building, caching of rejections, paging, search, re-audit before download | Tested with fake sources | `FilmLibraryRepositoryTest` |
| Movies tab: poster grid, genre chips, film details, quality chooser | Tested on the JVM with Robolectric (real layouts, no device) | `MainScreenSmokeTest` |
| Real sources: search, audit, posters | Tested in CI against the live services | `FilmSourcesLiveTest`, job `film-sources-live` |
| Real downloads: interrupt, resume, complete, verify SHA-1 (Internet Archive); interrupt, resume, complete (Commons) | Tested in CI against the live services, on a Linux runner | `FilmSourcesLiveTest` |
| App starts and the Movies tab opens on Android | Tested on emulators, API 23 and 35 | `MainTabsTest`, job `test-android` |
| Download service, progress notification, pause from the notification | **Not tested on a device** | Code only |
| Offline and streaming playback in the film player | **Not tested on a device** | Code only |
| Behaviour on GrapheneOS, with Doze, on metered networks, with little free storage | **Not tested** | Code only |

The live tests depend on what the sources return on the day and on their availability.

## Known limitations

* No device test has been done for downloading and playback; see above.
* The declared licence is trusted as declared. See [Licence audit](#licence-audit).
* The Commons search cannot filter by running time, so about half of each result page is
  discarded as too short, and the Commons part of the library grows slowly.
* The same film can appear more than once when uploads differ in title or year. Exact
  duplicates (same title and year) are merged, keeping the most downloaded one.
* Internet Archive films often exist only in 480p or lower.
* A download that needs more than about six hours of transfer time in a day can be paused by
  Android 15's limit for data-sync services; resume it from the film details.
* Subtitles, casting, background and popup playback are not supported in the film player.
* Film downloads are not part of NewPipe's download list or of its export/import.
* Texts of the section are English only.
