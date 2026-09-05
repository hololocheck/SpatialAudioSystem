---
title: Range Board
id: tools/range-board
tags: [item, tool, audio]
---

# Range Board

The item that decides **where** a sound is audible and **how it fades**. Put a configured board into the [Playback Device](playback-device.md)'s range slot.

[[TOC]]

## How to use it

1. Hold it — a **HUD** appears at the bottom of the screen
2. **Right-click** a block for the first corner, then another block for the second
3. **Shift + right-click** clears the range
4. Put the configured board into the [Playback Device](playback-device.md)'s **range slot**

## Controls

| Input | Action |
|---|---|
| **Alt + wheel** | Switch mode |
| **Ctrl + Shift + wheel** | Adjust the current mode's value |
| **Right-click** | Set a corner |
| **Shift + right-click** | Clear the range |

> [!NOTE]
> **Alt + wheel to cycle modes** is the shared idiom across Manta and TSU tools. The wheel has a short cooldown so one flick does not fire repeatedly.

## Modes

| Mode | Meaning |
|---|---|
| Normal range | Two corners define a box |
| Attenuation | Fade distance, in blocks, per direction |
| Downward | Fade distance below the device |

> [!TIP]
> To see the area you defined, turn on **Show Range** on the [Playback Device](playback-device.md).

## Related

- [Playback Device](playback-device.md)
- [Memory Device](memory-device.md)
