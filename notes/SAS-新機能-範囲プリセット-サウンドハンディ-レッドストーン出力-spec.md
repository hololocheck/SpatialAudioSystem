# SAS 新機能 仕様 — 範囲プリセット / サウンドハンディ / レッドストーン出力

**版: v2.6**（2026-09-02 起草、09-05 改訂）。元: `notes/SAS新機能追加。.txt`（ユーザー原文、2026-09-02）。
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

### 2.8 実装記録（v1.9、2026-09-04）
- §2.1: `item/SoundHandyItem`（`ModItems.SOUND_HANDY`、stacksTo 1、テクスチャ `sound_handy.png` = 素材 `soundhundy.PNG`）。DataComponent は
  `handy_tool_mode`（Integer）/ `handy_selected_device`（GlobalPos）/ `handy_hud_hidden`（Boolean、無し = 表示）。Alt/Shift ホイールと中ボタンは
  `client/SoundHandyClientHandler`（`ScrollCooldown`、処理後 `setCanceled`）。
- §2.2: `handy/SoundDeviceRegistry extends SavedData`（`spatialaudiosystem_sound_devices`、overworld の DataStorage）。登録は `setPlacedBy`
  （設置者を所有者に）/ `createMenu` の claim 後（既存装置の遅延登録）/ 改名、削除は `onRemove`。`handy/SoundDeviceLink` が BE・登録簿・一覧 push を結ぶ。
- §2.3: `HandyDeviceListPayload`（S2C、64 行・名前 64 文字を decode で検証）、`HandyActionPayload`（C2S、action 0〜10 / arg ±1000 を decode で検証:
  REQUEST_LIST / SET_MODE / SELECT / CLEAR_SELECTION / PLAY / STOP / SET_HUD / OPEN / SHARE_RANGE / TEST / STOP_TEST）、`SetDeviceNamePayload`。
  §2.5 の `OpenDeviceRemotePayload` は独立させず `HandyActionPayload.OPEN`。通知は lang key を送り client 側で翻訳（`ClientNotifyPayload.resolve`）。
- §2.4: `screen/SoundHandyScreen extends JsonLayoutPlainScreen` + `layouts/sound-handy.json`（220×360、一覧 / 装置 / 設定、`<repeat>` 12 行 +
  `ScrollViewport`）。名前は `TextInputController(32)`。装置タブのボタン: 再生 / 停止 / テスト / テスト停止 / 装置を開く / 範囲を共有。
  **テスト**は `handy/HandyTestPlayback`: 媒体スロットの媒体を所有者にだけ、所有者の立ち位置を session key にして送る（装置自身の world 再生
  session と衝突しない。スケジュールモードで媒体スロットが空なら「記録媒体が入っていません」）。停止時の registry `end` は
  `currentId` が自分の playbackId のときだけ（立っていた場所に後から装置が置かれ再生していれば、その session は残す。二次読みの指摘）。
- §2.5: `PlaybackDeviceMenu(…, remote)` の `stillValid` = `remoteStillValid(removed, holdingHandy, player, owner)`（純関数、台帳で破る）。
  **制約（v1.9 で判明）**: 装置画面はクライアント側の BE を読む（menu の client ctor は自分の level から BE を引く）ため、遠隔オープンは
  **同じディメンションで表示範囲内（server view distance）** の装置に限る。クライアントは先に自分の level に BE があるかを見て、無ければ
  `too_far` の toast で止める（ctor が例外を投げる前に）。サーバー側は `SoundDeviceLink.chunkSentTo` = その chunk の tracker に
  そのプレイヤーがいるか（`ChunkMap.getPlayers`）。view distance ではなく chunk map に聞く: クライアントの描画距離はサーバーより小さくてよい
  （二次読みの指摘）。
- §2.6: `SoundDeviceLink.shareRange` = プリセット半径 + 減衰モードをロード済みの自装置へ（{適用数, 総数} を通知、未ロードは総数にだけ入る）。
  箱はコピーしない（Q2 の推奨どおり）。
- §2.7: `screen/SoundHandyHudRenderer`（`HudChrome` 2 行: モード badge / 「♪ 名前  n/N」+ 再生中は緑、`HANDY_HUD_HIDDEN` で非表示）。
  結果通知は `HudToast`（`ClientNotifyPayload` → `SoundHandyHudRenderer.route`: ハンディ所持中は toast、それ以外は従来の範囲ボード行）。
  `HudToast.render` は「mod ごとに 1 回」の契約で、SAS ではこの renderer が唯一の呼び出し。
  **教訓**: `ClientNotifyPayload`（common）の lambda に `Minecraft` / `LocalPlayer` を書くと、payload handler 登録時の verifier が client class を
  ロードして dedicated server（test JVM）で `invalid dist DEDICATED_SERVER` になる。held 判定は client class 側に置く。
- 検証: `SoundHandyModesTest` / `SoundDeviceRegistryTest` / `HandyPayloadsTest` / `HandyRemoteMenuTest`（`gradlew test` で数える）、
  `scripts/playback.mutation.py` に Phase 3 の 9 制御（登録簿の他人除外・65 台目・名前の切り詰め、選択の wrap・空からの着地、decode 境界 2、
  遠隔 stillValid の所有者 / 所持）。wiki `tools/sound-handy`（ja/en、index に追加）。

### 2.9 実機評価 1 回目（v2.0、2026-09-04 22:0x）→ 改修の内容

ユーザーの指示 11 点（作業ログ 09-04 §14）と決定 2 件（モード廃止 / Shift+R はボードと同じ全部）を実装した。以下が v2.0 の仕様。
§2.1〜§2.7 の記述のうち、これと矛盾する箇所はこの節が優先する。

- **モード無し**（R3.1.1 の例外、ユーザー決定）: `handy_tool_mode` は削除。右クリック = 自分の再生装置なら対象に、それ以外は画面を開く。
  Shift+右クリックは対象解除。Alt+ホイールの循環は無い。
- **中ボタン = `TOGGLE_PLAY`**: 再生中なら停止、そうでなければ再生。再生できる媒体（通常: 媒体スロットに音声 / スケジュール: 曲が 1 つでも）
  が無ければ「再生できません: 記録媒体が入っていません」（`SoundDeviceLink.canPlay`）。
