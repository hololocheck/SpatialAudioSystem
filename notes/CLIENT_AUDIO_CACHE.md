# CLIENT_AUDIO_CACHE — 途中参加者の無音を、一度聞いた音声では無くす

> **状態: ACTIVE** — ユーザー判断 ⑨（2026-10-01、推奨「content-hash の client キャッシュ」）の実装仕様。

**バージョン: v1**（2026-10-02 / 初版 2026-10-02）

## 1. 何を直すか

- 再生中の音へ途中から入った人（参加・次元の移動・範囲に入る）は、音声の転送が終わるまで無音になる。2026-09-02 の実機
  （VM-A、5.1 MB）で **12 秒**。位置の補正（`receivedAtMillis` からの経過を足す）は正しく効いており、残る無音は転送時間そのもの
  （作業ログ `2026-09-02-SAS-途中参加の同期-再開と二次読み.md` §22）。
- いまの `ClientAudioChunkPayload.sendChunked` は、同じ音声を同じ人に何度でも全部送る（再ログイン・範囲の出入り・毎日の駅の
  チャイム）。
- **一度受け取った音声を client が内容の hash で覚えておき、サーバーが hash を予告したときに「持っている」と答えれば転送しない。**
  初めて聞く音声の途中参加は今までどおり転送時間ぶん無音（これはこの仕様の範囲外 — 途中からのストリーミングは別の設計）。

## 2. 往復

```
S → C  ClientPlayAudioPayload（既存 + contentHash 32 byte）
C → S  AudioCacheAnswerPayload(pos, playbackId, have)      ← client の network thread から即座に
S → C  ClientAudioChunkPayload × n                        ← have = false のときだけ
```

- **答えは network thread から返す。** 参加直後の client の main thread は地形の読み込みで数秒止まる（09-02 の実測で 6.7 秒）。
  main thread で答えると、キャッシュに無い人の無音が「止まっていた時間 + 転送」に伸びる（いまは止まっている間も chunk が届く）。
  network thread で答えれば、キャッシュに無い場合に増えるのは 1 往復だけ。
- 「持っている」の判定は network thread では**存在と大きさ**だけ（`<hash>.bin` があり、長さが `totalSize`）。中身の照合（sha256）は
  読み込むときに行い、合わなければその file を消して `have = false` を**遅れて**送る（サーバーは同じ申し出に一度だけ応じる、§3）。
- 再生の開始（main thread）はキャッシュから読んだバイトで、転送後と同じ `AudioManager.playAudio` を呼ぶ。位置の補正は既存のまま
  （`receivedAtMillis` = 予告が届いた時刻から数える）。

## 3. サーバー

- **hash は保存 id（UUID）で覚える。** `AudioStorage.save` は毎回新しい UUID に一度だけ書くので、id の内容は変わらない。10 MB の
  sha256 を配送のたびに server thread で計算しない。
- **申し出（offer）を player ごとに持つ**: (player, pos, playbackId) → 送るべき音声の出どころ。`have = false` が来たら送り、申し出を
  消す。`have = true` なら申し出は「遅れた false を一度だけ受ける」状態で残すが、それは **10 秒**だけ（遅れた false はキャッシュの
  file の照合に 1 回失敗したときだけ来る — 途中参加ごとに読み込んだ最大 10 MB を、要らないと答えた人のために 1 分持たない）。
  答えの無い申し出は 60 秒で失効する。
- **申し出の無い `have = false` は無視する**（任意の音声を何度でも送らせる増幅を許さない）。
- 対象は `sendChunked` の 4 つの呼び出し元すべて: `PlaybackDelivery.start`・`PlaybackDelivery.deliver`・`HandyTestPlayback`・
  `RecordingDeviceData`。
- 既存の信号 `SAS-Delivery` に答えの行 `answered pos=… id=… to=… cache=hit|miss` を足し、実機の検査が「転送したか」を読めるように
  する（`sent` の行は予告を送った印として形を変えない）。

## 4. client のキャッシュ

- 場所: `<gamedir>/spatialaudiosystem/audio-cache/<sha256 hex>.bin`。書き込みは一時 file から rename（途中で落ちても壊れた
  file を「持っている」と答えない）。
- 上限: 合計 **256 MB**。超えたら最終使用（file の更新時刻、命中のたびに触る）の古い順に消す。1 file の上限はサーバーと同じ
  `AudioStorage.MAX_AUDIO_SIZE`（10 MB）。
- 書くのは**転送で受け取って sha256 が予告と一致したもの**だけ。一致しなければキャッシュには書かない（再生はする — 転送で
  届いたバイトはサーバーが送ったものそのもので、名前だけが合わない）。
- キャッシュから鳴らすときも、転送と**同じ受け取りの session** を満たして同じ完了の経路で鳴らす（オフセット・到着の時刻・
  エンドレスの扱いが転送と同じになる）。読み込みと書き込みは専用の worker 1 本（network thread でも main thread でもない）。
