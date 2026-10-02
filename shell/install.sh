#!/bin/bash
# Links the chat plugin into the Omarchy shell and turns it on (bar badge on the right, before
# the tray). SUPER+M lives in ~/.config/hypr/bindings.lua.
set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
plugins="$HOME/.config/omarchy/plugins"

mkdir -p "$plugins"
ln -sfn "$root/kivan.tether" "$plugins/kivan.tether"
omarchy-shell -q shell rescanPlugins
# The rescan finishes after the call returns.
for _ in 1 2 3 4 5 6 7 8 9 10; do
  omarchy plugin list 2>/dev/null | grep -q kivan.tether && break
  sleep 0.5
done
omarchy plugin enable kivan.tether --before omarchy.tray