- **Shift+R = 範囲モード**（`handy_range_mode`、`TOGGLE_RANGE`）: 対象装置内の範囲指定ボードを `RangeRenderer` が「手に持ったボード」と同じ
  描き方（2 点目は視線追従）で描く。ボードと同じ編集を `HandyRangeEditPayload`（SET_POS1 / SET_POS2 / CLEAR / STEP_FACE、decode 境界）で
  サーバーが装置内のボード stack に適用する: 右クリック = 箱の 1 点目 → 2 点目（空クリックは視線先、ボードと同じ）、Shift+右 = 解除、
  Alt+ホイール = 表示モード（`RangeBoardHudRenderer.currentMode` を共有）、Ctrl+ホイール = 向いている面の減衰（0〜15、1 段ずつ）。
  Shift+ホイールは装置切替のまま（R3.3.1: Alt > Ctrl > Shift）。ボードが無い / 未ロードは拒否して通知。
- **一覧行 `Row`** に `hasMedium` / `hasBoard` を追加（未ロードは両方 false）。HUD と画面の「再生できない / 範囲編集できない」はこれを読む。
- **ミニ HUD**（`SoundHandyHudRenderer` を全面書き換え）: 中央のモードバッジは廃止。左端・縦中央の 190 px パネルが左からスライドイン
  （`HudAnimState` の entry/exit を x に使う）。行 1 = 再生装置のアイテムアイコン（`g.renderItem`）+ 名前 + n / N、行 2 = 状態
  （再生中 / 停止中 / 未読み込み / 再生できない: 記録媒体なし）、行 3 = 範囲モード ON / Shift+R: 範囲 / 範囲編集できない: ボードなし。
  幅 190 は `HudConstants.BADGE_W` の例外（サイドパネル）。
- **画面**（`SoundHandyScreen`）: 乗り換え案内端末と同じ右下固定（`dialogAnchor`、margin 12、`autoScaleEnabled=false`、GUI scale 2 相当に
  fit する `panelScale`、右下 pivot）と下からのスライド（220 ms ease-out、`onClose` を override して自前の退場 → `performClose`）。
  マウス座標は `sMx/sMy` で panel 座標へ逆変換（TSU と同じ）。× は `handleMainClick` で `onClose()`（base は overlay が無いと閉じない）。
  行は h 24 / stride 26 の 10 行、行頭に再生装置のアイテムアイコンを `afterDialogRender` で描く（layout に item ノードは無い）。
  下部ナビは「一覧 / 設定」の 2 つ。一覧の行クリック → その装置の編集ページ（← で戻る）。装置ページ = 名前ボックス / 座標 / 状態 /
  再生・停止 / テスト・テスト停止 / 装置を開く / 範囲を共有。
- **再生装置の画面**（`PlaybackDeviceScreenV2`）: 題名 `pb-title` が名前ボックスになった（クリックで `TextInputController(32)`、Enter で
  `SetDeviceNamePayload`）。同 payload はハンディ所持中か、その装置の menu を開いている本人なら受け付ける。
- 検証: `HandyRangeEditPayloadTest`、既存テストの更新。台帳の既存制御はそのまま（`cycleSelection` / decode 境界 / 65 行 / 遠隔 stillValid）。
- 二次読み（2 巡）で直した 2 点: 両手にハンディを持つと use/useOn は操作した stack で判定しサーバーは `held()`（main 優先）を読む
  → `held()` が返す stack だけが動き、他方は PASS。画面の開く slide 中はクリックの hit-test が縦にずれる（`sMy` は scale しか戻さない）
  → slide 中（開閉とも）はクリックを swallow。

### 2.10 実機起動の失敗と根治（v2.1、2026-09-05）

- 症状: v2.0 の jar を配備した client が `Error loading mods` / `ExceptionInInitializerError` で **mod ごと読み込めなかった**。
- 原因（`latest.log` 実測）: `SoundHandyHudRenderer` の
  `private static final ItemStack DEVICE_ICON = new ItemStack(ModBlocks.PLAYBACK_DEVICE.get());`。
  `@EventBusSubscriber` のクラスは FML が **mod construction 中にロード**するので、`<clinit>` はレジストリのバインド前に走り
  `NullPointerException: Trying to access unbound value` になる。
- 対処: アイコンは `SoundHandyHudRenderer.deviceIcon()` で**初回使用時に解決**（画面側も同じ 1 つを使う）。
- 同型の走査（§7）: 3 repo を `static ... = Mod*.X.get()` で grep → 2 件、どちらも v2.0 で私が足した箇所。両方直した。
- 再発防止: `RegistryStaticInitGateTest`（`SAS-LOAD-001`）が `src/main/java` を走査し、
  **静的フィールドの初期化子と静的初期化ブロックの両方**について Mod\* holder の `.get()` を拒否する。
  走査前にコメントと文字列・文字リテラル・テキストブロックを（位置を保ったまま）空白化するので、
  `static /* c */ {` もリテラル内の `}` も欺けない。対照 3 本（ソースツリーが見えているか / 空白化が位置を動かさないか /
  テキストブロックの後の実コードが残るか）。変異台帳に制御 3 本（static フィールド × 2 パッケージ、static ブロック）。
- **なぜ既存の検査で赤にできなかったか**: SAS の `gradlew test` は dedicated dist で mod construction まで実行する唯一の
  server-dist ゲートだが、**`Dist.CLIENT` の subscriber をロードしない**。compile・test・変異台帳・二次読みのどれもこの経路を通らない。
  **client を 1 回起動することだけが赤にできた**。
- **受け入れた残存所見**（二次読み 4 巡目、打ち切り規則）: 空白化はテキストブロックの区切りを厳密には解釈せず、
  `""""` のように本文が引用符で終わる形と、`""` の直後に `"` が来る形では引用符の対応がずれ得る。
  同じクラスに静的レジストリ初期化子が同居して初めて見逃しになるため、コードは動かさず記録に留める。
