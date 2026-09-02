# SAS 新機能 仕様 — 範囲プリセット / サウンドハンディ / レッドストーン出力

**版: v1.1**（2026-09-02 起草、同日改訂）。元: `notes/SAS新機能追加。.txt`（ユーザー原文、2026-09-02）。
規約: `manta/notes/BELUGAEXPERIENCE_RULES.md` v2.5 に従う（数値はホイール R4.13.0、boolean は
`ToggleSwitchController` R4.14.0、Item は tool_mode + Alt/Ctrl/Shift ホイール R3.2.x、中ボタン処理後は
`setCanceled` R3.5、HUD は `HudChrome` / `HudAnimState` / `HudConstants`、Screen は `JsonLayoutPlainScreen` /
`JsonLayoutScreen<T>`、control 記号は registry icon R4.23.1）。

## 0. 現状（実装を読んで確認した事実）

| 項目 | 現状 | 出所 |
|---|---|---|
| 装置の所有者 | `ownerUUID` / `ownerName` = **最初に開いた人**（`claimAndAllow`）。`privateMode` で所有者専用。画面右下に `OwnerFacePainter` の顔 + `OwnerAccess.ringColor` の輪 | `PlaybackDeviceBlockEntity.java:153-191`, `PlaybackDeviceScreenV2.java:614` |
| 設置時の所有者 | `setPlacedBy` は未実装 → 設置者 ≠ 所有者になり得る | `PlaybackDeviceBlock.java` |
| 範囲（ボード無し） | `attenuationRange`（既定 8、**clamp 0〜15**）を球の半径として線形減衰。減衰 OFF は定数 `AMBIENT_FALLOFF_BLOCKS` | `PlaybackDeviceBlockEntity.java:160,373`, `SpatialGain.java:57-70` |
| 範囲（ボード有り） | ボードの箱 + 6 面の減衰距離。**v1 の記述「装置自身の範囲は使われない」は誤り**だった: 面の距離の穴埋めが「stack に面成分が無いか」で決まり、角だけ設定した普通のボードは装置の範囲を 6 面に流用していた（v1.1 で「箱が無いときだけ穴埋め」に修正） | `SpatialGain.java:44-55`, BE `playMedia` |
| 画面の範囲表示 | `pb-atten-range` は表示のみ（ホイール未配線）。`SetAttenuationRangePayload` は存在 | `PlaybackDeviceScreenV2.java:239,327` |
| スケジュール操作 | `PlaylistCommandPayload` OP 0〜8（play all / stop / test / count / add / remove / reorder / mode / loop） | `PlaylistCommandPayload.java:24-32` |
| 画面を開く経路 | `useWithoutItem` → `openMenu(be, pos)`。`PlaybackDeviceMenu.stillValid` は **8 ブロック距離チェック** | `PlaybackDeviceBlock.java:65-69`, `PlaybackDeviceMenu.java:131` |
| レッドストーン | **入力のみ**（`POWERED`、立ち上がりで `startPlayback`）。出力無し | `PlaybackDeviceBlock.java:81-95` |
| HUD | `RangeBoardHudRenderer`（`RenderGuiEvent.Post`、`HudAnimState`、`HeldTools`）が範囲ボード用に既存 | `screen/RangeBoardHudRenderer.java` |
| Manta Memo | `belugalab.mcss3.memo`（**internal、SAS から import 不可**）。流用は `assets/manta/layouts/memo.json` の**構造を写す**（220×360、ヘッダ / 一覧タブ / 編集タブ / 設定タブ / 下部ナビ 3 セル） | `manta/.../memo.json` |
| SavedData | `AudioIdRegistry extends SavedData` が既存パターン | `audio/AudioIdRegistry.java` |
| Manta 公開部品 | `TextInputController(maxLen, placeholder)`, `NumberWheelInput`, `ScrollViewport(total, visible)`, `TabController`, `ToggleSwitchController`, `HudToast`, `ScrollCooldown`, `ModifierKeys`, `HeldTools` | `com.manta.api.controller/hud` |
| アイコン | `play` `square` `repeat` `list` `pencil` `plus` `minus` `zap` `check` `link` `map-pin` `music` `settings` `timer` `trash-2` `volume-2` `power` `share-2` `copy` `refresh-cw` `chevron-up/down` | `assets/manta/icons/icons.json`（188） |

## 1. 範囲プリセット（Phase 1）

**意図**: ボード無しでも、ジュークボックス相当の再生範囲を装置の画面で選べる。

- 装置自身の範囲 `attenuationRange` を**再生範囲（プリセット）**として扱う。範囲 **1〜64 ブロック**（現行 0〜15 を
  拡張。0 は廃止 = 無音の設定値は持たない）。
