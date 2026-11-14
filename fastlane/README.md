# F-Droid 用メタデータ

`metadata/android/` は Android アプリ「もしもの防災」（`dev.jisaku.mosimo`）の F-Droid 向け掲載情報です。F-Droid はリポジトリ直下のこのディレクトリを読みます。

- Google Play の掲載情報ではありません。Play へのアップロードは `apps/android/fastlane/` 側で行い、こちらは読み込みません。
- リリースごとに `apps/android/app/build.gradle.kts` の `versionCode` と同じ番号で `*/changelogs/<versionCode>.txt` を追加します（500 文字以内）。
- 公開の手順は `apps/android/DEVELOPMENT.md` の「F-Droid・GitHub Releases への公開」を参照してください。