- 消すのは自分のキャッシュの dir の中だけ。名前が 64 桁の hex + `.bin` でない file には触らない。
- **限界（意図して残す）**: キャッシュはサーバーをまたいで 1 つ（game dir ごと）。だからサーバーは、ある音声の hash を予告して
  have/need を見れば、この client がその音声を（どのサーバーでであれ）受け取ったことがあるかを知りうる。サーバーごとに分ければ
  消えるが、別のサーバーの同じ音声で命中しなくなる。今は分けない（二次読みの注記、2026-10-02）。

## 5. 版の混在

- `ModNetworking` の版を `1.7` → `1.8`。古い client は接続の時点で版の不一致として断られる（ペイロードの解読失敗で落ちるより先に）。

## 6. 検査（赤にできる形で）

- 単体: キャッシュ `AudioCacheTest`（SAS-CACHE-001: 書いて読める・中身の壊れた file は読みで消える・hash の合わないバイトは書かない・
  上限で最も古く使われたものから消え、命中は使用に数える・自分の名前でない file に触らない・形の悪い hash は例外でなく「無い」）、
  サーバーの申し出 `AudioOffersTest`（SAS-NET-009: need で一度だけ送る・have で送らない・have の後の遅れた need で一度だけ・have の後は
  10 秒の猶予だけ・申し出の無い答え・失効・player ごと）、受け取り `ClientDownloadHandoffTest`（SAS-NET-008: hash が wire を往復する・32 byte でない hash は
  作れない・キャッシュのバイトが待っている session を満たし転送と同じものを渡す・置き換わった音や長さ違いは満たさない・転送が始まった
  session を上書きしない・handler の順序「session を積む → 答える（handler 自身の文として。積んだ仕事の中ではない）→ 読む」・
  network thread での登録と版 1.8）。session を先に積むのは、need が呼ぶ chunk も have が読むバイトも同じ main thread の列を通るので、
  どちらも満たす相手より先に着けないようにするため。
- 実機（VM-A、AOS-1 = hololocheck・AOS-2 = hololosub）: A が再生 → B が途中参加（初回 = `cache=miss`、転送あり）→ B が再ログイン →
  2 回目は `cache=hit`・chunk の転送なし・無音がほぼ 0（`SAS-Delivery` の `sent` と `answered` の行、`SAS-CatchUp` の `applied` の行の
  時刻差で測る）。`answered` が `sent` から 1 往復で来ること（B の main thread が地形を読んでいる間でも）が network thread で答えている証拠。

## 7. 実測（2026-10-02 15:2x、VM-A、AOS-1 = hololocheck・AOS-2 = hololosub、5,531,112 byte の MP3、LAN）

遅れ = client の補正の報告 `usedMs` − サーバーが送った `offset` = **予告を受けてから最初の音が出るまで**（client の時計、ms）。

| 場面 | miss（転送あり） | hit（転送なし） |
|---|---|---|
| 参加直後（A が鳴らし直してから B が参加、offset 38.1 s / 37.2 s） | 3,481 ms | 3,839 ms |
| 範囲に歩いて入る（B は既にワールドにいる、offset 9.2 s / 9.4 s） | 370 ms | **55 ms** |

- **命中は 3 回とも転送なし**（`answered … cache=hit`、B の 2 回目の参加・上の 2 つの hit）。`answered` はどれも `sent` と同じ秒 —
  参加直後（地形の読み込み中）でも network thread から答えている。A が最初に鳴らした回は A 自身が `cache=miss` → 1 秒後に再生。
- **範囲に入る場面では、キャッシュが転送を経路から外した**（370 → 55 ms）。
- **参加直後は短くならなかった**（3.5 s 前後、1 回ずつで差は誤差の内）。参加した client の main thread は地形の読み込みで止まっており、
  LAN では 5.5 MB の転送がその間に並行して終わる — 無音は転送ではなく停止で決まる（推論: 停止そのものは測っていない）。
  **§1 の前提「無音 12 秒 = 転送時間」（09-02）は、今日の LAN では成り立たない**（転送を含めて 3.5 秒）。遅い回線では転送が停止より
  長くなり、参加直後にも効くはず（推論、回線を絞る手段が無く未測定）。帯域は命中のたびに 1 人 1 回 5.5 MB 減る。
- 予測（script の頭）の照合: 「2 回目の参加は転送ぶん短い」は**反証**（参加直後は停止が支配）。条件をそろえた「範囲に入る」の
  予測「miss > hit を 0.2 s 以上」は当たった。

## 変更履歴

- v1（2026-10-02）: 初版。§7 の実測と、§1 の前提（無音 = 転送時間）が LAN の参加直後には当たらないことを同じ版の中で追記
  （公開前のため版を分けない）。
