# Arc Disney

An Android phone app for the Disney catalogue from Arc TV, with everything on the
phone: no backend, and no shared debrid account. It opens on a Disney splash that
fades into a wall of posters, with nothing above them but a search bar, a small
Disney logo and an update button. Tapping a poster plays the film in VLC, which is built into the
app, so playback never leaves it: from the device's own AllDebrid account when
it's signed in to one and AllDebrid has the film (full speed), otherwise by
torrent, streamed on the phone.

It's made for a locked Android 9 device: the user never needs the Settings app,
so the app gets the device onto Wi-Fi itself (see [Wi-Fi](#wi-fi)). It runs on
Android 8.0 and later.

## How it works

**The catalogue** is every Disney-branded and Pixar film from TMDB, each with its
best releases from The Pirate Bay and Knaben. "Disney-branded" means the studios
TMDB credits: Walt Disney Pictures, Productions, Animation Studios and Feature
Animation (TMDB's home for The Little Mermaid to Brother Bear), Pixar,
DisneyToon (the sequels, Tinker Bell, Planes), Disney Television Animation,
Walt Disney Television, Disney Channel, Disney Branded Television and
Disneynature, at 40 minutes or longer. Not Touchstone, Marvel, Lucasfilm or
20th Century, except that the snapshot adds The Nightmare Before Christmas, Who
Framed Roger Rabbit, and the Disney Channel Original Movies TMDB files under
other studios (High School Musical, Halloweentown, Zombies, Descendants 3…).
A snapshot ships in the APK (`app/src/main/assets/disney.json`), so a
fresh install has all of it without searching anything. The wall shows only
films with a seeded release; search matches title and year as you type,
ignoring accents and punctuation ("wall e" finds WALL·E).

**The update button** does on the phone what the `arc-disney` Edge Function's
`sync` and `scan` did on the server:

- With a TMDB key it asks TMDB for Disney films the phone doesn't have yet.
- It searches The Pirate Bay's JSON API (by IMDb id, then title + year) for
  films never searched, then Knaben's (`data/Knaben.kt`, title + year) when
  that found fewer than three seeded. Knaben searches many sites' uploads, YTS
  and The Pirate Bay among them, so it finds films apibay doesn't have and
  keeps working when apibay is down. Films that came up empty are re-checked
  after 3 days for this year's and last year's films (new films often have
  nothing until their digital release), after 30 days for older ones. Films
  that already have seeded releases keep them.
- Releases are filtered as the backend filtered them (cams, packs, extras and
  TV-shaped names out), then ranked for streaming on a phone: seeded first,
  1080p preferred, large files (remuxes, 4K over 8 GB) and dubbed/MULTi
  releases pushed down, seeders as a tiebreak. The top 8 are kept.
- A release found by name must be named for the film (`namedAs`): the title
  first (after a `[site]` tag or "Disney's"), not followed by a sequel number,
  and the year (±1) after it; with a one-word title only a genre may come in
  between. So "Aladdin (1992) 1080p" is Aladdin, but "Tiger.3.2023" isn't Tiger
  (2024), "Under.Wraps.2.2022" isn't Under Wraps (2021) and "The.Elephant.Queen.2019"
  isn't Elephant (2020). IMDb-tagged uploads only need the title or the year.
- If neither site can be reached (The Pirate Bay is blocked by most UK ISPs),
  the update stops early and says so instead of marking films as having no
  seeds.

There's no TMDB key in the source. Builds take one from `local.properties`
(`tmdb.apiKey=…`, gitignored) or the `TMDB_API_KEY` environment variable. Without
one, the first press of the update button asks for a key (long-press it later
to change it); "Just re-check" skips TMDB and only searches releases.

**AllDebrid** (`debrid/`, optional, per device): each device can sign in to
its own account, once on first run (after Wi-Fi, skippable) or later from the
update button's long-press menu. The sign-in is AllDebrid's PIN flow: the
screen shows a code and a QR code to enter at alldebrid.com/pin from any phone
or computer, so nothing is typed on the device, and the API key it gets is
kept on that device only (Sign out removes it). Nothing debrid goes into the
APK, so the same app can be handed to anyone.

On a signed-in device, Play first asks AllDebrid for the film's releases (up to
five, seeded or not: its cache doesn't need seeders). The first one it already
has plays straight from AllDebrid's servers; ones it doesn't have are removed
from the account again. If it has none of them, or the account has a problem
(expired, not premium, a new location to confirm by email), the launch screen
says so and the film streams by torrent as below. If VLC can't play
AllDebrid's link, the torrent takes over too. The requests come from the
device itself, as AllDebrid only serves links to the IP that asked for them.

**Playing a film by torrent** (`torrent/`):

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
that's how copyright letters from ISPs come about. Playing from AllDebrid
avoids this, as only AllDebrid's servers touch the torrent. Don't share one
AllDebrid account between devices on different connections: AllDebrid treats
use from several places at once as account sharing.

## Wi-Fi

On first run, after the splash, a device that isn't online gets a **Connect to
Wi-Fi** screen: nearby networks with their signal, a padlock on the ones that
need a password, and "Other network…" for one that hides its name. Tap a
network, type the password, Connect. A wrong password says so; Continue unlocks
once the device reaches the internet (Skip for now gets past it offline). A
device already online at first run never sees the screen.

Afterwards there's no Wi-Fi button in the header. Long-press the update button
for a small menu (Wi-Fi, AllDebrid account, TMDB key), and when the device drops offline a
"No internet · Wi-Fi settings" pill appears over the posters.

How it joins (`wifi/WifiController.kt`): Android 9 lets an app add a saved
network (`WifiManager.addNetwork`) and join it (`enableNetwork`), so this is
the same as joining from Settings, and the device rejoins by itself after a
restart. WPA/WPA2 Personal, WEP and open networks are supported; WPA3-only and
work (802.1X) networks are shown but can't be joined. Android 10 and later only
allow this to a device-owner app, so there the screen offers the system's Wi-Fi
panel instead, unless the app is the device owner.

Android 9 only lists nearby networks to an app with location permission, and
only while location is on. The screen asks for the permission as it opens. If
location is off, it says so and opens "Other network…" so the name can be
typed in.

## Updates and kiosk mode

Every push to `main` publishes a signed GitHub Release, and devices install it
by themselves within the hour, never in the middle of a film. Set up as the
device owner, the app also locks the device to itself (it's the home screen,
pinned, with only the power menu left) and updates without anyone tapping
anything. [RELEASING.md](RELEASING.md) has the one-time GitHub setup (two
secrets), the signing key, and how to set up a device.

## Building

Needs JDK 17+ and the Android SDK (platform 36).

```sh
./gradlew :app:assembleRelease
```

`app/build/outputs/apk/release/` has an APK per CPU type and
`app-universal-release.apk` (about 65 MB) with both ARM builds. Use the
universal one when unsure: plenty of Android 9 devices run a 32-bit system on a
64-bit chip, which only the 32-bit (`armeabi-v7a`) build runs on. On a device
with adb, `adb shell getprop ro.product.cpu.abi` says which.

Local release builds without `keystore.properties` (or the `KEYSTORE_FILE` /
`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` environment variables) are
signed with the debug key so they still install, but they can't update to or
from a published release. They don't check for updates either: that needs
`UPDATE_TOKEN`, which only the release workflow provides.

## Tests

```sh
./gradlew :app:testDebugUnitTest
```

- `data/`: release filtering, naming and ranking, the updater against a mock
  Pirate Bay, Knaben and TMDB (new films, kept releases, retry timing, a
  blocked site, Knaben filling in and standing in), and the catalogue store's
  snapshot merging.
- `torrent/`: the HTTP server's range handling; the real engine streaming a
  magnet from a libtorrent seeder on loopback, byte for byte with a seek; and,
  where ffmpeg is installed, a real H.264/AAC MP4 streamed by torrent and
  decoded by ffmpeg over the app's address, seeking past what has downloaded.
  ffmpeg's HTTP input reads by range requests the way VLC's does. These run
  libtorrent's Linux build, taken from the `libtorrent4j-linux` jar.
- `wifi/`: the network list (one row per name, signal, security from scan
  capabilities, password quoting), and joining on Robolectric's Android 9:
  the saved configuration, turning Wi-Fi on, a new password replacing the
  old entry, and networks it refuses.
- `debrid/`: the AllDebrid PIN sign-in, account details, a cached release
  resolving to its largest file, an uncached one removed again, and account
  errors keeping AllDebrid's reason.
- `playback/`: the order a film comes in: a release AllDebrid has plays from
  it without libtorrent starting; none cached, signed out, or a bad key fall
  back to the torrent (with the reason shown).
- `update/`: picking the newest release and this CPU's APK, reading releases
  with the token, and downloading through GitHub's redirect without the token
  following it.
- `admin/KioskTest`: kiosk mode as device owner on Robolectric's Android 9 (lock
  task, home screen, location granted), an ordinary install left alone, and an
  update that downloads during a film but installs only once it ends.
- `ui/ScreensTest`: the splash, wall, launch, player and Wi-Fi screens rendered on
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
tab-separated list (TMDB id, year, IMDb id, poster path, popularity, title), and
`-DsnapshotRetry=true` searches again now for films that came up empty.
The first snapshot was seeded from the `arc_disney_titles` table: 684 films,
419 of them playable. That table only had TMDB's first four studios, so the
second added 466 films from the rest: the studios' film lists on TMDB's website
(no API key needed), Disney Channel films and IMDb ids from Wikidata, each
40 minutes or longer and already out. Searched on The Pirate Bay and Knaben,
with the films that had come up empty searched again, that made 1,150 films,
683 of them playable. A phone that already has the app merges a newer snapshot
in when the app is updated, without losing what its own updates found.

## Licences

libVLC is LGPL 2.1 (it's linked dynamically, from the `libvlc-all` AAR).
libtorrent is BSD-licensed and libtorrent4j MIT. Film data and posters come from
TMDB.
