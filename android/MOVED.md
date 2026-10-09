# This copy of the phone app is frozen

The phone app moved to the dibs repo on 2026-10-09 (`~/Projects/dibs/android`). This `android/` is frozen at the commit that added this file: nothing installs from it, and `scripts/install-phone.sh` here refuses to run. Make app edits in a dibs worktree (same `android/` paths) and ship them there; the dibs ship builds and installs the app.
