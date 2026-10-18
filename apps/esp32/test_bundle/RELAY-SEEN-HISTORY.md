# Relay: durable received-ID suppression

## Bug and minimal change

The sixteen Bundle slots were also the only cross-session received-ID memory.
`completed_` prevented replay only within one connection and was cleared on
reconnect. A new peer could therefore offer an evicted old ID, trigger a new
WANT/save/preview/speech callback, evict another ID, and repeat the entire backlog.

A new regression first failed on `e.ids()==1`: after seventeen accepted events,
the next peer caused WANTs for both evicted ID 0 and genuinely new ID 17. It now
requests only ID 17, including after constructing a rebooted Relay.

Changes stay in `MeshRelay.h/.cpp`, `relay_host.cpp`, `test_relay.py`, and this file.
Speech/main/protocol/Android sources, existing README/audit files, phone/COM3 state
and Git commits were not changed by this work.

## Persistence and custody are different

- The complete signed Bundle remains in `/mesh/00` through `/mesh/15`.
- Immediately **before replacing a verified victim**, append its full event ID to
  `/mesh-seen`, flush/close, then read it back exactly. If remembering fails, do
  not replace the victim or ACK/callback the incoming event.
- Replace a verified victim using LittleFS's replacing `rename`, not
  remove-then-rename. A failed rename leaves the old complete Bundle available.
  Failed final readback still receives no ACK/callback.
- `find(id)` protects currently cached IDs; the journal protects previously
  evicted IDs. Together they suppress repeat WANTs across peers and reboot.
- History alone **never** advertises inventory, forwards a body, sends a custody
  ACK, or invokes the received-message callback. Those require the actual Bundle.
- Retained live Bundles continue advertising/forwarding with the original event
  and normal hop/age updates, even when the journal is full or a runtime history
  error has stopped acquisition. No relay protocol or signed field changes.

The existing reboot policy is unchanged: restored Bundles are cold because no
trusted downtime is available, so they are not forwarded with a fabricated new
residence timestamp. Seen history does not revive them.

## Hard bounds and explicit failure policy

Format: eight bytes `MSEEN1\r\n`, then up to **1,024** records. Each record is the
32-byte full Nostr event ID plus a four-byte big-endian CRC-32C of that ID.

- Journal maximum: **36,872 bytes**, outside `/mesh`'s sixteen-file budget.
- RAM index: at most **8,192 bytes** plus vector bookkeeping. Only eight-byte ID
  prefixes are indexed; every prefix hit reads and confirms the full 256-bit ID
  and checksum from disk. Prefix collisions cannot suppress an unrelated event.
- Existing Bundle budget remains 384,000 bytes plus one 24,000-byte temporary
  Bundle. Including a full journal, the logical peak is **444,872 bytes**, not an
  additional Bundle slot. No new partition, dependency or NVS storage.
- Lookup scans a bounded 1,024-entry prefix index; disk confirmation reads one
  36-byte record per matching prefix. One journal append/flush/readback occurs
  per newly evicted ID, not per offered inventory ID.

**The journal saturates; it does not rotate or silently forget IDs.** Once its
1,024 eviction records and sixteen Bundle slots are full, new unknown events are
not requested. Existing-body forwarding remains possible. Capacity is reserved
for outstanding WANTs, so they cannot overrun the history bound. The last safe
replacement can still be saved and ACKed. Logs explicitly report
`seen history full; no new requests when cache full`.

This deliberately trades unlimited new acquisition for reliable dedup within a
finite device budget: approximately **1,040 distinct received IDs** can be covered
by a fresh history + full cache. Increasing capacity or explicitly retiring
history requires an operator policy and RAM/storage assessment; no automatic
expiry is inferred from an untrusted wall clock. Do not expect unlimited lifetime
history from bounded storage.

Malformed/truncated/oversized/checksum-invalid history is retained and makes
`begin()` fail rather than treating it as empty. A runtime history lookup/write
failure disconnects the current exchange, sends no acceptance ACK for the failed
acquisition, and disables further acquisition in that boot; already-retained live
Bundles can still forward on a subsequent exchange. Diagnostics identify the
history stage. There is no automatic repair/format/truncate/delete of history.

Checks exercise Arduino FS's flush/close/rename/readback boundary. Actual sudden
power-loss ordering/durability is **not hardware-tested**; CRC detects malformed
records, not malicious local filesystem edits or proof of author identity.

## Prefer new inventory candidates

Current Android `MeshEngine` uses a linked insertion-order map, and `inventory()`
preserves it: oldest accepted first, newest last. Relay now selects unknown IDs
from the **tail first** for its bounded WANT batch. A changed inventory replans
only unsent requests; already-issued requests are not cancelled or misidentified.
This is a local scheduling heuristic, not an assertion that inventory order is
cryptographically trustworthy or that it equals author creation time. The sender
may still choose its own order inside a requested batch.

## Upgrade / handoff caveats

- First upgrade needs no formatting or migration: existing sixteen verified
  Bundles already protect their IDs. Their IDs are journaled before later eviction.
- IDs evicted **before** this software existed cannot be reconstructed from the
  current cache. Old uncached events may be received once on first catch-up;
  subsequent eviction/reconnect/reboot is then covered. No historical knowledge
  is invented from matching text.
- Back up/restore the entire message filesystem, including root `/mesh-seen`,
  not only the `/mesh` directory. Erasing or intentionally discarding history
  removes suppression guarantees for IDs whose bodies have also gone.
- No hardware, ADB, COM3, flash, reset or filesystem-format operation was performed.
  Coordinator owns deployment and physical reception/speech/forwarding checks.

## Validation

Red before fix (real Relay + codec + ArduinoJson, fake LittleFS/clock):

```text
FAIL 59: e.ids()==1
```

Final full suite with ASan/UBSan: **4,326 assertions PASS**. Coverage includes:

- 17th receipt / cache eviction, different peer, reconnect, reboot, no old replay;
- newest-tail request selection and replacing backpressured unsent plans;
- retained-body forwarding and no history-only custody ACK/callback;
- journal open/write/flush/close/read/seek/corrupt-read failures before eviction;
- prefix collision with different complete IDs, missing/unreadable lookup data;
- malformed/truncated/oversized/checksum-invalid startup history retained intact;
- reaching the history cap without rotation and still forwarding retained data;
- old Bundle survives failed atomic replacement; invalid signatures and storage
  faults never falsely ACK; original Relay limits and forwarding tests retained.

Fast focused check from repo root, Windows sh with the documented WSL cache:

```sh
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu -- env PYTHONPATH=/home/mchiba/.cache/mesh-bundle-host/deps/usr/lib/python3/dist-packages python3 apps/esp32/test_bundle/test_relay.py --dedup-only --mbedtls-include /home/mchiba/.cache/mesh-bundle-host/deps/usr/include --mbedtls-library /home/mchiba/.cache/mesh-bundle-host/deps/usr/lib/x86_64-linux-gnu/libmbedcrypto.a
```

Omit `--dedup-only` for the full suite. Both generate only public test-key fixtures
and temporary host build outputs; they do not access hardware.

Normal `uv run pio run -e m5stack-basic` also **PASS** on the shared worktree's
current source snapshot: application **1,218,205 / 1,310,720 bytes (92.9%)**,
static RAM **48,392 / 327,680 bytes**. The journal index is heap, not included in
that static RAM figure. Speech is being edited by its separate owner, so these
whole-firmware numbers are a snapshot, not an isolated size delta for Relay.