- 検証: SAS 全 test **261**、変異台帳 **176/176**（09-05 02:46 の通し実行、受領書 `sources: 8d8643774d3ec8a1`、SKIP 0）。
  この 3 本が load ゲートの制御（static フィールド × 2 パッケージ、static ブロック）。
  jar `CA989331B1C5D582771F9465DD379A54` を両配備。
  **配備後に client を起動して `latest.log` を読んだ**: `SpatialAudioSystem initialized` 00:37:23、
  `ExceptionInInitializerError` / `ModLoadingException` / `failed to load correctly` は **0 件**。VM-A `Done (0.918s)` 00:38:44。

### 2.11 実機評価 2 回目の指示（v2.2、2026-09-05）

ユーザーの指示（作業ログ 09-04 §15）に対する実装。

- **レイアウト調整モード**（設定タブ）: `client/SoundHandyLayoutState`（client 専用・セッション内保持、TSU の
  `TransitTerminalState` と同じ形）に `layoutAdjustMode` とパネル / HUD のオフセット。ON の間、画面は
  ミニ HUD を実物のまま描き（`SoundHandyHudRenderer.renderGhost`）、HUD 本体とパネルのヘッダをドラッグで移動できる。
  「位置をリセット」で両方が既定の角へ戻る。**ドラッグは画面内に clamp**（`clampOffset`）— 画面外へ出すと掴み直せない
  （二次読みの指摘）。HUD は画面が開いている間は描かれない（R2.3.2）ので、調整は画面側が代理で描いて行う。
- **ミニ HUD の既定位置**: 画面中央左 → **左下**（`BOTTOM_MARGIN = 48` でホットバーを避ける）。左からのスライドインは維持。
- **画面を開いても暗転しない**: base は blur と transparent gradient を無効化しているが、in-world screen では vanilla の
  `renderBackground` 自体が暗転する。TSU 乗り換え案内端末と同じく `renderBackground` を空 override にした。
- **設置した装置が HUD に出ない**: `SoundDeviceLink.ensureTarget` — 一覧を push するとき、ハンディの対象が未設定
  （または対象がもう一覧に無い）なら、直前に設置した装置（`pushListTargeting`）か先頭の装置を対象にする。
  **プレイヤーが選んだ対象が一覧に残っていれば触らない**。これで設置直後に中ボタン再生が届く。
- **画面のボタンが無反応に見えた**: 対象未設定のとき `sendAtSelected` が黙って return していた → 「装置が選択されていません」を toast。
- **wiki**: `wiki/index.json` に `tools/sound-handy.md` を追加（登録漏れで「target page not found」だった）。
  あわせて wiki のリンクは**ページ位置ではなく wiki ルートから解決される**ことが実測で分かったので、SAS の 6 ページ
  （ja/en）の相対リンクをルート相対へ書き換えた（実機の broken link 385 → 372、SAS 分は 0）。
- 検証: SAS 全 test **261**、jar `4DC814D295913007CC440B08D82531B4` を両配備、VM-A `Done (1.105s)` 01:49:40、
  **client を起動して実測**: load エラー 0、`SpatialAudioSystem initialized`、wiki `loaded 6 pages`、SAS の broken link 0。
- 未処理（別件として記録）: Manta のシステムフォントが 01:38 の起動で
  `FreeType error: Unrecognized error: 0x14 (Loading glyph)` を出し、MC が選択中リソースパックを全解除した。
  次の起動（01:49）では 0 件で再現せず、SAS とは無関係（`FontManager.finalizeProviderLoading` → `TrueTypeGlyphProvider`）。

### 2.12 実機評価 3 回目の指示（v2.3、2026-09-05、Manta 2.3.0 → 2.3.1 を伴う）

ユーザーの指示 10 点（作業ログ 09-04 §16）に対する実装。部品になるものは Manta に置いた（規約 §3・§10）。

- **ミニ HUD は画面表示中も出す**: Manta 2.3.0 の `com.manta.api.hud.HudCoexistentScreen`（R2.3.2 の例外マーカー、rules v2.6）を
  `SoundHandyScreen` が実装し、HUD renderer は `mc.screen == null || instanceof HudCoexistentScreen` で描く。HUD は screen より先に
  描かれるので、ヒントの吹き出し（`HintHud`、左下）が上に来る → **`HudTooltipDodge`**（Manta 2.3.0）で重なる分 + GAP だけ上へ滑って逃げる。
  吹き出しの矩形は `HintHud.currentBox()`（描いた frame の見た目の箱）。レイアウト調整モードの ghost 描画は廃止（R2.9.2 = 二重描画禁止）。
- **装置ページのスライド**: 装置ページの全ノードに `dynamicX:"hd-dev-x"`。engine はノードごとに自分の x を default に渡すので、
  `getDynamicNumber` が `default + offset` を返せば 1 キーでページ全体が動く（親の dynamicX は子を動かさない: 子は絶対座標）。
  入場 = -220 → 0、退場 = 0 → -220（220 ms ease-out）、退場完了で `page` を切替（`settleDevPage`、毎 frame）。
- **行の点**: 媒体あり=緑 / なし=赤 / 未ロード=暗（再生中の区別は状態行が持つ）。
- **名前ボックスに名前が出ない**: `textKey` は class で解決される（R4.6.1）のに class が `hd-dev-name-box` だけだった → `hd-dev-name-text` を class に追加。
- **カーソルの点滅**: 部品は既存の `com.manta.api.render.TextCaretRenderer`（wiki 検索・列車プリセット検索が使う）。ハンディの名前ボックスと
  再生装置の題名ボックスの上に `canvas` ノード（`hd-dev-name-caret` / `pb-title-caret`）を重ね、`drawCanvas` で focus 中だけ描く（owner 付き = IME 用の caret 報告）。
- **装置を開いてもハンディは開いたまま**: MC の screen は 1 枚なので、`SoundHandyScreen.behind` に自分を預けて OPEN を送り、
  `PlaybackDeviceScreenV2.init` が受け取って `render` の先頭で `renderBehind`（休止状態のパネル、入力なし）、`performClose` は container を
  閉じてから `setScreen(handy)`。ESC は装置画面が先に受けるので「装置 UI から閉じる」になる。
