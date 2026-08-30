<h1 align="center">Spatial Audio System</h1>

<p align="center"><b>A BelugaLab audio mod, built on Manta.</b></p>

---

## English

Spatial Audio System (SAS) plays **your own audio files** inside the world. Write an mp3 / ogg / wav
from your PC onto a recording medium with the Memory Device, play it back from a Playback Device, and
use the Range Board to define where the sound is audible and how it fades — departure melodies,
in-building announcements, background music. Works in multiplayer, and every screen is rendered
through the BelugaExperience design workflow on top of the Manta UI runtime.

### Features

- **Play your own audio** — mp3 / ogg / wav from your PC, up to 10 MB per file
- **3D range control** — the Range Board defines a box where the sound is audible, with per-direction fade-out
- **♪ Schedule** — queue up to 6 media and play them in order, each repeating 1–10 times or **∞** for continuous ambience
- **Cover art** — artwork embedded in the audio file is shown on the device screens
- **Redstone automation** — the Playback Device fires on a rising edge, for stations, cutscenes, and ambient triggers
- **Access modes** — the first player to open a device becomes its owner; switch it between public and private
- **Multiplayer-safe** — audio is stored on the server and streamed to each client, so there is no per-client desync
- **Built-in wiki** — every screen documented in-game, with hover hints and F1 (EN / JA)
- **Public API** — addon mods can trigger playback and hook playback completion

### Requirements

| Dependency | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.220+ |
| **Manta** | 1.1.12 — bundled in the jar, no separate download |

### Open source

SAS is **MIT-licensed** — published at
[github.com/hololocheck/SpatialAudioSystem](https://github.com/hololocheck/SpatialAudioSystem).
Addon mods should build against the stable `belugalab.sas.api` package, which can broadcast playback,
read range and medium metadata, and listen for `PlaybackEndedEvent`. The internal
`com.spatialaudiosystem.*` package is not an API surface and may be repackaged between versions.

---

## 日本語

Spatial Audio System (SAS) は、**自分の音声ファイル**をワールド内で再生する Mod です。PC 内の
mp3 / ogg / wav を記憶装置で記録媒体に書き込み、再生装置で再生し、範囲指定ボードで「どこまで
聞こえるか・どう減衰するか」を決めます。発車メロディ、館内放送、BGM などに使えます。マルチ
プレイ対応。全画面を Manta UI ランタイム上の BelugaExperience デザインワークフローで描画して
います。

### 特徴

- **自分の音声を再生** — PC 内の mp3 / ogg / wav を、1 ファイル最大 10 MB まで
- **3D 範囲制御** — 範囲指定ボードで音の聞こえる箱を定義し、方向ごとにフェードアウト
- **♪ スケジュール** — 最大 6 件の媒体を並べて順番に再生。各エントリ 1〜10 回
- **ジャケット表示** — 音声ファイルに埋め込まれたアートワークをデバイス画面に表示
- **レッドストーン自動化** — 立ち上がり信号で再生。駅放送・カットシーン・環境音トリガに
- **アクセスモード** — 最初に開いたプレイヤーが所有者。公開 / 非公開を切り替え可能
- **マルチプレイ安全** — 音声はサーバー側に保存して各クライアントへ配信するため desync なし
- **内蔵 wiki** — 全画面をゲーム内で解説。ホバーヒントと F1 対応（英/日）
- **公開 API** — addon mod から再生指示・再生完了のフックが可能

### 必要な環境

| 依存 | バージョン |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.220+ |
| **Manta** | 1.1.12 — jar に同梱、別途ダウンロード不要 |

### オープンソース

SAS は **MIT ライセンス**で
[github.com/hololocheck/SpatialAudioSystem](https://github.com/hololocheck/SpatialAudioSystem)
に公開しています。addon mod は安定 API の `belugalab.sas.api` パッケージを参照してください
（再生指示、範囲・媒体メタデータの取得、`PlaybackEndedEvent` の購読が可能）。内部パッケージ
`com.spatialaudiosystem.*` は API 表面ではなく、バージョン間でリパッケージされる可能性があります。
