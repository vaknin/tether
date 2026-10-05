#!/usr/bin/env bash
# Regenerates the dibs app's look from ~/Projects/design (docs/DESIGN.md, "The dibs app"):
# its theme at dibs's hue, the launcher and notification icon (renamed ic_dibs_*, since the
# app's same-named resources would win the merge) and its Lucide icons. Never edit the outputs.
set -euo pipefail
HUE=180
MARK=dibs-mark.svg  # the deadpan face, drawn for dibs (the user's pick, 2026-10-05)
ICONS=(message-circle inbox hammer history paperclip send-horizontal image camera file file-text
  chevron-down chevron-up check x arrow-down smartphone ellipsis-vertical copy eye-off undo-2
  sparkles circle-stop triangle-alert message-square-reply external-link laptop clock)

cd "$(dirname "$0")"
pkg=com.kivan.tether.dibs
src=src/main/java/com/kivan/tether/dibs/ui/theme
res=src/main/res
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

design kotlin --hue "$HUE" --package "$pkg.ui.theme" --r "$pkg" --src "$src" --res "$tmp/res" >/dev/null
mkdir -p "$res/font" "$res/drawable" "$res/mipmap-anydpi"
cp "$tmp"/res/font/*.ttf "$res/font/"
# Theme.Design stays the app's (same name); the activity uses Theme.Dibs (res/values/dibs.xml),
# whose accent follows the hue.
accent=$(design colors --hue "$HUE" | awk '$1 == "accent" {print $2}')
sed -i -E "s|(<color name=\"dibs_accent\">)#[0-9A-Fa-f]{6}|\1$accent|" "$res/values/dibs.xml"

design launcher --hue "$HUE" --mark "$MARK" --res "$tmp/launch" >/dev/null
for f in ic_launcher_background ic_launcher_foreground ic_launcher_monochrome; do
  sed 's/ic_launcher_/ic_dibs_/g' "$tmp/launch/drawable/$f.xml" >"$res/drawable/${f/ic_launcher_/ic_dibs_}.xml"
done
sed 's/ic_launcher_/ic_dibs_/g' "$tmp/launch/drawable/ic_notification.xml" >"$res/drawable/ic_dibs_notification.xml"
for f in ic_launcher ic_launcher_round; do
  sed 's/ic_launcher_/ic_dibs_/g' "$tmp/launch/mipmap-anydpi/$f.xml" >"$res/mipmap-anydpi/${f/ic_launcher/ic_dibs}.xml"
done

rm -f "$res"/drawable/lucide_*.xml
design icons --res "$res" "${ICONS[@]}" >/dev/null
echo "dibs look regenerated: hue $HUE, mark $MARK"