- **中ボタンで再生停止できない**: vanilla の pick block は `handleKeybinds` の一方の分岐でしか `pickBlock()` を呼ばず、
  `InteractionKeyMappingTriggered` はその中でしか発火しない。TSU のツールと同じ **`InputEvent.MouseButton.Pre`（中ボタン、PRESS）+ `setCanceled`** に。
- **Shift+R と Iris の R**: NeoForge の `KeyModifier.NONE` は IN_GAME で常に active なので Shift 中でも Iris の R が発火する。
  `KeyMapping.click` は `InputEvent.Key` より前なので、こちらの handler で **R に NONE で束縛された他 mapping の `consumeClick()` を
  空になるまで呼ぶ** = Iris の click を先に食う（`eatPlainKeyClicks`）。
- **範囲モードでは範囲指定ボードの HUD**: `RangeBoardHudRenderer` の表示 stack を「手持ちのボード、無ければハンディ対象装置内のボード
  （client BE の range slot、範囲モード ON のときだけ）」に。バッジ・情報行・編集（Alt/Ctrl+ホイール）は同じ。ミニ HUD は自分の renderer で
  そのまま出る。
- **注意（規約）**: ミニ HUD が画面中も出るのは R2.3.2 の例外で、rules v2.6 に `HudCoexistentScreen` として明文化。
- **Manta 2.3.0 → 2.3.1（同日）**: 2.3.0 を配備した client が 09-05 08:55 に `FT_Load_Glyph` でネイティブクラッシュ（`hs_err_pid49956`、
  01:38 の起動では `FreeType error 0x14` でリソースパック全解除）。原因は Manta の bold フォント定義が `reference manta:ui` で regular 面を
  共有し、vanilla の `FontManager` がフォントごとの確定を並列に走らせるため同一 FreeType face を 2 スレッドが叩いたこと
  （`MANTA_FONT_SYSTEM_TTF_SPEC.md` §8）。SAS 側のコードは無変更、内包 Manta だけ 2.3.1 に。
- 検証（v2.3、09-05 09:2x）: SAS 全 test **261**、`nested_manta_check` = 3 consumer とも正本 `manta-2.3.1.jar`、`consumer_link_scan` link-safe。
  jar `C9518FAC501843F4…`（SAS）/ `E89D6B1BF3C14CA6…`（TSU）/ `F9228F8C96E6B20C…`（ASC）を client と VM-A に配備（3 点一致）、
  VM-A `Done (0.902s)` 09:26:03。client を 2 回起動（09:26 / 09:27）: `Manta 2.3.1` 内包で起動、`SpatialAudioSystem initialized`、
  wiki 6 ページ・SAS ページの broken link 0、`FreeType error` 0、新しい `hs_err_pid*` 0（2 回とも）。
  変異台帳 176 本の通し実行は v2.2 時点の受領書のまま（v2.3 の edit 後は未実行 — 次の通しで更新）。

### 2.13 実機評価 4 回目の指示（v2.4、2026-09-05、Manta 2.4.0 を伴う）

ユーザーの指示 9 点（作業ログ 09-05）に対する実装。部品になるものは Manta に置いた（規約 §3・§10、rules v2.7）。

- **装置ページのスライドがパネルの外から入る**: ノードは `hd-dev-x` で -220 → 0 に動くので、動いている間はパネルの左の外に
  見えていた。`GuiGraphics.enableScissor` は pose ではなく GUI 座標なので（vanilla `GuiGraphics.java:219-233`）、
  `render` でパネルの画面上の矩形（右下 pivot の scale、開閉スライド分の y を足す）に clip する。矩形の算術は
  `client/HandyPanelGeometry.screenRect`（pure、`HandyPanelGeometryTest`）。
- **装置画面の裏のハンディに JEI が重なる**: Manta の `MantaJeiPlugin` は overlay の矩形しか JEI に渡していなかった。
  **Manta 2.4.0** で `JsonLayoutScreen.extraOccupiedAreas()`（既定は空）を新設し plugin が合流（R4.12.7）。
  `PlaybackDeviceScreenV2` が `handyBehind` の `screenRect()` を返す。
- **中ボタンで再生 → ミニ HUD が「再生中」のまま**: 行の playing はサーバーの list push でしか更新されず、push は
  ハンディの action（`HandyActionPayload` の末尾）と登録簿の変化だけだった。音が自然に終わる `setIsPlaying(false)`、
  媒体の出し入れ、ボードの出し入れは誰も push しない（同型 = §7 の sister audit: playing だけでなく medium / board も）。
  → 装置の server tick で `SoundDeviceLink.rowSignature(be)`（playing / board / 媒体のファイル名と形式）を
  `RowChangeDetector`（初回は基準、以後の差分で push。pure、`RowChangeDetectorTest`）に流し、変わったら
  `onStateChanged` → 所有者へ `pushList`。owner の無い装置は見ない（誰の一覧にも無いし、テストの bare device が
  それ）。ロード直後は `onLoad` で 1 回 push（tick の初回は黙るので）。
- **範囲指定ボードの減衰は Ctrl+ホイール、HUD にその旨**: 減衰モードの情報行 `range_atten_fmt` を
  「東: 8 ブロック · Ctrl+ホイールで設定」に（`RangeBoardClientHandler:51` はボード手持ちで Ctrl または Shift、ハンディの
  範囲モードは Ctrl のみ — 両方に真）。
- **ミニ HUD に音源情報**: `HandyDeviceListPayload.Row` に `mediumFile` / `mediumFormat`（decode 境界 128 / 16 文字、
  `HandyPayloadsTest`）。サーバーは `SoundDeviceLink.mediumOf(be)`（単体スロット / スケジュールなら鳴っている entry、
  無ければ音のある最初の entry）。client は形式ごとに `new ItemStack(RECORDING_MEDIUM)` + `AUDIO_FORMAT` を 1 本ずつ
  作って `renderItem` — アイテムモデルの `audio_format` override（mp3 / ogg / wav の 3 色）がそのまま効く。
  HUD は 5 行: 名前 + n/N、媒体（アイコン + ファイル名 / 「再生できない: 記録媒体なし」/ 未読み込み）、状態、範囲、ハイライト。
