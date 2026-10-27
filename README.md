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


## Stage 1: Embedded MIDI/KAR lyrics inspection
This build adds a **Lyrics Check** button to inspect embedded lyric/text meta-events in a selected MIDI/KAR file. It is offline and keeps the existing song-list workflow.

**Important limitation:** this stage does NOT yet synthesize/play MIDI audio, synchronize/highlight words in real time, or play video. Those require a MIDI synthesizer/audio engine and playback timing integration, which must be added and tested as a separate stage.


## Stage 2: SoundFont selection settings foundation
Adds a SoundFont Settings screen where users can select an `.sf2` or `.sf3` file using Android's document picker (including SD card providers), persist the selected file URI, and restore the default selection.

**Important:** This is only the settings/file-selection foundation. The current app still does NOT synthesize or play MIDI audio, and selecting a SoundFont does not yet change audio. A compatible FluidSynth/native integration and an appropriately licensed bundled SoundFont must be added and tested in a later stage before MIDI playback works.
