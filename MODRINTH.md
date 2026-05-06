<p align="center">
  <img src="https://raw.githubusercontent.com/hololocheck/SpatialAudioSystem/master/src/main/resources/icon.png" width="128" alt="Spatial Audio System">
</p>

<p align="center">
  <strong>Custom Audio Playback for Minecraft — MP3 / OGG / WAV</strong><br/>
  <em>Spatial 3D-volume falloff · Range / Attenuation boards · Multiplayer-ready · Public API for addon mods</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A.svg" alt="Minecraft">
  <img src="https://img.shields.io/badge/NeoForge-21.1.168+-orange.svg" alt="NeoForge">
  <img src="https://img.shields.io/badge/version-1.0.4-blue.svg" alt="Version">
  <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License">
</p>

<p align="center">
  <a href="#english">🇺🇸 English</a> · <a href="#japanese">🇯🇵 日本語</a>
</p>

---

<a id="english"></a>

## 🇺🇸 English

**Spatial Audio System** lets you play your own MP3 / OGG / WAV files in Minecraft with full spatial volume falloff. Write audio onto a Recording Medium with the Recording Device, play it back through the Playback Device, and define the audible 3D region (with optional directional attenuation) using the Range Board. Works in multiplayer.

### ✨ Highlights

- 🎵 **Play your own audio** — drop any MP3 / OGG / WAV from your PC into the Recording Device, write it to a Recording Medium, and play through the Playback Device
- 🎯 **3D range control** — Range Board defines a box where the audio is audible at full volume, with optional directional fade-out (attenuation mode)
- 🔴 **Redstone automation** — Playback Device triggers on rising edge, perfect for stations / cutscenes / ambient triggers
- 🌐 **Multiplayer-safe** — server-authoritative file storage (`<world>/spatialaudiosystem_audio/<uuid>.audio`), broadcast playback, no per-client desync
- 📦 **Up to 10 MB per audio file** — chunked transfer survives NeoForge's 1 MB packet ceiling
- 🧩 **Public API** (`belugalab.sas.api`) — addon mods can broadcast playback, listen for `PlaybackEndedEvent`, and read range / attenuation metadata

### 📦 Requirements

| Mod | Version | Required |
|-----|---------|----------|
| NeoForge | 21.1.168 or newer | ✅ |

No other dependencies — works standalone.

### 🧱 Added Content

#### 🔊 Playback Device *(block)*
- Slots: 1 × Recording Medium, 1 × Range Board
- Plays the recording medium when redstone-powered (rising edge) or via the GUI
- With a Range Board inserted, audio is gated to the box; with **attenuation mode** on, volume falls off smoothly at the box's faces
- Auto-stops playback on all clients when the block is broken

#### 🎙️ Recording Device *(block)*
- Slot: 1 × Recording Medium
- Pick an MP3 / OGG / WAV file from your local PC and write it to the medium
- File-based storage means the recording is robust against creative-mode item dupes and container shuffles

#### 💿 Recording Medium *(item)*
- Holds a UUID reference; the actual audio bytes live as a file in the world's `spatialaudiosystem_audio/` directory
- Texture changes per format (MP3 / OGG / WAV)
- Tooltip shows file name and format

#### 📋 Range Board *(item)*
- Defines a 3D box (Pos1 + Pos2) where audio is audible
- Modes: **Normal range / Attenuation / Downward**
- Up to 64-block reach via raycast
- Live preview while held: Pos2 follows your crosshair while only Pos1 is set
- Controls:
  | Input | Action |
  |-------|--------|
  | Right-click (block) | Set Pos1 / Pos2 (alternating) |
  | Right-click (air) | Raycast (up to 64 blocks) and set the coordinate |
  | Shift + Right-click | Clear coordinates |
  | Alt + Mouse Wheel | Switch mode (Range / Attenuation / Downward) |
  | Ctrl + Mouse Wheel | Adjust attenuation distance in the direction you face |

### 🧩 For Mod Developers — Public API