- **Shift+ホイールの切替をスライドで見せる**: `client/HandyTargetSwitch`（pure な状態機械、`HandyTargetSwitchTest`）。
  index が別の装置へ動いた frame でスライド開始、旧行が出て新行が入る（`enableScissor` でパネル内に clip、220 ms、
  R2.7.1 の ease-out cubic）。向きは index 順、ただし直前 500 ms 以内のホイールの hint が勝つ（末尾 → 先頭の wrap でも
  「次」の向き）。初回・対象消失・対象出現・同じ装置の値更新はスライドしない。R2.9.3 として規約化。
- **Shift+H ハイライト**: DataComponent `handy_highlight`、`HandyActionPayload.TOGGLE_HIGHLIGHT`（12）、client の
  `onKey` は R と H を同じ edge-trigger で扱い、Shift+H も他 mod の plain H の click を食う。描画は
  `RangeRenderer` の同じ stage で **Manta 2.4.0 の `WorldOutline.box(…, throughBlocks = true)`**（`RenderType.lines()` と
  同 state で depth test 無し・depth write 無し = `MantaRenderTypes.LINES_THROUGH_BLOCKS`、vanilla `RenderType.java:599-611`
  と `RenderStateShard.java:264-269` を実読）。対象位置の 1 ブロック箱を 0.02 膨らませてハンディの accent 色。
  未ロードでも位置はハンディにあるので描く。R8.3.1 として規約化。
- **ミニ HUD にハイライトの ON/OFF と操作**: 5 行目「ハイライト ON (Shift+H)」/「Shift+H: ハイライト」。
- **ハンディを開くと対象の詳細が開く → 一覧のまま**: `SoundHandyScreen` のコンストラクタは常に `PAGE_LIST`。
- **二次読みの是正（v2.4）**: 保存時 `isPlaying=true` の装置は `onLoad` の push 後に最初の tick の `reconcileAfterLoad` が停止させ、
  その停止後の署名が detector の無音の初回になっていた → `onLoad` で push の前に署名を `offer`（基準 = ロード時の状態）。
  `rowSignature` に「再生できるか」の文字を追加（ファイル名が空の媒体を「媒体なし」と区別）。1 tick 内の複数変化は 1 push、
  online owner の N 台同時ロードは N push（≤64、ログイン時 1 回）は受け入れ。
- 検証（v2.4、09-05 12:2x）: Manta 2.4.0 = `obfuscate publish` 緑（compat gate 9 jar、verifyChromeBin）、obf `ACC57989ADE4643013B8E78D734CBBFE`、
  manta test 2716、cefReference 5 PASSED、manta5 ledger 13/13 が 2.4.0。SAS 全 test **266**（+5: `HandyTargetSwitchTest` 3 /
  `RowChangeDetectorTest` 1 / `HandyPanelGeometryTest` 1）、`nested_manta_check` = 3 consumer とも正本、`consumer_link_scan` link-safe。
  jar `738E947FF4EB6CF6…`（SAS）/ `3DE55C6F9024C0F3…`（TSU）/ `93ED750A7550D9E2…`（ASC）を client と VM-A に配備（3 点一致）、
  VM-A `Done (1.302s)` 12:25:05。client 起動（12:25）: `Manta 2.4.0` 内包、`SpatialAudioSystem initialized`、wiki 6 ページ・SAS ページの
  broken link 0、`FreeType error` 0、新しい `hs_err_pid*` 0。変異台帳は 181 本（+5、needle 各 1）— 通し実行の結果は作業ログ 09-05 §7。

### 2.14 実機評価 5 回目の指示（v2.5、2026-09-05、Manta 2.4.1 を伴う）

ユーザーの指示 4 点（作業ログ 09-05 §8）。

- **名前ボックスの caret が最後の文字の隣に無い**: 部品 `TextCaretRenderer` が x を vanilla `Font.width(String)` で測っていた。2.2.0 以降
  UI font の文字列は Manta の自前ラスタが描くので、caret は vanilla の bitmap glyph が終わる位置（実機で約 60 GUI px、描画は約 37 px）に
  出ていた。TSU の wiki 検索・列車プリセット検索の caret も同じ。→ **Manta 2.4.1**: UI font で styled した `Component` を
  `MantaText.width` で測る（「描く側が測る」の caret 版、`TextRasterWiringGateTest` が source を縛る、変異制御 1 本）。SAS 側は caret に
  `display()`（空なら placeholder）でなく `value()` を渡す — 空欄では caret が先頭に来る（ハンディ・再生装置の両方）。
- **装置ページがシアンの縁からスライドインする**: clip をパネルの矩形からフレームの border 幅（`DialogFrame.DIALOG_BORDER_W` = 2、
  API 外なので SAS に定数 `FRAME_BORDER_W`）× scale だけ内側に。紺色の端から出る。
- **一覧行のヒントが「Shift+ホイールでも選べます」**: 画面表示中はホイールを画面が取るので嘘だった → 「クリックでこの装置を対象にして、
  装置のページを開きます」（ja / en）。
- **JEI が装置画面とハンディの間に出る**: JEI は「GUI の右側」から除外矩形を避けた**大きい方の矩形**に自分を置く。ハンディの矩形だけを
  申告すると、装置画面とハンディの隙間（全高）の方が上の帯より大きく、そこに来た。→ `extraOccupiedAreas` を **装置画面の右端から画面右端まで、
  ハンディの上端から画面下端までの帯**にする（右側で空くのはハンディの上の帯だけになる）。合わせて Manta 2.4.1 で overlay の矩形を
  scale 込みに（二次読み所見の是正）。
- **実機評価 6 回目（同日、v2.5 の追記）: スライド中にシアンの縁が消える** — clip は `super.render` 全体に掛かり、エンジンは frame をノード
  より先に描くので border の帯も clip されていた。ノードだけを clip する hook は無いので、`super.render` の後に `disableScissor` して
  frame の border を同じ pose で描き直す（`SmoothRenderer.strokeRoundedRect(g, px, py, 220, 360, 10, 2, #4fc3f7)` = エンジンの rounded
  border と同じ内側の帯）。slide の 220 ms の間だけ。
