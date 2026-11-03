# 0.2.0

Android 15向け、versionCode 2。前版と同じデバッグ署名キーを使用。

追加: フラッシュON/OFF、三分割グリッド、重力/加速度センサーによる水平器、長押し連写、連打の待ち行列、タップAF/AE、上下ドラッグの露出補正。
削除: プロファイルの見出し、撮影画像を確認するボタン、元映像との比較ボタン。

RAW優先・JPEGフォールバックと固定ルックは維持。連写は1枚ずつ現像・保存。

ビルド: tools/build-cached-apk.py。SDKのJavaコンパイラ・D8・AAPT2・zipalign・apksignerでAPK生成完了。既存のGradleキャッシュを使用し通信は行っていません。ビルドログはbuild/android-v02-build.log。

ユーザーの指定により、この版のテスト・lint・実機動作検証は実施していません。docs/validation.mdは0.1.0の記録です。
