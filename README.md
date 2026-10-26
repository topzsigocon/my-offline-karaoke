# My Offline Karaoke

Android prototype for an offline MIDI/KAR songbook.

## Current prototype features
- Android app with song-number input
- Pick a folder on device/SD card using Android Storage Access Framework
- Recursive scan for `.mid`, `.midi`, and `.kar` files
- Local songbook and title/filename search
- Basic queue UI
- GitHub Actions workflow to build a debug APK

## Important limitations in this first prototype
- MIDI/KAR audio playback is not implemented yet. The current Play button only selects the song.
- Lyrics parsing/synchronization is not implemented yet.
- Song numbers are generated from the alphabetical scan order and are not yet persisted as a manually editable database.
- MP4 background video support is not implemented yet.

These are planned next milestones; do not treat this prototype as a finished karaoke player.

## Build
Push to `main` (or `master`) to trigger GitHub Actions, or run the workflow manually from Actions. Download the `my-offline-karaoke-debug-apk` artifact from a successful run.