- **既定値**: 新規設置は **64（ジュークボックスと同じ）**。既存装置は保存値のまま（8 のものは 8）。
- **UI**: 装置画面の `pb-atten-range` 行を値 div にし、ホバー + ホイールで ±1（R4.13.0）。表示は
  「再生範囲: 64 ブロック（ジュークボックス）」/「再生範囲: 24 ブロック」。ホイール直後に client 即時反映 +
  `SetAttenuationRangePayload`（decode で 1〜64 を検証、範囲外は拒否）。
- **ボードとの関係**: ボードが入っている間はボードの箱 / 6 面減衰が有効で、プリセットは使われない（現行どおり）。
  画面ではプリセット行を暗色表示（`colorKey`）にして「ボード有効」を示す。
- **減衰 OFF（ボード無し）は現行どおり**（160 ブロックの緩い減衰、範囲に依存しない）。v1 で「半径内 1.0 / 外 0」に
  変えたが、既存ワールド（保存値 8）と TSU のアナウンス（`SasApi` 経由、ボード既定配列）が 8 ブロックで無音になるため
  二次読みで撤回（v1.1）。
- **ボードとの境界（v1.1）**: 面の距離の穴埋めは「箱が無い」ときだけ装置のプリセットで行う。箱があるボードは面成分
  （未編集なら 8）を使い、プリセットは読まれない。角無し・面編集済みのボードはプリセットが有効。
- 範囲表示トグル（ワールド描画）は v1 ではボードの箱のみ（半径の描画は対象外）。

## 2. サウンドハンディ（Phase 3）

**意図**: 手持ちアイテム 1 つで、自分が設置した再生装置を一覧・選択・遠隔操作する。

### 2.1 アイテム
- id `spatialaudiosystem:sound_handy`、テクスチャはユーザー提供 `soundhundy.PNG`（16×16）を
  `textures/item/sound_handy.png` に配置。スタック 1。
- `tool_mode`（DataComponent）: `0 = GUI`（右クリックでハンディ画面）/ `1 = SELECTION`（装置を右クリックで
  対象に選択）。**Alt+ホイール** = モード循環（R3.2.1）。
- **Shift+ホイール** = 対象装置の切替（R3.2.3 の値直編集に相当。cooldown 180 ms、`setCanceled`）。
- **中ボタン** = 選択中の装置を再生（サーバー側 `startPlayback`。所有者のみ）。**Shift+中ボタン** = 停止。
- Shift+右クリック = 対象選択のクリア（R3.8 テンプレート）。ツールチップ最終行は操作ヒント（A3.11）。

### 2.2 リンク（所有者判定）
- 所有者 = **設置者**。`PlaybackDeviceBlock.setPlacedBy` で `setOwner(placer)` を設定（新規）。既存装置は
  従来の「最初に開いた人」のまま（互換）。
- サーバーの `SoundDeviceRegistry extends SavedData`（overworld の DataStorage に 1 つ）: owner UUID →
  [(GlobalPos, name)]。登録は設置時、削除は `onRemove`（撤去でリンク解除・一覧から消える）。所有者が後から
  確定した既存装置は、所有者が開いた時点で登録（遅延登録）。
- 他人の装置は決してリンクされない（登録簿が owner 単位。表示・操作の全経路で owner == player を検証）。

### 2.3 一覧同期
- `HandyDeviceListPayload`（S2C）: 手に持った時 / 画面を開いた時 / 登録簿変更時に、その所有者へ一覧を送る。
  行 = GlobalPos, name, loaded?, playing?。上限 64 装置（decode で検証）。
- 選択中の装置は item の DataComponent `selected_device`（GlobalPos）。サーバーが権威（中ボタン再生の対象）。

### 2.4 ハンディ画面（`JsonLayoutPlainScreen`、layout `sound-handy.json`、memo.json の構造を写す）
- ヘッダ: 題名「サウンドハンディ」、📖（wiki ページは後続）、×。
- **一覧タブ**: `<repeat>` 行 + `ScrollViewport`（可視 12 行）: `music` アイコン / 名前（未命名は
  「再生装置 (x, y, z)」）/ 座標 + ディメンション / 状態ドット（再生中=緑・待機=灰・未ロード=暗）。クリックで選択。
- **装置タブ**（選択中の装置）: 名前の編集（`TextInputController(32)`、確定で `SetDeviceNamePayload`）/
  座標 / 状態 / ボタン: **再生**（world 再生）・**停止**・**テスト**（持ち主の client だけで媒体をローカル再生 =
  既存のプレビュー経路）・**装置を開く**（遠隔で装置本体の画面を開く → 記録媒体の挿入・スケジュール・範囲ボードは
  既存 UI がそのまま使える）・**範囲を共有**（§2.6）。
- 下部ナビ: 一覧 / 装置 / 設定（設定 = HUD 表示 ON/OFF トグル程度）。
- 名前は装置本体の画面の題名にも出す（`pb-title` → 名前があれば名前）。

