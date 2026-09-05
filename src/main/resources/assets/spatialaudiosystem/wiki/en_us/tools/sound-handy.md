---
title: Sound Handy
id: tools/sound-handy
tags: [item, tool, audio]
---

# Sound Handy

Controls your [playback devices](playback-device.md) from anywhere. Whoever places a device, or opens it first, owns it; the handy lists the owner's devices only.

[[TOC]]

## In the hand

| Action | What happens |
|---|---|
| Right-click (air, or a block that is not a device) | Opens the handy screen |
| Right-click one of your playback devices | Targets that device |
| Shift + right-click | Clears the target |
| Shift + Wheel | Walks the target through the list |
| Middle button | Plays / stops the target (when it holds a medium) |
| Shift + R | Toggles the range mode (below) |
| Shift + H | Toggles the highlight: the target's outline shows through blocks |

## Mini HUD

While the handy is held, a small panel slides in from the left into the bottom-left corner. Top to bottom: the targeted device (the playback device's icon, its name, n / N), the medium it holds (the medium's icon in its format's colour and the file name; "cannot play" without one), its state (playing / stopped), the range mode (Shift + R; "cannot edit range" without a board), and the highlight (Shift + H) on / off. When Shift + Wheel changes the target, the rows slide inside the panel the way the wheel went. A sound ending or a medium taken out reaches the panel on its own - the server watches the device - so there is no need to re-hold the handy. The panel can be turned off in the screen's Settings.

## Range mode (Shift + R)

Shows the target's range. When the device holds a [range board](tools/range-board.md), the board inside the device is edited with the same controls as a board in the hand.

| Action | What happens |
|---|---|
| Right-click (a block, or the air) | First corner, then the second |
| Shift + right-click | Clears the box |
| Alt + Wheel | View mode (normal / attenuation / downward) |
| Ctrl + Wheel | The facing face's attenuation distance (in an attenuation mode; the board's HUD names this control) |

A device without a board cannot be edited (the HUD says so). In the range mode the range board's own settings HUD appears with the same content, and the mini HUD stays.

## The screen

A small panel in the bottom-right corner (slides up from the bottom). It always opens on the list.

- **List**: your devices - the playback device's icon, name and position, and a dot for the state (green = holds a medium, red = no medium, dark = not loaded). Click a row and that device's page slides in from the left (the arrow slides it out).
- **Device** (from a row): the name (click to edit, Enter saves), position and state, with Play / Stop / Test / Stop test / Open device. The arrow returns to the list.
  - **Test** plays the device's medium for you alone, where you stand (nobody else hears it).
  - **Open device** opens the device's own screen from where you are, while its chunk has reached your client.
- **Settings**: the mini HUD.

A device can also be named from its own screen (click the title box).

## Limits

- Up to 64 devices per owner appear in the list.
- A device whose chunk is not loaded cannot be played, stopped, opened or range-edited.
- "Open device" reaches devices whose chunk has been sent to your client (the screen reads the device from the client's side).
