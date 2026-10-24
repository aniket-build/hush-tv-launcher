#!/bin/bash
# Installs Hush on an Android TV over Wi-Fi ADB and makes the Home button open it.
# Usage: scripts/enable-as-home.sh <tv-ip> [path/to/hush.apk]
# Google's launcher is NOT disabled; it stays as the fallback. Undo: scripts/disable-home.sh <tv-ip>
set -euo pipefail
IP="${1:?usage: $0 <tv-ip> [hush.apk]}"; APK="${2:-$(dirname "$0")/../build/hush.apk}"
TV="$IP:5555"; PKG=io.github.aniketrai.hush; SVC="$PKG/$PKG.HomeRedirectService"
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }

echo "0/4 Check this is the genuine Hush APK"
SUM=$( (shasum -a 256 "$APK" 2>/dev/null || sha256sum "$APK") | awk '{print $1}')
echo "    SHA-256: $SUM"
echo "    Compare it with the SHA-256 listed on the GitHub Release you downloaded it from."
if [ -n "${HUSH_SHA256:-}" ]; then
    [ "$SUM" = "$HUSH_SHA256" ] || { echo "    ✗ Does NOT match HUSH_SHA256. Not installing." >&2; exit 1; }
    echo "    ✓ Matches HUSH_SHA256"
else
    read -r -p "    Does it match? [y/N] " ok; [ "$ok" = y ] || [ "$ok" = Y ] || { echo "Stopped. Nothing was installed."; exit 1; }
fi

adb connect "$TV" >/dev/null
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach $TV. Is ADB debugging on, and did you allow this computer on the TV?" >&2; exit 1; }

echo "1/4 Installing Hush"
adb -s "$TV" install -r "$APK" | tail -1

echo "2/4 Usage access (lets 'Recently played' count apps opened from remote buttons)"
adb -s "$TV" shell cmd appops set "$PKG" GET_USAGE_STATS allow

echo "3/4 Home button redirect (adds Hush to any accessibility services you already use)"
cur=$(adb -s "$TV" shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$cur" in *"$SVC"*) new="$cur" ;; ""|null) new="$SVC" ;; *) new="$cur:$SVC" ;; esac
adb -s "$TV" shell settings put secure enabled_accessibility_services "$new"
adb -s "$TV" shell settings put secure accessibility_enabled 1

echo "Done. Press Home on your remote. Press Home twice quickly for the Google home screen."

echo "4/4 Close the door: ADB debugging lets any computer on your Wi-Fi ask to control the TV."
read -r -p "    Turn ADB debugging off now? (recommended) [Y/n] " off
if [ "$off" != n ] && [ "$off" != N ]; then
    adb -s "$TV" shell settings put global adb_enabled 0 || true
    adb disconnect "$TV" >/dev/null 2>&1 || true
    echo "    ADB debugging is off. Hush keeps working."
else
    echo "    Left on. Turn it off later: Settings > Device Preferences > Developer options > ADB debugging."
fi
