# Changelog

> Japanese version: [CHANGELOG.md](CHANGELOG.md)

## [1.0.5] - 2026-08-01

### Major Changes

- **Every screen rebuilt on Manta UI**
  The Playback Device and Memory Device screens are now rendered through the BelugaExperience design workflow on the Manta UI runtime. The old GUI textures have been removed.
- **Manta is bundled in the jar (jar-in-jar)**
  Manta 1.1.12 ships embedded and is declared a required dependency. You do not need to install Manta separately.

### Added

- **♪ Schedule (playlist)**
  An editor for playing several recording media in order from a Playback Device. Up to 6 entries, each repeating 1–10 times. Drag a medium from your inventory onto a row's slot, or shift-click to drop it into the first free row. Reorder with ▲ / ▼, preview a row with its ▶, remove it with ✕ (the medium is handed back). **▶ Play All** runs the sequence; the row currently sounding is outlined and the outline moves along as it advances. Change a play count by hovering it and scrolling.
- **In-game wiki**
  The **📖** button on any screen opens the page for that screen. With hints on, hovering an element and pressing **F1** jumps straight to that feature's page. Five pages, in English and Japanese.
- **Hint mode**
  A toggle in the top-right of each screen. With it on, hovering an element shows its description in the lower left.
- **Cover art (jacket) display**
  Artwork embedded in an audio file is shown on the Memory Device and Playback Device screens. JPEG jackets decode through Manta's image decoder.
- **Access mode (public / private)**
  The first player to open a device becomes its owner. The face icon switches between public (green) and private (red); while private, nobody but the owner can open it.
- **Range Board HUD**
  Holding the board shows a HUD at the bottom of the screen. **Alt + wheel** cycles the mode; **Ctrl or Shift + wheel** adjusts the current mode's value (outside Normal range mode). The wheel has a 180 ms cooldown so one flick does not fire repeatedly.
- **Recording error feedback**
  Write failures on the Memory Device are now surfaced on screen.

### Bug Fixes

- **Fixed world assets from 1.0.3 or earlier not being recognized**
  The 1.0.4 rename changed only the namespace, so blocks in chunks, items in inventories, block entity types, menus, and the data components carrying the audio id all still named a mod that no longer existed. Registry aliases now map the legacy id (`stationsoundsystem`) onto the current one, and `AudioStorage` falls back to the old directory (`<world>/stationsoundsystem_audio/`). **Recording media, playback devices, and range boards from 1.0.3 or earlier work as-is** — the 1.0.4 note about needing a fresh world no longer applies.

### Improvements

- Added a playback session registry and scheduler, centralising playback start / stop / completion notification.
- Added playback timeout handling.
- Hardened input validation on playlist command packets.
- General hardening across audio storage, networking, and audio processing.
- Replaced control glyphs with Manta icons.
- Added a unit test suite (52 tests).

## [1.0.4] - 2026-05-05

### Major Change (Mod rename / ID change)

- **Renamed from "Station Sound System" to "Spatial Audio System"**
  Repositioned as a general-purpose spatial audio playback mod rather than a railway-station-specific mod.
  - Mod ID: `stationsoundsystem` → `spatialaudiosystem`
  - Display name: `Station Sound System` → `Spatial Audio System`
  - Java root package: `com.example.stationsoundsystem` → `com.spatialaudiosystem`
  - Main class: `StationSoundSystem` → `SpatialAudioSystem`
  - Resource locations (`assets/`, `data/`) and the GitHub repository have been renamed accordingly.
  - **Recording media, playback devices, and range boards from 1.0.3 or earlier are not recognized in worlds upgraded to 1.0.4. Either start a new world or replace the affected items / blocks.**

### Public API for Integration (for addon mods)

- **New package `belugalab.sas.api`** added as a stable API surface that will not be repackaged across versions. Internal package `com.spatialaudiosystem.*` is subject to repackaging, so addon mods must always reference the `belugalab.sas.api` package.
- **`belugalab.sas.api.SasApi`** — facade class
  - `isInstalled` — whether SAS is loaded
  - `isRecordingMedium`, `isRangeBoard` — item type checks
  - `hasAudio`, `getAudioFileName`, `getAudioFormat`, `getAudioId` — recording medium metadata
  - `hasRange`, `getRangePos1`, `getRangePos2`, `getAttenuationRanges` — range board metadata
  - `loadAudio`, `loadAudioFromMedium` — server-side audio binary loading
  - `playAudio` — server-side broadcast playback
  - `stopAudio` — stop playback at a position
