# PipeHome

PipeHome is a personal fork of [NewPipe](https://github.com/TeamNewPipe/NewPipe). It keeps
everything NewPipe does and adds a personalised home screen, a movies section and a navigation
bar built around four sections: **Home**, **Movies**, **Subscriptions** and **Downloads**.

It is licensed under the GPL v3 or later, like NewPipe. "NewPipe" is a registered word mark of
NewPipe e.V.; this fork therefore uses its own name and application id
(`io.github.jimmysbeat1.pipehome`), which also lets it be installed next to the official app.
It is not affiliated with or endorsed by the NewPipe team or by YouTube. Please report problems
with the fork to this repository, not to NewPipe.

## What it adds

### Home: a "For You" feed computed on the device

The feed is ranked by an engine in `app/src/main/java/org/schabi/newpipe/recommend/engine`
that only uses data already stored on the phone:

| Signal | Source | How it is used |
| --- | --- | --- |
| Watch history | NewPipe's history tables | Terms of the titles and the channels you watch |
| Watch duration | Saved playback positions | A video watched to the end counts about four times as much as one you left right away |
| Recency, rewatching | History dates and counts | Interest halves every 30 days; repeats count more |
| Searches | NewPipe's search history | Counted more strongly than a single watch |
| Subscriptions | Subscription table and local feed | Channel boost, and recent uploads become candidates |
| Feedback | "Not interested", "Don't recommend channel", topic long-press | Hides the item, lowers similar titles, blocks channels and topics |

From these it builds an interest profile, derives **topics** (preferring phrases such as
"mechanical keyboard" over single words, and requiring repeated evidence), and scores each
candidate by topic match, channel affinity, subscription, **freshness** and popularity.
The ranked list is then **diversified**: similar videos are pushed apart, a channel may only
appear twice within twelve items, and every seventh slot goes to something outside your known
interests so that you can discover new topics. Videos you have watched and Shorts (optional) are
filtered out. Each card says why it is shown.

Candidates come from your local subscription feed and from requests made through NewPipe
Extractor: a search for a few of your topics, the videos related to a few you watched, and the
current charts. Results are cached for a week, so the feed also works offline.

### Movies

Free full-length films with genre filters, poster cards, search and a watchlist.

Searching YouTube for "full movie" mostly returns re-uploads by people who do not own the
films. To only show films that are legitimately available, the tab is built from a curated list
of channels run by distributors, studios, archives and broadcasters
(`MovieCatalog.DISTRIBUTORS`). Channels are matched by channel id, never by name. Uploads by
anyone else are hidden; you can opt in to seeing them in search results from the menu, in which
case they are labelled *Unverified*. The list is an editorial judgement, not a legal guarantee,
and availability of individual films varies by country.

### Purchased and rented movies

Films bought or rented on YouTube are DRM protected and tied to a Google account. PipeHome does
not play them and does not try to get around the protection. The *Purchased & rented movies*
card opens your purchases or the store in the official YouTube app if it is installed, and in
your browser otherwise. You can pin titles you own to the Movies tab for quick access; they
always open in the official player. This is the only feature that involves a Google account,
and the sign-in happens in YouTube's own app or site, never in PipeHome.

### Downloads

NewPipe's download manager is unchanged and is additionally available as a main tab. Use it
for content you are authorised to download.

### Design

Dark theme by default, a labelled bottom navigation bar, large rounded video cards, filter
chips and poster grids. The previous behaviour is one setting away: *Settings → Appearance*
(theme, tab position) and *Settings → Content → Content of main page* (any of NewPipe's own
tabs can be added back, and the new ones removed).

## Privacy

* **No account, no telemetry.** The fork adds no analytics, no crash uploads and no Google
  Play Services dependency. NewPipe's crash dialog remains user-initiated, and its e-mail
  option is removed so that reports for the fork are not sent to the NewPipe team.
* **Personalisation data stays on the device.** The profile is recomputed from NewPipe's
  history each time and never stored or sent. Feedback, cached candidates, the movie catalog,
  the watchlist and pinned purchases are JSON files in the app's private storage
  (`files/personalisation/`). They are not part of the NewPipe database, so database
  exports stay compatible with NewPipe.
* **What does leave the device.** To find candidates the app sends the same kind of anonymous
  requests NewPipe sends when you search or open a video: searches for a few of your topics
  (two or three words each), "related videos" lookups for a few videos you watched, the charts,
  and the upload lists of the movie distributors. They carry no account and no identifier, but
  the service can see them and your IP address, exactly as with manual use. Use a VPN or Tor
  if that matters to you.
* **You stay in control.** Clearing the watch or search history in *Settings → History and
  cache* makes the feed forget what it learned from it; disabling watch history stops it
  from learning. *Reset recommendations* in the Home menu deletes feedback and cached
  candidates.
* The update checker only ever runs in official NewPipe builds and is inactive here.

## GrapheneOS

Nothing in the fork depends on Google Play Services or on privileged APIs, and the minimum
and target SDK levels are NewPipe's. Install the APK from the CI artifacts (see below) with the
system installer; allow the network permission when asked. The official-playback hand-off works
with either the YouTube app (e.g. in a profile with sandboxed Google Play) or Vanadium.

## Building

Requirements: JDK 21 and the Android SDK (platform 37.0, build-tools 37.0.0).

```sh
./gradlew assembleDebug           # debug build, id io.github.jimmysbeat1.pipehome.debug
./gradlew assembleContinuous      # optimised build, the one CI publishes
./gradlew testDebugUnitTest       # JVM tests
./gradlew connectedCheck          # on-device tests, needs an emulator or phone

# end-to-end tests against the real service (need network access)
PIPEHOME_LIVE_TESTS=1 ./gradlew :app:testDebugUnitTest --tests "*LiveServiceTest*"
```

### CI builds and signing

`.github/workflows/ci.yml` builds the APK for every push to `dev`, `claude/**` and
`feature/**` and for pull requests, runs lint and the unit tests, runs the on-device tests on
emulators (API 23 and 35), and uploads the APK as the **PipeHome-apk** artifact of the run.

Without further setup the APK is signed with a throwaway debug key. Such builds install fine,
but Android refuses to update an app with an APK signed by a different key, so you would have
to uninstall (and lose data) between builds. To get updatable builds, create your own key once
and store it as repository secrets. No key is, or should ever be, committed to the repository.

```sh
keytool -genkeypair -v -keystore pipehome.jks -alias pipehome \
        -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 pipehome.jks   # value for PIPEHOME_KEYSTORE_BASE64
```

| Secret | Value |
| --- | --- |
| `PIPEHOME_KEYSTORE_BASE64` | the base64 text printed above |
| `PIPEHOME_KEYSTORE_PASSWORD` | the keystore password |
| `PIPEHOME_KEY_ALIAS` | `pipehome` |
| `PIPEHOME_KEY_PASSWORD` | the key password |

Keep `pipehome.jks` and the passwords somewhere safe: losing them means losing the ability to
update installed copies. Locally, the same build is produced by setting
`PIPEHOME_KEYSTORE_FILE` (path to the keystore) and the three other variables.

## Code map

| Path | Content |
| --- | --- |
| `recommend/engine` | Pure Kotlin: tokenizer, interest profile, ranker. No Android dependencies. |
| `recommend/data` | Signals from the database, candidate fetching, JSON store, repository |
| `recommend/ui` | Home tab |
| `movies` | Catalog rules, fetching, repository, Movies tab, official playback hand-off |
| `download/DownloadsTabFragment.kt` | Download manager as a tab |
| `settings/tabs/Tab.java` | The three new tab types (ids 100 to 102) |
| `fragments/MainFragment.java` | Bottom navigation bar |

## Known limitations

* Topics are derived from words in video titles. This works well for specific interests and
  less well for vague ones; there is no understanding of video content.
* "Related videos" lookups load a full video page each and may be refused by the service when
  it suspects automation; the feed then relies on the other sources.
* Poster cards crop the 16:9 video thumbnail; YouTube does not provide real posters for these
  uploads. Genres are inferred from the words distributors put into titles, so some films only
  appear under *All*.
* The watchlist and pinned purchases are not included in NewPipe's export/import.
* The app icon is still NewPipe's.
