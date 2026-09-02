# Changelog

> Japanese version: [CHANGELOG.md](CHANGELOG.md)

## [1.1.0] - 2026-09-02

### Changed

- **Manta 2.0.0** (embedded Manta updated to 2.0.0, required range `[2.0.0,3)`).
  Update the other BelugaLab mods (TSU 1.1.0 / ASC 1.2.0) at the same time: a build that embeds Manta 1.x requires `[1.1.74,2)`, and the two required ranges cannot both be satisfied by one Manta.

### Added

- **Every sound reaches a player who arrives late, from the sound's current position.**
  One-shots are now delivered on joining, on arriving from another dimension, and on walking into range, just like ∞ sounds.
  The listener starts at the position the sound has reached (their own download time is accounted for).
  Listeners who were present from the start are behind that position by their own initial download, so a late arrival can be ahead of them by that much.
  A player who arrives within range after the sound has ended hears nothing, and the sound is retired.
- **∞ button for normal playback** (right of play/stop): the medium in the single slot repeats until stopped.
  Toggling it while playing takes effect on the sound that is playing: OFF lets each listener finish the current pass, ON restarts it as endless.
- The schedule ON/OFF toggle moved into the schedule screen's button row (the main-screen toggle is gone).
- The range-board slot, attenuation, range display and schedule button now share one row.

### Fixed

- The single slot's ∞ playback stopped after 10 minutes (it reached the runaway safety timeout).
- A redstone pulse into an empty slot silently disarmed a running schedule's ∞ entry.
- Removing the medium during ∞ playback left the sound running.
- The range board tooltip and the upload failure messages were English-only.

### Internal

- Network version 1.5 (`client_play_audio` carries a start offset and a synchronised flag; `client_set_loop` and `catchup_report` added). Older clients are refused at login.
- The server log `SAS-CatchUp` records the start position each client actually applied.

## [1.0.6] - 2026-08-29

### Added

- **Endless playback (∞)**
  Scroll a schedule entry's play count one step past 10 and it becomes **∞**, which plays until
  you stop it — for continuous ambience such as fluorescent hum, ventilation or machinery, held
  for as long as the server is up. The loop runs on each client, on audio it already holds, so
  **repeating costs no further network traffic** (the file transfers once per listener). The loop
  is restored automatically after a server restart or a chunk reload. An entry set to ∞ is the
  last thing that device plays: the schedule does not advance past it.
- **Players who arrive later now hear an endless sound that is already playing**
  Joining a server, arriving from another dimension, or simply walking into range all start an
  ∞ sound for you, **provided you are somewhere it can be heard**. Players outside the audible
  region are not sent it, so nothing is transferred for a sound nobody could hear.

  (In 1.0.6 this applied to ∞ sounds only. 1.1.0 extends it to one-shots, started from the sound's current position — see below.)
- **`SasApi.playAudio(..., boolean loop)`** added to the public API. The existing five-argument
  form is unchanged and behaves as `loop = false`.

### Bug Fixes

- **Fixed players who joined during playback hearing nothing**
  Audio was sent only to the players who were online at the instant a sound started, and there
  was no path that sent it to anyone afterwards. Players who joined, or who arrived from another
  dimension, received neither the metadata nor the audio, and the failure was silence rather
  than an error, so it was invisible from every other client.
- **Fixed a schedule stopping permanently on an empty server**
  The only thing that advanced a sequence was the end-of-playback report, which is sent by a
  client. With nobody online no report ever arrived and the sequence stayed where it was. The
  block entity's own timeout reset the device without telling the sequence, so the one path that
  could have recovered it did not. Endless playback does not depend on that round trip at all.

### Internal

- The client's volume calculation and the server's range check are now one implementation
  (`SpatialGain`). Two copies would drift into either shipping audio to someone who hears
  silence, or leaving a player inside an audible box with nothing playing — neither of which
  raises an error, so the disagreement is made structurally impossible instead.
- A playback session now records what is playing where and who has not yet been sent it.
- Added `SpatialGainTest`, `PlaybackLoopTest` and delivery coverage in
  `PlaybackSessionRegistryTest`, with mutation controls in `scripts/playback.mutation.py`.

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