- 検証（v2.5）: 作業ログ 09-05 §8〜§9。SAS 266 tests、変異台帳 181/181（§8）、client / VM-A 3 点一致 `4D940701…`（§8）→ 6 回目の追記後は
  `8402A204…`（§9）、client 起動 OK。Manta 2.4.1 = font 変異 45/45、test 2717、cefReference 5、obf `43554B7C…`。

### 2.15 リリース前の 2 点（v2.6、2026-09-05、Manta 2.5.0 を伴う）

- **範囲を共有（§2.6）は機能ごと削除**（ユーザー判断 2026-09-05「不要」）: layout の button と hint span、`SoundHandyScreen` の case、
  `SasScreenHints` の登録、`HandyActionPayload.SHARE_RANGE`（番号 7 は欠番のまま — 旧 client が 7 を送っても handler は無視する）、
  `SoundDeviceLink.shareRange`、lang ja/en の 4 key、wiki ja/en の節。§2.6 と §2.4 の「範囲を共有」の記述はこの節が上書きする。
- **ツールチップ・mini HUD・wiki のフォント**: どれも vanilla の bitmap font で描いていた（dialog だけが raster）。Manta 2.5.0 の
  `MantaText.ui / draw / uiWidth / split` に切り替え（`HintHud`、`HudChrome`、`HudToast`、`HudText`、`WikiScreen`、`MarkdownRenderer`、
  `WikiEmbedRegistry`）。SAS 側は `SoundHandyHudRenderer` の描画と幅を `MantaText.draw` / `MantaText.uiWidth` / `HudText.ellipsize` に。
  wrap は raster の advance で折るので描いた幅と一致する。TSU 自前の `drawString`（149 site）は対象外。
- 検証（v2.6）: 作業ログ 09-05 §10〜§11。Manta 2.5.0 = font 変異 48/48、test 2718、cefReference 5、obf `C67BA1FB…`、ledger 13/13。
  SAS 266 tests、jar `358EBD5D…`（`-all` = plain = release asset）を client / VM-A に配備、VM-A `Done` 17:55:13、client 起動 OK。

## 3. レッドストーン出力（Phase 2）

**意図**: 再生状態を外部機械へ伝える。ランプ点灯（レベル出力）とパルス、強度と遅延を UI で設定。

- ブロック: `isSignalSource = true`、`getSignal` = BE の現在出力（0〜15）を**全面に弱い信号**として出す
  （コンパレータと同じ。固体ブロックを通した強い伝導はしない — 強い信号は隣の隣まで更新する義務があり、
  装置は自分の隣しか更新しないため。v1.3 で `getDirectSignal` を撤去）。出力値が変わった tick と**ロード直後の
  最初の tick**で `updateNeighborsAt`（ロード時は値が 0 のままでも一度通知 — 再生中に保存されたチャンクは
  ダストとランプが点いたまま戻ってくるため）。入力（立ち上がり再生）は POWERED を常に追従させた上で、
  **装置が出力中の立ち上がりだけ無視**（自分の出力がダスト経由で戻る自己再生の防止。出力中はレバーも効かない）。
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
- **実装（v1.2）**: `RedstoneRule`（record、trigger は PLAYING / START / STOP / END。PLAYING はレベル、他はパルス。
  mode は trigger に含意されるので別項目にしない）+ `RedstoneOutputPlan`（tick 演算、MC 非依存、非永続）。
  事象は `playMedia` 成功 = START、`stopPlayback`（再生中のみ）と ∞ OFF の撤回 = STOP、終了報告の
  `setIsPlaying(false)` = END。出力は BE の `refreshRedstoneOutput()` が毎 tick と各事象・編集の直後に再計算し、
  変化したときだけ `updateNeighborsAt`。**入力の自己再生防止**: 装置の出力が 0 より大きい間は `neighborChanged` の
  入力を読まない（自分の出力が隣のダストを通って戻る場合の再始動を防ぐ）。
  遅延つきレベル: 開始から delay 後に ON、停止・終了から delay 後に OFF。delay より短い音はランプを点けない。
  **連続性（v1.3）**: 停止/終了から 1 tick 以内の開始（スケジュールの次曲、supersede）は継続扱いで、遅延つきの
  ランプは消えない。ロード後にまだ開始を見ていない停止/終了はパルスを出さない（保存中再生の timeout 停止対策）。
  出力 OFF 中は事象を溜めない。再生中の 1 回再生を Play All が置き換える場合は「開始」であり「停止」ではない。
  **チャンク再ロード（v1.6）**: ロード時（`loadRedstone`）に保存された `isPlaying` が真なら plan を**永続化した開始
  tick から再開**（START 事象ではない — パルスは出ない、遅延つきランプは遅延をやり直さない）。終了報告がチャンクを
  強制ロードして最初の tick より前に END を届けても、これで END パルスが出る。ロード後最初の tick の
  `reconcileAfterLoad` は「音が生き残ったか」だけを決める: 登録簿に無ければ（サーバー再起動）保存された
  `isPlaying` は古い: 全装置で plan を reset（仮の playing を捨てる）し、ループ未装備なら `stopPlayback`（パルス無し。
  ループ装備は loop 分岐が再張りしたときの START で再点灯。音源が消えたループは点きっぱなしにならない）。
  通知用と reconcile 用のフラグは別。**ロード時の再開は tag から読む**（`loadAdditional` は `isPlaying` を
  `loadRedstone` の後で復元するため、フィールドを読むと本番では一度も再開しない — v1.7）。
  `playbackStartTick` を永続化（再ロード直後の timeout 誤停止と、それに伴う偽の STOP を防ぐ）。

## 3.5 スケジュール / レッドストーン UI の改修（Phase 2.5、v1.8）

Phase 2 の実機評価（「おおむね OK。開始時、停止時、再生中で問題なし。遅延長さも問題なし」）に続くユーザー指示
（2026-09-03）を反映する。