### 2.5 遠隔オープン
- `PlaybackDeviceMenu` に「遠隔」フラグ: `stillValid` は距離ではなく **ハンディを手に持っている && 所有者**で判定。
  サーバーは `OpenDeviceRemotePayload(pos)` を受けて権限確認 → `openMenu(be, pos)`（既存 provider）。
- 未ロードの装置（chunk 未ロード）は「未ロード」表示で遠隔操作不可（強制ロードはしない）。

### 2.6 範囲指定の共有
- 選択中の装置の **プリセット半径 + 減衰モード** を、自分の全装置（または一覧で複数選択した装置）へ適用する。
  ボードの箱（絶対座標）はコピーしない（他の場所では意味を持たない）。

### 2.7 サブ UI（HUD）
- ハンディを手に持っている間、ホットバー上に小バッジ（`HudChrome` 二層、`HudAnimState` 入退場 300/250 ms）:
  「♪ 名前 (x, y, z)  n/N  ●再生中」。Shift+ホイールで即時更新。中ボタンの結果は `HudToast`。
- `mc.screen != null` / `hideGui` では描画しない（R2.3.1/2）。

## 3. レッドストーン出力（Phase 2）

**意図**: 再生状態を外部機械へ伝える。ランプ点灯（レベル出力）とパルス、強度と遅延を UI で設定。

- ブロック: `isSignalSource = true`、`getSignal`/`getDirectSignal` = BE の現在出力（0〜15）を**全面に**出す。
  出力値が変わった tick で `updateNeighborsAt`。入力（既存の立ち上がり再生）はそのまま。
  ※同じ回路に入力と出力を繋ぐと自己再生になり得る（v1 では仕様として注記、面の選択は v2 候補）。
- **ルール（最大 6 本、BE に永続化）**: `{trigger: PLAYING | START | STOP | END, mode: LEVEL | PULSE,
  strength 1〜15, delayTicks 0〜600, pulseTicks 2〜100}`。
  - `PLAYING` + `LEVEL`: 再生中は strength を出し、停止・終了で 0（それぞれ delay 後に反映）。
  - `START` / `STOP` / `END` + `PULSE`: 事象から delay 後に strength を pulseTicks の間出す。
  - 複数ルールが同時に有効なら **最大値**を出力（「複数条件」）。
  - 事象: START = `startPlayback` / スケジュール entry 開始、STOP = 明示停止（UI・ハンディ・レッドストーン）、
    END = 自然終了（`PlaybackEndedEvent`）。
- **UI**: 装置画面に `zap` アイコンのボタン → 新ダイアログ `playback-redstone.json`（overlay）:
  マスタートグル「出力を有効」、ルール行の `<repeat>`（trigger ⇅ / mode ⇅ / 強度 / 遅延 秒（0.5 秒刻み）/ パルス長 tick
  — すべて値 div のホイール）、追加 / 削除（`plus` / `trash-2`）。`RedstoneRuleCommandPayload(pos, op, index, field, delta)`
  を decode で範囲検証。
- 既定: ルール無し（出力 0）= 既存ワールドの挙動不変。

## 4. 検証

- 各 Phase で `gradlew check` 緑（layout validation / `UntranslatedTextGateTest` / `ControlGlyphGateTest` / i18n 両言語）。
- 変異台帳（`scripts/playback.mutation.py`）に: プリセット clamp と decode 境界、登録簿の追加・削除・他人除外、
  遠隔 `stillValid` の所有者判定、レッドストーンの max 合成・遅延・パルス終了、を追加。
- 実機: 両配備 → (1) ボード無しの半径 64 で聞こえる範囲、(2) ランプ点灯 / パルス、(3) ハンディの一覧・中ボタン・
  Shift+ホイール・遠隔オープン、を VM-A で確認。

## 5. 未確定（ユーザー判断待ち。推奨案で先に進める）

| # | 論点 | 推奨 | 代替 |
|---|---|---|---|
| Q1 | 新規装置の既定範囲 | **64（ジュークボックス）** | 現行 8 のまま |
| Q2 | 「範囲指定の共有」の中身 | **プリセット半径 + 減衰モードを自分の全装置へ** | ボードの箱も含める / 選択装置のみ |
| Q3 | レッドストーン出力の面 | **全面** | 装置の背面のみ |
| Q4 | 実装順 | **1 範囲プリセット → 2 レッドストーン → 3 ハンディ**（小→大、各 Phase で実機確認） | ハンディ先行 |

## 変更履歴

- v1.1（2026-09-02）: 二次読みの所見で §0 の「ボード有りではプリセット不使用」を訂正し、面距離の穴埋め規則を
  「箱の有無」に変更。減衰 OFF の半径化を撤回（既存ワールドと TSU への影響）。
- v1（2026-09-02）: 起草。実装の現状（§0）を読んだ上で 3 機能を分解、未確定 4 点を §5 に明示。
