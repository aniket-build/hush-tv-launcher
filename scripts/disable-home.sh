#!/bin/bash
# Turns off Hush's Home redirect and Usage access. Add --uninstall to remove the app as well.
# Usage: scripts/disable-home.sh <tv-ip> [--uninstall]
set -euo pipefail
IP="${1:?usage: $0 <tv-ip> [--uninstall]}"; TV="$IP:5555"
PKG=io.github.aniketrai.hush; SVC="$PKG/$PKG.HomeRedirectService"
adb connect "$TV" >/dev/null
cur=$(adb -s "$TV" shell settings get secure enabled_accessibility_services | tr -d '\r')
new=$(echo "$cur" | tr ':' '\n' | grep -vx "$SVC" | grep -v '^null$' | paste -sd: - || true)
if [ -z "$new" ]; then
    adb -s "$TV" shell settings delete secure enabled_accessibility_services >/dev/null
    adb -s "$TV" shell settings put secure accessibility_enabled 0
else
    adb -s "$TV" shell settings put secure enabled_accessibility_services "$new"
fi
adb -s "$TV" shell cmd appops set "$PKG" GET_USAGE_STATS default
[ "${2:-}" = "--uninstall" ] && adb -s "$TV" uninstall "$PKG"
echo "Done. Home goes to Google's launcher again."
