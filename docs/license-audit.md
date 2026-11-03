# ライセンス確認（2026-11-03）

v0.6.0 APKに対して、AGPの解決済みpackage依存モデル42件、Maven POM（親POMを含む）、AAR/JAR内のLICENSE/NOTICE、APK内のMETA-INFを確認した。ビルドツール専用の依存とAPK同梱の依存は区別した。依存追加・更新時は再確認が必要。

## 結果と配布義務

- AndroidX、Kotlin標準ライブラリ、Coroutines、AtomicFU、Dagger、Guava ListenableFuture、Inject等はApache-2.0。ListenableFutureは親POM、Auto Valueとjavax.injectは上流ソースのヘッダーも確認した。
- CameraX camera-coreは、Apache-2.0の本体に加えて内部libyuvがBSD-3-Clause。両方の条件を守る必要があり、どちらか一方を選べる意味ではない。
- この42件の宣言ではGPL/LGPL等のソース提供義務を課すライセンスは見つかっていない。
- Apache-2.0の第4条はライセンスのコピー提供を要求し、NOTICEが付属するものは該当する告知も保持する。変更した第三者ファイルを配る場合の変更表示等も条件に含む。
- libyuvのBSD条件は、バイナリ配布でも著作権表示、条件文、免責文を付属文書その他の配布物に含めることを要求する。推薦・宣伝に権利者名を使う権利は与えられない。
- これらは専用画面や撮影画面への常時表示を一律に求める条件ではない。読めるライセンス文・告知を配布物へ同梱する方式を採用した。
- 本体のMITは第三者のライセンスを置き換えない。

## 既存APKと今回の対応

v0.6.0 APKにはJakartaのMETA-INF/NOTICE.mdがあるが、Apache-2.0の全文とlibyuvの告知は見当たらなかった。その状態だけで告知を満たしたとは扱わない。

assets/licensesへMIT、Apache-2.0全文、libyuv全文、Jakarta NOTICE、依存一覧を追加し、v0.6.1 APKにも同梱。ソース配布用の一覧はルートTHIRD_PARTY_NOTICES.md。アプリの撮影UIや処理は変更していない。

## 根拠

- Apache-2.0第4条: https://www.apache.org/licenses/LICENSE-2.0
- libyuv原文: https://chromium.googlesource.com/libyuv/libyuv/+/master/LICENSE
- libyuvミラー: https://github.com/lemenkov/libyuv/blob/main/LICENSE
- CameraXのlibyuv同梱: https://android.googlesource.com/platform/frameworks/support/+/androidx-main/external/libyuv/README.md
- javax.inject: https://javax-inject.github.io/javax-inject/
- Google Auto: https://github.com/google/auto

確認は依存宣言と配布物・上流告知に基づく。将来の依存更新や、未宣言の組込みコードまで包括して確認済みとするものではない。実機動作テストは実施していない。
