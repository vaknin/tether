#!/usr/bin/env bash
# Build the phone app and install it on the Pixel over adb, when what the app is built from
# changed since the last install here (android/, the core, the ffi, Cargo.lock). Run by dibs's
# ship recipe for Tether (the user's call, 2026-10-04: approving a Tether update also installs
# the app), so a failure here leaves the ship resumable and becomes a "try again?" question.
# Signing: with ~/.config/tether/keystore.properties Gradle signs as before. Without it (the key moved
# to /var/lib/dibs-root/keys, out of every agent's reach; dibs task #145) the APK is built unsigned
# and signed by a root step the user approves on the phone with their fingerprint: the fixed script
# scripts/sign-apk.sh, run by dibs-root with the APK as its file.
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
# The phone dibs would pick: ANDROID_SERIAL, else a USB device, else one over paired Wireless
# debugging, else what `dibs phone connect` finds (it reconnects after a Wi-Fi drop, waking the
# phone through Tether). Never plain adb over TCP (`adb tcpip`, :5555): it is unencrypted.
pick() {
    "$adb" devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ && $1 !~ /:5555$/ { print $1 }' |
        sort -t: -k2 | head -n1
}
phone() {
    serial=${ANDROID_SERIAL:-$(pick)}
    if [[ -z $serial ]] && command -v dibs >/dev/null; then
        serial=$(timeout 120 dibs phone connect 2>/dev/null | sed -n 's/^connected: \([^ ]*\).*/\1/p') || true
    fi
    [[ -n $serial ]] || { echo "phone app: the phone isn't reachable over adb (asleep, or off the home Wi-Fi); wake it and try again" >&2; exit 1; }
}
phone

(cd android && ./gradlew --quiet :app:assembleRelease)
out=android/app/build/outputs/apk/release
# The same file Gradle reads (android/app/build.gradle.kts).
if [[ -f $HOME/.config/tether/keystore.properties ]]; then
    apk=$out/app-release.apk
    [[ -f $apk ]] || { echo "phone app: no APK at $apk" >&2; exit 1; }
else
    # Built unsigned (AGP zipaligns it); a phone-approved root step signs it with the release key.
    unsigned=$out/app-release-unsigned.apk
    [[ -f $unsigned ]] || { echo "phone app: no unsigned APK at $unsigned" >&2; exit 1; }
    command -v dibs >/dev/null || { echo "phone app: dibs isn't installed, so nothing can sign the APK" >&2; exit 1; }
    version=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' android/app/build.gradle.kts)
    echo "phone app: asking you on the phone to sign Tether $version (waits up to 30 min)"
    log=$(mktemp)
    trap 'rm -f "$log"' EXIT
    if ! dibs root request "Sign Tether $version for your phone" --script "$PWD/scripts/sign-apk.sh" \
        --file app.apk="$PWD/$unsigned" --wait 1800 | tee "$log"; then
        echo "phone app: the APK wasn't signed (denied, expired, or the step failed); see above" >&2
        exit 1
    fi
    dir=$(sed -n 's/^out: //p' "$log" | tail -n1)
    apk=$dir/app.apk
    [[ -n $dir && -f $apk ]] || { echo "phone app: the signing step gave no signed APK (looked in ${dir:-nowhere})" >&2; exit 1; }
    # The wait may have been long: find the phone again (it may be back on another port).
    [[ -n ${ANDROID_SERIAL:-} ]] || "$adb" -s "$serial" get-state >/dev/null 2>&1 || phone
fi
timeout 120 "$adb" -s "$serial" install -r "$apk"
echo "$head" > "$mark"
echo "phone app: installed $(git describe --always "$head") on $serial"