- **16 件**: `PLAYLIST_SIZE` / `MAX_ENTRIES` と `RedstoneRule.MAX_RULES` を 6 → 16。両ダイアログは
  `ScrollViewport(total, 6)` で 6 行を窓表示し（A4.19: 行 = repeat index + offset、ダイアログ root の `wheelKey`
  がリスト領域内のホイールをスクロールに使う — 行内の値セルのホイールは engine が先に拾う）、右端にスクロールバー
  （track 6 px / thumb 20 px、`visibleKey` で必要なときだけ）。追加でリストが伸びたら末尾へスクロール、
  削除で縮んだら clamp。スケジュールのスロット（menu slot）も窓内の行だけ配置し、窓外は画面外へ。
  6 スロットで保存された playlist は `ItemStackHandler.deserializeNBT` が **保存時のサイズに縮める** ため、
  一時 handler に読んでから固定 16 の handler へ写す（`copyPlaylist`）。
- **スケジュール行**: `▶`（試聴）の隣の × を **停止**（`manta:square`、装置の再生を止める = ヘッダの停止と同じ）に。
  **ゴミ箱**（`manta:trash-2`、エントリ削除 + 媒体返却）を媒体スロットの右へ。連続再生の隣の停止は残す。
  行幅 220 → 214（スクロールバー分）、媒体スロット枠 x 192 → 170。
- **スケジュール ON で媒体を返す**: `toggleScheduleMode(Consumer<ItemStack>)`。ON 遷移時に通常スロットの媒体を
  取り出してプレイヤーへ（インベントリに入らなければ足元へ drop）。単体再生中ならその再生を止める（スロットが
  空になるので）。OFF 遷移と空スロットでは何もしない。
- **エントリごとのレッドストーン条件**: `RedstoneRule.entry`（0 = 全体 / 1〜16 = そのエントリ。行の 2 行目
  「対象」セル、ホイールで 全体 → #1 … #16 → 全体 と巡回）。事象は entry を運ぶ（`playMedia(stack, loop, entry)`、
  STOP/END は `playingEntry + 1`。スケジューラは `stop()` で **stopPlayback を setPlayingEntry(-1) の前**に呼ぶ）。
  plan は「いま鳴っている entry と、その開始 tick」「直前の entry と、その開始・終了 tick」を持ち、
  対象つきランプは自分の entry の開始・終了から遅延を測る。同じ entry が 1 tick 以内に再開（再生回数）した場合は
  開始 tick を引き継ぐ（遅延つきランプが瞬断しない）。ロード時の `resume` は entry を知らない（ディスクに無い）ので
  対象つきランプは次の開始まで暗い。単体再生は entry 0 = 「全体」の規則だけに一致（対象 #k はスケジュール再生
  のときだけ意味を持つ — スケジュール OFF のとき対象セルはグレー表示、値は保持）。
  旧セーブ（entry キー無し）は 0 = 全体として読む。
- **赤**: レッドストーン設定ダイアログの縁・タイトル・区切り・追加ボタン・スクロールバーはパレットの赤 `#ef5350`。
  **BelugaExperience R4.3.2（ダイアログ縁 3 色）の例外**（ユーザー指示 2026-09-03、JSON 生成側と本節に明記）。
  装置画面の稲妻ボタンは赤い枠の div + canvas（`pb-redstone-icon`）になり、`SvgIcon.draw` で自前の
  レッドストーン結晶 SVG（4 芒星 + 濃い芯）を描く（Manta の icon registry は触らない = Manta 再ビルド不要）。
  列ヘッダは「条件 / 強度 / 遅延 / 長さ」、行は 2 段（1 段目に 4 値 + ゴミ箱、2 段目に「対象」）で
  スケジュールダイアログと同じ 35 px ピッチ。
- **二次読み 1 巡目の反映（v1.8 内）**: (1) engine は canvas ノードを self-clickable にするので、ボタン内の canvas は
  自分の class でクリックが届く → `onElementClick` に `pb-redstone-icon` の case（gate: clickable 祖先の下の canvas は
  必ず case を持つ。`owner-face-canvas` は Manta の `OwnerAccess.isFaceClick` が処理）。(2) lang の `%d` を全部 `%s` に。
  表示は元から壊れていない（`Language` がロード時に `%d` / `%f` を `%s` に正規化する — Vault
  `Knowledge/translatable-lang-percent-specifier.md`、2026-08-26 に TSU が同じ誤診を経て確認済み）ので、これは
  ファイルと実行時意味を 1:1 にするハイジーン。gate（2 locale に `%s` / `%%` 以外の指定子が無い）が本当に拾うのは
  正規化を通り抜けて生テンプレート表示になる `%x` `%b` `%c` の類。(3) `playingEntry` は **開始が権威**（`playMedia` 成功時に
  entry を書く、単体は −1）、`stopPlayback` で −1 に戻す — 行の試聴の後に単体再生を始めても、その終了が試聴した
  entry の END として報告されない。
- **二次読み 2 巡目の反映（v1.8 内）**: レッドストーン事象の entry は `playingEntry` ではなく **`soundingEntry`**
  （1-based、0 = 単体。`playMedia` 成功時に BE 自身が書く）を使う。理由: `PlaybackFinishedPayload` は
  `PlaybackEndedEvent` を `setIsPlaying(false)` より先に post し、行の試聴ではスケジューラがその event の中で
  `setPlayingEntry(-1)` するため、`playingEntry` を使うと試聴の END が「全体」で報告される（対象 #k の終了規則が
  試聴で鳴らない）。`playingEntry` は枠表示用に残し、スケジューラが自由に動かしてよい。
- **実機評価 2 回目の反映（v1.8 内、2026-09-03 夜）**: 値セルは h 13（枠付きの文字箱は 9 + 2×(border+1) 以上）、
  レッドストーン行に ▲▼（`OP_MOVE`、隣と swap）、英語の条件名は "Playing" 等の短い語（列見出しが文脈）、追加ボタンは
  `btn_add_entry` を共用、装置画面のレッドストーンボタンは canvas 1 ノード（hover は canvas 自身に出る）。gate:
  `englishValuesCarryNoJapanese`（半角カナ含む）と `staticLabelsFitTheirBoxes`（全 layout の `translatableKey` ノードと、
  値セル（条件 / 対象 / 強度 / 遅延 / 長さ / 再生回数 / 範囲行の合成値）の既知の最大値を vanilla `ascii.png` の幅で測る。
  実機フォント（Manta 2.1 のシステムフォント）は可変幅で更に狭いので、これは上限側の検査）。
