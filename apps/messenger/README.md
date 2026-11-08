# もしもトーク / Mosimo Talk (code name Hanashi) — Mosimo DM demo (Android)

An everyday-style messenger that always talks [Mosimo](../../protocol/README.md): DMs travel phone to phone over the
BLE/LAN mesh, relayed by any Mosimo node (including the `apps/android` app). No server, no login, no internet.
Wire: [protocol/0022](../../protocol/0022-direct-messages.md).

- `:mosimo-dm` — the SDK: NIP-04 DMs, delivery receipts, profiles, delivery status (pure JVM, unit-tested).
- `:app` — the UI (`dev.jisaku.hanashi`), gluing `:mosimo-dm` to `MeshCoordinator`.
- `:bundle-core`, `:mesh-android` are used in place from `../android`.

```sh
mise run test   # SDK unit tests
mise run apk    # app/build/outputs/apk/debug/app-debug.apk
```

Demo: install on two or three phones (one Mosimo app per phone for BLE; on one phone apps still meet over LAN).
`./gradlew :app:assembleDebug -PhanashiSuffix=b` builds a second identity (`dev.jisaku.hanashi.b`) for single-phone testing.
