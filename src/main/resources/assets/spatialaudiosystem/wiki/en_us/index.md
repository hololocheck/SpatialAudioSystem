---
title: Spatial Audio System
id: index
tags: [overview, audio]
---

# Spatial Audio System

A mod for playing **your own audio files** inside the world. You write audio onto a recording medium, then play it from a playback device over a range you define — departure melodies, in-building announcements, background music.

[[TOC]]

## Parts

| Part | Role |
|---|---|
| [Memory Device](memory-device.md) | Writes an audio file (mp3 / ogg / wav) from your PC onto a **recording medium** |
| Recording Medium | The item that carries the audio; put it in a playback device to hear it |
| [Playback Device](playback-device.md) | Plays a medium — one at a time, or several in order via the [♪ Schedule](playback-device/schedule.md) |
| [Range Board](tools/range-board.md) | Defines where the sound is audible and how it fades |
| [Sound Handy](tools/sound-handy.md) | Controls your playback devices from anywhere |

## The basic flow

1. Place a **Memory Device**, right-click it, and pick an audio file with **Select File**
2. Put an empty **recording medium** in the input slot and press the write button
3. Move the finished medium into the **Playback Device**'s media slot
4. Set a range with the **Range Board** and put it in the device's range slot
5. Press **▶**. To play several in order, use the [♪ Schedule](playback-device/schedule.md)

> [!NOTE]
> The audio itself is stored on the server and streamed to each client on playback. The medium item carries a reference id, not the file, so copying a medium costs no extra storage.

## Hints and F1

Turn on the **Hint** toggle in the top-right of any screen: hovering an element then shows a description in the lower left. With that on, pressing **F1** opens the wiki page for that feature. The **📖** button opens the page for the screen you are on.

## Access mode (public / private)

The first player to open a device becomes its **owner**. Clicking the face icon switches between **public (green)** and **private (red)**; while private, nobody but the owner can open it.

## Related

- [Memory Device](memory-device.md)
- [Playback Device](playback-device.md)
- [Range Board](tools/range-board.md)
- [Sound Handy](tools/sound-handy.md)