- **二次読み 3 巡目の反映（v1.8 内）**: `soundingEntry` を **NBT に保存**し（`playbackStartTick` と同じ tag）、
  `loadRedstone` が読んで `redstonePlan.resume(startTick, soundingEntry)` で再開する。これで §3 の「終了報告がチャンクを
  強制ロードして最初の tick より前に END を届けても END パルスが出る」は**対象つき規則でも**成り立ち、対象つきランプは
  保存した entry で再点灯する（本節前段の「対象つきランプは次の開始まで暗い」は **entry が保存されていない旧セーブ /
  単体再生のときだけ**）。

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

- v2.6（2026-09-05）: リリース前の 2 点を §2.15 に。範囲を共有（§2.6）を機能ごと削除（ユーザー判断）、ツールチップ / mini HUD / wiki を
  Manta 2.5.0 の raster font に。1.1.0 のリリース（GitHub / Modrinth）と同時。
- v2.5（2026-09-05）: 実機評価 5 回目の指示 4 点を §2.14 に実装。caret は Manta 2.4.1（`TextCaretRenderer` が UI font で styled した
  Component を `MantaText.width` で測る）+ SAS は caret に `value()` を渡す、装置ページの slide は border の内側に clip、行ヒントの文言、
  JEI は装置画面の右端からの帯を申告してハンディの上に。
- v2.4（2026-09-05）: 実機評価 4 回目の指示 9 点を §2.13 に実装。装置ページの slide を clip、裏のハンディを JEI に申告
  （Manta 2.4.0 `extraOccupiedAreas`）、装置の tick が行の変化（再生 / 媒体 / ボード）を見て一覧を再送、減衰の Ctrl+ホイール
  を HUD に、ミニ HUD に媒体（形式色のアイコン + ファイル名）と Shift+H の行、切替は `HandyTargetSwitch` のスライド、
  Shift+H = ブロック越しの対象枠（Manta 2.4.0 `WorldOutline`）、開くときは常に一覧。
- v2.3（2026-09-05）: 実機評価 3 回目の指示 10 点を §2.12 に実装。画面中も出るミニ HUD と吹き出し回避は Manta 2.3.0 の部品
  （`HudCoexistentScreen` / `HudTooltipDodge`、rules v2.6）。装置ページのスライド、点の色、名前ボックスの class、caret 部品、
  装置画面の裏にハンディ、中ボタンは MouseButton.Pre、Shift+R は Iris の R を食う、範囲モードでボード HUD。
  同日、内包 Manta を 2.3.1 に（2.3.0 の bold フォント定義が FreeType の同一 face を 2 スレッドで叩き client がクラッシュ）、
  配備と client 2 回起動の結果を §2.12 末尾に記録。
- v2.2（2026-09-05）: 実機評価 2 回目の指示を §2.11 に実装 — レイアウト調整モード（HUD / パネルをドラッグ、画面内に clamp）、
  ミニ HUD を左下へ、画面の暗転を止める、設置直後の自動対象、対象未設定の無反応を toast 化、wiki の登録漏れとリンク解決。
- v2.1（2026-09-05）: 実機で mod が読み込めなかった件（静的初期化子でのレジストリ解決）を §2.10 に記録。
  遅延解決へ根治し、ソース走査ゲート `SAS-LOAD-001` と変異制御 3 本で再発を止めた。受け入れた残存所見も明記。
- v2.0（2026-09-04）: 実機評価 1 回目の指示 11 点を §2.9 として実装 — モード廃止、中ボタンはトグル再生、Shift+R の範囲モード
  （装置内ボードをボードと同じ操作で編集、新 payload）、左端ミニ HUD、右下固定 + スライドの画面、一覧行クリック → 装置ページ、
  再生装置の画面に名前ボックス、× の修正。
- v1.9（2026-09-04）: Phase 3 の実装内容を §2.8 に追記 — 遠隔オープンは chunk を送った相手に限る（画面が client 側 BE を読む）、テスト再生の
  session key は所有者の立ち位置、`OpenDeviceRemotePayload` は `HandyActionPayload.OPEN`、通知は toast、common 側に client 型を書かない教訓。
- v1.8（2026-09-03）: Phase 2.5（§3.5）— 16 件 + スクロール、行の停止ボタンとゴミ箱、スケジュール ON での媒体返却、
  ルールの「対象」（エントリ範囲）、赤い縁と自前のレッドストーン SVG（R4.3.2 の例外を明記）。
- v1.7（2026-09-03）: ロード時の再開が本番で一度も動かなかった（フィールド順）→ tag から読む。ループ装備の古い
  フラグも仮の playing は捨てる。
- v1.6（2026-09-03）: 再開はロード時に行い（終了報告による強制ロードでも END パルスが落ちない）、最初の tick は
  古いフラグの片付けだけ（パルス無し）。tick 配線を実行するテストを追加。
- v1.5（2026-09-03）: 再ロードの再開は START 事象ではなく `resume(開始 tick)`（パルス再発火と遅延やり直しを防ぐ）。
  サーバー再起動後の古い `isPlaying` は最初の tick で片付ける。
- v1.4（2026-09-03）: 二次読み 2 巡目 — 再ロードで生き残った音の再点灯、`playbackStartTick` の永続化、
  再生中の開始は継続（負の対照を追加）。
- v1.3（2026-09-03）: 二次読み 8 件を反映 — 弱い信号のみ、ロード直後の通知、POWERED 追従後の自己再生ガード、
  1 tick 以内の再開は継続、開始なしの停止はパルス無し、OFF 中は溜めない。
- v1.2（2026-09-03）: Phase 2 の実装内容を §3 に追記（trigger に mode を含意、事象の対応表、自己再生防止）。
- v1.1（2026-09-02）: 二次読みの所見で §0 の「ボード有りではプリセット不使用」を訂正し、面距離の穴埋め規則を
  「箱の有無」に変更。減衰 OFF の半径化を撤回（既存ワールドと TSU への影響）。
- v1（2026-09-02）: 起草。実装の現状（§0）を読んだ上で 3 機能を分解、未確定 4 点を §5 に明示。
