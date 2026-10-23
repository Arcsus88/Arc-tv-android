# Releases and self-updating devices

Every push to `main` (other than docs-only changes) runs
`.github/workflows/release.yml`: the unit tests, then signed APKs, published as
a GitHub Release tagged `v1.2.<run number>`. Each release's version code is
higher than the last, so it installs over it.

Devices update themselves from those releases: the app checks this repo about
once an hour, downloads the APK for the device's CPU, waits until no film is
playing, and installs it. When the app is the device owner (below), nobody has
to tap anything and the app comes straight back up afterwards. Otherwise
Android asks for a tap on Install, and only if "Install unknown apps" is
allowed for Arc Disney.

## One-time setup on GitHub

Repository → Settings → Secrets and variables → Actions → New repository secret:

| Secret             | Value |
|--------------------|-------|
| `SIGNING_PASSWORD` | The signing key's password (handed over with the key; see below). |
| `UPDATE_TOKEN`     | A fine-grained token that can read this repo, made as below. |
| `TMDB_API_KEY`     | Optional: a TMDB key, so the update button finds new films without asking. |

**The update token.** This repo is private, so devices need a token to see its
releases. It's built into each release APK, so make it able to do nothing but
read this one repo: GitHub → your picture → Settings → Developer settings →
Personal access tokens → Fine-grained tokens → Generate new token.

- Repository access: Only select repositories → `Arc-tv-android`.
- Permissions → Repository permissions → **Contents: Read-only** (Metadata:
  Read-only is added by itself). Nothing else.
- Expiration: the longest offered, or none if offered.

A token that expires leaves devices stranded on their last version, because the
fix (a build with a new token) is what they can no longer download. Before it
expires, make a new token, replace the `UPDATE_TOKEN` secret and push anything:
devices take that release with the old token and carry on with the new one.

Then Actions → Release → Run workflow (or push to `main`).

**Before the secrets are added**, each push publishes a *preview* instead: a
prerelease tagged `preview` (replaced every time) whose APKs are signed with a
throwaway key and carry no update token. It's there to look at and try; it
never updates itself, the updater ignores it, and it can't be updated into a
real release, so uninstall it before installing one.

## The signing key

Android installs an update only if it's signed with the same key as the app
already on the device. The key lives in the repo encrypted
(`signing/release.keystore.enc`, AES-256); `SIGNING_PASSWORD` unlocks it in the
workflow, and is also the keystore and key password (alias `arcdisney`).
Keep the password somewhere safe: without it no further update can install on
any device.

To unlock it by hand (for a local signed build):

```sh
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in signing/release.keystore.enc -out release.keystore
KEYSTORE_FILE=release.keystore KEYSTORE_PASSWORD=… KEY_ALIAS=arcdisney KEY_PASSWORD=… ./gradlew :app:assembleRelease
```

Builds signed any other way (debug builds, or the earlier test APKs) can't
update to a release, or the other way round; such a device needs the app
uninstalled first.

## Setting up a device (device owner)

Device owner is what lets the app update silently and lock the device to
itself: it becomes the home screen, pins itself so there's no Home, Recents or
notification shade to leave by (the power menu still works), and grants itself
the location access Android 9 needs to list Wi-Fi networks. It can only be set
on a device with no accounts on it, so start from a reset:

1. Factory-reset the device. In the setup wizard skip adding a Google account
   (Wi-Fi can be joined now or later in the app).
2. Settings → About → tap Build number seven times; then Developer options →
   USB debugging on. Connect it to a computer with `adb`.
3. Download `arc-disney-<version>-universal.apk` from the latest release and run:

   ```sh
   adb install arc-disney-1.2.1-universal.apk
   adb shell dpm set-device-owner com.arcsus.arcdisney/.admin.AdminReceiver
   adb shell am start -n com.arcsus.arcdisney/.MainActivity
   ```

4. The app opens, locks the device and, if it isn't online yet, shows Connect
   to Wi-Fi. Developer options can be turned off before step 4 if nobody needs
   adb again (once it's locked, Settings can't be reached).

From then on it boots straight into Arc Disney and keeps itself up to date.

**Undoing it:** the device owner can only be removed by the app itself or by a
factory reset from recovery mode (the hardware-button menu), so keep that in
mind before handing the device over. `adb install -r` of a release APK still
works for a manual update while USB debugging is on.

## Notes

- Private repos get a monthly allowance of free Actions minutes; a release run
  takes roughly ten.
- A release whose APKs fail to install (for example, signed differently) is
  retried every hour; fix it by publishing a newer, correctly signed release.