The `belugalab.sas.api` package is a stable surface (won't be repackaged across versions):

```java
// Detect SAS at runtime
if (belugalab.sas.api.SasApi.isInstalled()) {
    // Server-side broadcast playback
    SasApi.playAudio(serverLevel, pos, recordingMediumStack, rangeBoardStack, /*attenuation*/ true);
}

// Hook playback completion (chained sequences, sequential announcements, etc.)
NeoForge.EVENT_BUS.addListener((PlaybackEndedEvent e) -> {
    BlockPos endedAt = e.getPos();
    // ... advance your sequence
});
```

Available helpers: `isRecordingMedium`, `isRangeBoard`, `hasAudio`, `getAudioFileName/Format/Id`, `hasRange`, `getRangePos1/Pos2`, `getAttenuationRanges`, `loadAudio`, `loadAudioFromMedium`, `playAudio`, `stopAudio`.

> The internal `com.spatialaudiosystem.*` package is **not** part of the API surface and may be repackaged. Always reference `belugalab.sas.api` from addon mods.

### 📜 License

[MIT License](https://github.com/hololocheck/SpatialAudioSystem/blob/master/LICENSE)

### 🔗 Links

- Source / Issue tracker: <https://github.com/hololocheck/SpatialAudioSystem>
- Changelog: [English](https://github.com/hololocheck/SpatialAudioSystem/blob/master/CHANGELOG-EN.md) · [日本語](https://github.com/hololocheck/SpatialAudioSystem/blob/master/CHANGELOG.md)

---

<a id="japanese"></a>

## 🇯🇵 日本語 (Japanese)

**Spatial Audio System** は、自分の MP3 / OGG / WAV ファイルを Minecraft 内で空間的な音量減衰付きで再生できる NeoForge Mod です。記憶装置で音声を記録媒体に書き込み、再生装置で再生、範囲指定ボードで音声が聞こえる 3D 範囲 (および方向別の減衰) を定義します。マルチプレイ対応。

### ✨ 主な機能

- 🎵 **自分の音声ファイルを再生** — PC 内の任意の MP3 / OGG / WAV を記憶装置で読み込み、記録媒体に書き込んで再生装置で鳴らす
- 🎯 **3D 範囲制御** — 範囲指定ボードで音声が聞こえる箱を定義。減衰モード ON で箱の境界からなめらかにフェードアウト
- 🔴 **レッドストーン自動化** — 立ち上がり信号で再生開始。駅放送・カットシーン・環境音トリガに最適
- 🌐 **マルチプレイ安全** — サーバ側ファイル管理 (`<world>/spatialaudiosystem_audio/<uuid>.audio`)・ブロードキャスト再生で desync なし
- 📦 **音声 1 ファイル最大 10 MB** — チャンク転送で NeoForge のパケット上限 (1 MB) を回避
- 🧩 **公開 API** (`belugalab.sas.api`) — addon mod から再生指示・`PlaybackEndedEvent` 購読・範囲 / 減衰メタデータの取得が可能

### 📦 必要環境

| Mod | バージョン | 必須 |
|-----|-----------|------|
| NeoForge | 21.1.168 以降 | ✅ |

他の Mod に依存しません。単体で動作します。

### 🧱 追加コンテンツ

#### 🔊 再生装置 *(ブロック)*
- スロット: 記録媒体 × 1、範囲指定ボード × 1
- レッドストーン入力 (立ち上がり) または GUI で再生
- 範囲指定ボードをセットすると箱内のみで音が聞こえる。**減衰モード** ON で境界からなめらかに音量フェード
- ブロック破壊時、全クライアントの再生を自動停止

#### 🎙️ 記憶装置 *(ブロック)*
- スロット: 記録媒体 × 1
- PC ローカルの MP3 / OGG / WAV を選んで記録媒体に書き込み
- ファイルベース保存なので、クリエイティブモードのアイテム複製や容器操作で破損しません

#### 💿 記録媒体 *(アイテム)*
- アイテム自体は UUID 参照のみ。実体は `<world>/spatialaudiosystem_audio/` ディレクトリのファイル
- 書き込まれた形式 (MP3 / OGG / WAV) でテクスチャが変わる
- ツールチップにファイル名と形式を表示

#### 📋 範囲指定ボード *(アイテム)*
- 2 点 (Pos1, Pos2) で音声が聞こえる 3D 箱を定義
- モード: **通常範囲指定 / 減衰率設定 / 下向き設定**
- レイキャスト最大 64 ブロック
- 持っている間、Pos1 のみ設定中は Pos2 が視線先に追従するライブプレビュー
- 操作:
  | 入力 | 動作 |
  |------|------|
  | 右クリック (ブロック) | Pos1 / Pos2 交互設定 |
  | 右クリック (空中) | 64 ブロック先までレイキャストして座標設定 |
  | Shift + 右クリック | 座標クリア |
  | Alt + マウスホイール | モード切替 (範囲 / 減衰 / 下向き) |
  | Ctrl + マウスホイール | 視線方向の減衰距離を変更 |

### 🧩 Mod 開発者向け — 公開 API

`belugalab.sas.api` パッケージはバージョン間で安定した API 表面です (リパッケージされません):

```java
// 実行時に SAS の存在を判定
if (belugalab.sas.api.SasApi.isInstalled()) {
    // サーバー側からブロードキャスト再生
    SasApi.playAudio(serverLevel, pos, recordingMediumStack, rangeBoardStack, /*減衰*/ true);
}

// 再生完了をフック (連鎖再生、シーケンシャル放送など)
NeoForge.EVENT_BUS.addListener((PlaybackEndedEvent e) -> {
    BlockPos endedAt = e.getPos();
    // ... 次の処理へ
});
```

利用可能なヘルパー: `isRecordingMedium`, `isRangeBoard`, `hasAudio`, `getAudioFileName/Format/Id`, `hasRange`, `getRangePos1/Pos2`, `getAttenuationRanges`, `loadAudio`, `loadAudioFromMedium`, `playAudio`, `stopAudio`。

> 内部パッケージ `com.spatialaudiosystem.*` は API 表面ではなく、リパッケージされる可能性があります。addon mod は必ず `belugalab.sas.api` を参照してください。

### 📜 ライセンス

[MIT License](https://github.com/hololocheck/SpatialAudioSystem/blob/master/LICENSE)

### 🔗 リンク

- ソース / Issue tracker: <https://github.com/hololocheck/SpatialAudioSystem>
- 更新履歴: [English](https://github.com/hololocheck/SpatialAudioSystem/blob/master/CHANGELOG-EN.md) · [日本語](https://github.com/hololocheck/SpatialAudioSystem/blob/master/CHANGELOG.md)
