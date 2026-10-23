# Arc Disney

An Android phone app for the Disney catalogue from Arc TV, with everything on the
phone: no backend and no debrid account. It opens on a Disney splash that fades
into a wall of posters, with nothing above them but a search bar and an update
button. Tapping a poster streams the film by torrent straight into VLC, which is
built into the app, so playback never leaves it.

## How it works

**The catalogue** is every Disney-branded and Pixar feature from TMDB (Walt Disney
Pictures / Productions / Animation Studios and Pixar), each with its best Pirate
Bay releases. A snapshot ships in the APK (`app/src/main/assets/disney.json`), so a
fresh install has all of it without searching anything. The wall shows only
films with a seeded release; search matches title and year as you type,
ignoring accents and punctuation ("wall e" finds WALL·E).

**The update button** does on the phone what the `arc-disney` Edge Function's
`sync` and `scan` did on the server:

- With a TMDB key it asks TMDB for Disney films the phone doesn't have yet.
- It searches The Pirate Bay's JSON API (by IMDb id, then title + year) for
  films never searched, and re-checks films that came up empty: after 3 days
  for this year's and last year's films (new films often have nothing until
  their digital release), after 30 days for older ones. Films that already
  have seeded releases keep them.
- Releases are filtered as the backend filtered them (cams, packs, extras and
  TV-shaped names out; a name search must match the title and year), then
  ranked for streaming on a phone: seeded first, 1080p preferred, large files
  (remuxes, 4K over 8 GB) and dubbed/MULTi releases pushed down, seeders as a
  tiebreak. The top 8 are kept.
- If The Pirate Bay can't be reached (it's blocked by most UK ISPs), the update
  stops early and says so instead of marking films as having no seeds.

There's no TMDB key in the source. Builds take one from `local.properties`
(`tmdb.apiKey=…`, gitignored) or the `TMDB_API_KEY` environment variable. Without
one, the first press of the update button asks for a key (long-press it later
to change it); "Just re-check" skips TMDB and only searches releases.

**Playing a film** (`torrent/`):

- `TorrentEngine` runs libtorrent (via libtorrent4j) on the phone, with DHT,
  local peer discovery and UPnP/NAT-PMP. It adds the release's magnet, waits
  for the file list, picks the largest video file and ignores the rest.
- It fetches the first 8 MB and the last 2 MB first: VLC reads both (the end
  holds the index of most MP4s and MKVs) before it shows a frame. The launch
  screen shows the film's art, the buffering percentage, peers and speed.
- `StreamServer` then gives VLC an address on the phone (`http://127.0.0.1:…`)
  that answers range requests, holding each answer until the torrent has the
  bytes. Every read pushes the next 48 MB ahead of it to the front of the
  download queue, so a seek fetches from the new position first.
- If a release finds no peers in 45 seconds, or stalls before playback starts,
  the next one is tried (up to 4), with no extra tap. "Try another release" on
  the launch screen does the same by hand.
- The film downloads to the app's cache and is deleted when you leave it.
  libtorrent stops a minute after the last film closes.

libtorrent's default storage memory-maps files and installs its own
SIGSEGV/SIGBUS handler; the engine uses its plain read/write storage instead,
which is plenty for one film at a time.

**The player** is libVLC inside the app: full screen and landscape, with
play/pause, ±10 s, a seek bar, fit/fill, and the release's audio and subtitle
tracks. VLC decodes everything a release might carry (HEVC, TrueHD, DTS).
Leaving the app pauses the film.

**Worth knowing:** streaming a torrent means the phone downloads from, and
uploads to, everyone sharing it, and they can see its IP address. In the UK
that's how copyright letters from ISPs come about. The debrid route in Arc TV
avoids this.

## Building

Needs JDK 17+ and the Android SDK (platform 36).

```sh
./gradlew :app:assembleRelease
```

There's one APK per CPU in `app/build/outputs/apk/release/`; phones want
`app-arm64-v8a-release.apk` (about 39 MB). Without `keystore.properties` (or the
`KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` environment
variables), release builds are signed with the debug key so they still install.

## Tests

```sh
./gradlew :app:testDebugUnitTest
```

- `data/`: release filtering and ranking, the updater against a mock Pirate
  Bay and TMDB (new films, kept releases, retry timing, a blocked site), and
  the catalogue store's snapshot merging.
- `torrent/`: the HTTP server's range handling; the real engine streaming a
  magnet from a libtorrent seeder on loopback, byte for byte with a seek; and,
  where ffmpeg is installed, a real H.264/AAC MP4 streamed by torrent and
  decoded by ffmpeg over the app's address, seeking past what has downloaded.
  ffmpeg's HTTP input reads by range requests the way VLC's does. These run
  libtorrent's Linux build, taken from the `libtorrent4j-linux` jar.
- `ui/ScreensTest`: the splash, wall, launch and player screens rendered on
  the JVM with Robolectric and Roborazzi, into `app/build/screens/` with
  `-Proborazzi.test.record=true`. Posters load from `-DposterDir=…` (TMDB
  images saved by file name) when it's given.

## Rebuilding the snapshot

The snapshot is made by running the app's own update on a computer:

```sh
TMDB_API_KEY=… ./gradlew :app:testDebugUnitTest --tests '*GenerateSnapshot*' -DgenerateSnapshot=true --rerun
```

It updates `app/src/main/assets/disney.json` in place. Without `TMDB_API_KEY` it
only searches releases; `-DsnapshotSeed=films.tsv` adds films from a
tab-separated list (TMDB id, year, IMDb id, poster path, popularity, title).
The first snapshot was seeded from the `arc_disney_titles` table: 684 films,
419 of them playable. A phone that already has the app merges a newer snapshot
in when the app is updated, without losing what its own updates found.

## Licences

libVLC is LGPL 2.1 (it's linked dynamically, from the `libvlc-all` AAR).
libtorrent is BSD-licensed and libtorrent4j MIT. Film data and posters come from
TMDB.
