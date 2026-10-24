#!/usr/bin/env bash
# Ticket 08: CI-less release recipe (spec R1-7 sideload-first).
#
# Prerequisites (human steps, one-time):
#   1. Generate a release keystore, e.g.
#        keytool -genkeypair -v -keystore mindverse-release.jks -keyalg RSA \
#          -keysize 4096 -validity 10000 -alias mindverse
#   2. Create `keystore.properties` at the repo root (gitignored):
#        storeFile=mindverse-release.jks
#        storePassword=<password>
#        keyAlias=mindverse
#        keyPassword=<password>
#   Without keystore.properties the release APKs build unsigned (still installable via
#   `adb install -t` on a dev device, but NOT publishable).
#
# Publish (human step) — conventions the in-app updater parses (see ReleaseUpdateChecker):
#   - GitHub Release tag:  v<versionName>   (e.g. v0.4.0)
#   - Release body:        includes a line `versionCode: <N>`
#   - Assets:              the two APKs from dist/ (names carry mindverse-<N>-<flavor>.apk)
#
# Crash reporting: none by design (privacy posture); diagnostics come from user-provided
# logcat until the M3 local crash-log screen.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION_CODE="$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)"
echo "Building mindverse versionCode=$VERSION_CODE (play + fdroid, release)"

./gradlew :app:lintPlayRelease :app:lintFdroidRelease \
          :app:assemblePlayRelease :app:assembleFdroidRelease

mkdir -p dist
for flavor in play fdroid; do
  src="app/build/outputs/apk/$flavor/release/app-$flavor-release.apk"
  dst="dist/mindverse-$VERSION_CODE-$flavor.apk"
  cp "$src" "$dst"
  echo "  $dst"
done
echo "Done. Upload the two dist/ APKs as GitHub Release assets."
