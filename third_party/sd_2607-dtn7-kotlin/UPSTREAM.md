# sd_2607-dtn7-kotlin snapshot

Source: https://github.com/jphacks/sd_2607-dtn7-kotlin.git
Commit: b3c329afe1079c54f746614d186ba8ab7e398782

Only `bpv7/src`, `LICENSE.txt`, `BPV7_AUDIT.md` and `BPV7_IMPLEMENTATION.md`
are vendored. Their contents are unchanged. `bpv7/build.gradle.kts` is this
application's build adapter, not the upstream build file.

Reason: the fork has no required prepublished artifact, and its original root
build configures unrelated Android/SQLDelight modules with an older toolchain.
Using the source snapshot avoids a JitPack build at install/build time. This is
not a separate BPv7 implementation. Update this snapshot deliberately, keeping
the commit and license here. The app's bounded preflight and relay policy live
in `apps/android/bundle-core`, not in the fork.

The adapter uses Jackson 2.21.3 and bcprov-jdk18on 1.83 instead of the fork's
Jackson 2.14.0 / bcprov-jdk15on 1.65. The fork's BPv7 regression tests are included
and run with the app build. No legacy BPSec signing, fragmentation or TCPCL is
used by this app; see upstream's implementation-status document for limits.