- **`belugalab.sas.api.PlaybackEndedEvent`** (NeoForge event)
  - Fired when playback finishes. Mods implementing chained / sequential playback can hook this without polling.
  - The handler in `PlaybackControlPayload` also posts this event on stop notifications, so it covers `playAudio` calls that bypass `PlaybackDeviceBlockEntity` (e.g. addon-driven dynamic playback).

### Bug Fixes

- **Fixed audio continuing to play on the title screen after leaving a world**
  Added a new `ClientLifecycleHandler` that subscribes to `ClientPlayerNetworkEvent.LoggingOut` and `LevelEvent.Unload`, calling `AudioManager.stopAll()` on either event. In-flight `ClientAudioChunkPayload` chunk sessions are also cleared, so leftover sessions do not bleed into a subsequent login.

## [1.0.3] - 2026-03-23

### Major Changes

- **Implemented chunked audio data transfer**
  Both upload (Client→Server) and playback broadcast (Server→Client) now split audio data into 500 KB chunks. This avoids NeoForge's per-packet size limit (about 1 MB), so large audio files no longer kick the client. Maximum file size raised to 10 MB.

### Improvements

- **Chunk transfer session timeout**
  Incomplete upload / download sessions are auto-discarded after 30 seconds, preventing memory leaks on disconnect.

- **`ClientNotifyPayload` made optional**
  Connection is no longer rejected when the server and client mod versions differ.

- **Lite-inventory construction is now automated**
  `getUpdateTag()`'s component copy switched from a manual list to `copy() + remove(AUDIO_DATA)`, so newly added components no longer get accidentally omitted.

- **Range board notification messages moved to HUD**
  Coordinate set / clear messages are shown below the item name instead of the action bar, eliminating overlap with the panel UI.

### Bug Fixes

- **Fixed the kick when writing large audio files**
  Sending a single audio file larger than the per-packet size limit kicked the client; this has been fixed.

- **Fixed all players being kicked when playing back large audio files**
  The same packet size overflow occurred during server-to-client playback broadcast and has been fixed.

## [1.0.2] - 2026-03-22

### Major Changes

- **Audio data storage migrated to a file-based scheme**
  Audio data is now decoupled from `ItemStack` data components and stored as world data on the server side, in `<world>/stationsoundsystem_audio/<uuid>.audio`. Each `ItemStack` only carries a UUID reference (`AUDIO_ID`), fundamentally resolving data corruption that previously occurred during network sync or creative-mode operations. Recording media from older versions are auto-migrated on first playback.

### Improvements

- **Mod icon added**
  The playback device icon now appears in the mod list.

- **Range board panel localized to Japanese**
  Mode names changed to "通常範囲指定" / "減衰率設定" / "下向き設定". Direction names and descriptions are also localized. Long mode names are now scaled to fit within the panel.

- **Long-range selection on range boards**
  Blocks up to 64 blocks away can now be selected via right-click, even outside arm reach.

- **Range board follow-preview animation**
  When only Pos1 is set, Pos2 now smoothly follows the player's cursor as a preview. Both points unset also shows a 1-block preview box at the cursor.

### Bug Fixes

- **Audio data corruption on version upgrade fundamentally fixed**
  By migrating audio data to file-based storage, the path no longer goes through `ItemStack` network sync, so corruption from creative-mode or container operations no longer occurs.

- **Fixed the bug where audio could play out of range when joining a world**
  Gain updates are now performed per frame from the render thread.

## [1.0.1] - 2026-03-12

### Bug Fixes

- **Fixed attenuation mode being non-functional**
  The background audio thread accessed `ClientLevel`'s player list in a non-thread-safe way, causing `ConcurrentModificationException` to be silently caught internally so the gain stayed at 1.0 (full volume) at all times. Player coordinates are now cached from the main thread per frame, and the audio thread reads from that cache.

- **Improved attenuation gain update timing (fixes the brief full-volume burst when stepping out of the orange box)**
  Previously the audio thread only updated gain every ~260 ms, so leaving the range region briefly played at full volume. Gain updates now happen on the render thread (~60 fps) for a much faster response.

- **Fixed attenuation not applying to other players' playback**
  Thanks to the gain timing fix above, attenuation now correctly applies to playbacks initiated from a recording medium written by another player.

- **Fixed audio not playing on redstone input**
  During the multiplayer refactor, the packet-send path inside `startPlayback()` was lost. The rising edge now correctly broadcasts the playback packet to all clients.

- **Fixed audio data corruption on recording media**
  A creative-mode item-slot sync packet could overwrite real server-side data with a client-side placeholder; this has been fixed. A validation step now skips playback if invalid data is detected.

- **Fixed other clients' audio not stopping when the playback device is destroyed**
  When a playback device is destroyed, `ClientStopAudioPayload` is now broadcast to all players.
