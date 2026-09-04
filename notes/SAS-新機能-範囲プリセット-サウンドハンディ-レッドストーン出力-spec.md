# SAS 新機能 仕様 — 範囲プリセット / サウンドハンディ / レッドストーン出力

**版: v1.8**（2026-09-02 起草、09-03 改訂）。元: `notes/SAS新機能追加。.txt`（ユーザー原文、2026-09-02）。
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
