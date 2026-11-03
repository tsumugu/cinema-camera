# 検証結果と未確認事項

2026-11-02に`sh tools/check-host.sh`を実行。2026-11-03（日本時間）にAndroid SDK Platform 36、Build Tools 35.0.0、Gradle 8.13、AGP 8.13.2、JDK 21を用意し、`./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:lintDebug`が成功しました。

## 成功した確認

- JDK 21で、Androidに依存しないRAW現像・画像配置・粒子生成のクラスをコンパイル。
- 既知の合成RAW16に対し、行/画素ストライドとバッファ先頭位置を変更しても、期待するRGBを生成。
- RGGB/GRBG/GBRG/BGGRの4配列、黒レベル、WBゲイン、2つの緑の平均、非対称な色行列、レンズシェーディング。
- RAWの回転と、8bitより細かな入力差を浮動小数点出力に残すこと。
- 欠落・不正な補正情報、未知の配列、短いバッファを拒否。
- Javaアプリソース11ファイルの構文解析。
- XMLの構文解析。
- 同じGLSLをサーフェスレスEGL/GLESでコンパイルし、実描画して確認：RGBA順序、JPEG用読み戻しの上下方向、比較モード、階調の単調性・連続性、暗部の分離、黒白の端点、暗部への青み、粒子の強さと時間的安定性。
- 浮動小数点テクスチャからのRAWベース描画と、1.0を超える明部情報を肩で扱えること。
- Android APIに対するコンパイル、DEX生成、デバッグAPKの組み立て。
- Android lint：エラー0件、警告8件。対象APIの古さ（Android 15指定）、更新可能な依存関係、縦画面固定、非推奨API、バックアップ設定、未指定のアプリアイコン、XML用Viewコンストラクタに関する警告。
- APK Signature Scheme v2で署名検証成功。minSdk 29、targetSdk 35、compileSdk 36。
- APKのZIP整合性、同梱GLSLとソースの一致、arm64-v8aを含むネイティブライブラリ。

シェーダー側の詳細は`shader-validation.json`。これはデスクトップのGLES実行結果であり、moto g66jのGPUでの結果ではありません。

## 未確認

- CameraXのRAW+JPEG+ImageAnalysis同時セッションが対象実機で構成できるか。
- RAW申告能力、実際のRAW取得、撮影時刻とCaptureResultの照合、メーカーの補正情報の妥当性。
- RAW/JPEGの到着順序、部分失敗、タイムアウト、JPEG代替、再撮影、アプリ中断/復帰を含む結合動作。
- Android上の浮動小数点テクスチャと保存処理。
- 生成基準画像との視覚的な一致、実写の肌色、暗部ノイズ、明部の色と階調、速度、発熱、メモリ使用量。

配布できるデバッグAPKは生成済みです。「moto g66j 5Gで実機動作確認済み」とは扱わないでください。
