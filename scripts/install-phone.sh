#!/usr/bin/env bash
# Build the phone app and install it on the Pixel over adb, when what the app is built from
# changed since the last install here (android/, the core, the ffi, Cargo.lock). Run by dibs's
# ship recipe for Tether (the user's call, 2026-10-04: approving a Tether update also installs
# the app), so a failure here leaves the ship resumable and becomes a "try again?" question.
#   scripts/install-phone.sh [--force]
set -euo pipefail
cd "$(dirname "$0")/.."
state=${XDG_STATE_HOME:-$HOME/.local/state}/tether
mark=$state/phone-installed
mkdir -p "$state"
paths=(android crates/core crates/ffi Cargo.lock)
head=$(git rev-parse HEAD)
if [[ ${1:-} != --force ]] && last=$(cat "$mark" 2>/dev/null) && git cat-file -e "$last^{commit}" 2>/dev/null &&
    git diff --quiet "$last" "$head" -- "${paths[@]}"; then
    echo "phone app: nothing it is built from changed since ${last:0:9}; not reinstalled"
    exit 0
fi

adb=$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")
[[ -x $adb ]] || { echo "phone app: adb not found" >&2; exit 1; }
# The phone dibs would pick: ANDROID_SERIAL, else a USB device, else one over the network, else
# connect to its usual address (~/.config/system-notes.md).
pick() { "$adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }' | sort -t: -k2 | head -n1; }
serial=${ANDROID_SERIAL:-$(pick)}
if [[ -z $serial ]]; then
    timeout 10 "$adb" connect 192.168.1.245:5555 >/dev/null 2>&1 || true
    serial=$(pick)
fi
[[ -n $serial ]] || { echo "phone app: the phone isn't reachable over adb (asleep, or off the home Wi-Fi); wake it and try again" >&2; exit 1; }

(cd android && ./gradlew --quiet :app:assembleRelease)
apk=android/app/build/outputs/apk/release/app-release.apk
[[ -f $apk ]] || { echo "phone app: no APK at $apk" >&2; exit 1; }
timeout 120 "$adb" -s "$serial" install -r "$apk"
echo "$head" > "$mark"
echo "phone app: installed $(git describe --always "$head") on $serial"
