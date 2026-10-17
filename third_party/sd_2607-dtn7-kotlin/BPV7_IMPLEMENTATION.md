# BPv7 opaque-relay fixes and implementation status

This records the fixes following [the audit](BPV7_AUDIT.md) and the comparison
with `dtn7-rs`. The audit describes the original commit
`7f188ad398940bc1a5b0392f4e4eafa96fe00ff3`, not the corrected working tree.
No dependency upgrades, database schema changes, commits, or pushes are part of these fixes.

## Implemented scope

Ordinary, unregistered extension blocks can now survive decoding, validation,
storage, deferred forwarding, and serialization without interpreting their contents.
This is **not** a claim of complete RFC 9171/9172/9173 conformance.
In particular, unknown BPSec contexts are still not transparently relayed.

| Area | Change |
| --- | --- |
| Opaque data | An unregistered block's `BlobBlockData` is written as a byte string, not rejected or interpreted as nested CBOR. |
| Block flags | Use the RFC masks: report `0x02`, delete bundle `0x04`, discard block `0x10`. Deletion takes precedence over removal. |
| Input order | Decoding retains order, numbers, and duplicates. It does not sort or silently remove malformed duplicates. |
| Output insertion | Generated extensions use an unused number (prefer above the maximum; use a hole if the maximum supported number is occupied) and are inserted before payload, without renumbering or sorting existing blocks. |
| CRC | Check the received block's bytes, zeroing only the CRC value bytes, including any array break in the computation. |
| Encoding preservation | Retain the primary's received encoding while its fields remain unchanged. Retain a known extension's original data encoding while its decoded data remain unchanged. |
| Input validation | Reject floating-point/negative unsigned headers, unsupported CRC codes, bad CRC lengths, chunked block-data byte strings, invalid payload structure, duplicate standard extensions, and invalid hop limits. |
| Numeric encoding | Preserve all 64 control-flag bits. Encode legacy two-component ipn EIDs with uint64 node/service values, without CBOR bignum tags. |
| EID | Read the ipn SSP array and preserve percent-encoded dtn SSPs. |
| Reports | Generation is disabled by default. Honor the subject's status-time request; support `[true]` and `[true, 0]` without losing the assertion. |
| Residence time | Preserve the stored reception baseline. Update age/hop/previous-node on a separate send-attempt copy, preventing retry double-counting. |
| Expiration | Use reception time plus remaining lifetime for zero-timestamp stored bundles, including memory GC and SQL deadlines. |
| Stream handling | Use a bounded recording stream for wire CRC checking and a clean-EOF API for concatenated bundles. |

### Why both raw and decoded data are retained

For a known extension, different permitted inner CBOR encodings can represent
the same value. Replacing the received bytes unconditionally would change the
block-type-specific data, potentially breaking integrity protection.
The serializer compares the current decoded data's encoding to the snapshot
taken at decode time. It reuses received data only if unchanged; an explicit
data update is serialized afresh and gets a new CRC.
Header changes still get a newly computed canonical-block CRC.

The primary uses a snapshot of its fields for the same reason. This preserves
received bytes during normal forwarding without silently ignoring public mutable
field updates. The library does not enforce a fully immutable public primary API.

## Migration and compatibility

### Corrected block flags are a wire-format change

Older Kotlin releases incorrectly used `0x02` for discard, `0x04` for report,
and `0x08` for delete. Those values are **not auto-detected**: the same bit string
cannot safely mean both the old and standard operation.
Existing peers and stored bundles using the old meanings need a coordinated
upgrade or an explicitly versioned conversion at the deployment boundary.
Do not blindly rewrite bytes of signed bundles.
Old SQL/Room rows for zero-timestamp bundles may also contain an incorrectly
calculated expiration column. Recompute/reinsert those rows under a controlled
migration before enabling GC on an upgraded deployment; this change does not
automatically migrate existing databases.

`BlockIsEncrypted` is deprecated, not used as an encryption indicator.
Its bit is ignored by normal BP processing. The old misleading `Reserved4`
enum entry was replaced by `Reserved3`; code referring to it must be updated.

### Streaming parsers

For one in-memory bundle, use `cborUnmarshalBundle(bytes)`.
For one stream bundle, `cborUnmarshalBundle(input)` closes the parser/input.
For concatenated stream bundles:

```kotlin
createBpv7Parser(input).use { parser ->
    while (true) {
        val bundle = parser.readBundleOrNull() ?: break
        // Pass this bundle to the BPA.
    }
}
```

Do **not** construct a plain `CBORFactory().createParser(input)` and then call
the BP extension methods. Jackson read-ahead alone does not expose the original
consumed bytes needed for wire CRC validation; use `createBpv7Parser`.
Plain Jackson parsers over byte arrays retain a source array and remain usable.
The HTTP client/server and `bputil show` use the recording parser.

The stream parser defaults to a **64 MiB per-block local limit**, not a protocol
limit. Pass `maxBlockBytes` to change this policy. Completed blocks are released;
Jackson's read-ahead for later blocks is retained. In-memory byte-array decoding
does not add this stream limit; the caller already owns that input allocation.
Block CRC helpers that re-encode an object are deprecated and are no longer used
to validate received data.

### Reports

Enable only when the deployment can tolerate the extra traffic:

```kotlin
node.applicationAgent.administrativeAgent.generateStatusReports = true
```

The default administrative agent also suppresses reports for anonymous subjects,
administrative-record subjects, and null report-to EIDs.
Custom administrative agents remain responsible for their own reporting policy.
Status assertions at time zero, or without a timestamp, are represented by
`StatusReport.assertedStatuses`; use `assert(...)` to set or clear an assertion.

### Time and transmission views

For stored bundles use `BundleDescriptor.expireAt()` / `isExpired(now)`, not a
zero-timestamp bundle's age alone. `created` is the reception baseline persisted
by existing stores. No extra DB column is needed.
Forwarding callbacks receive a transmission view, not the original stored mutable
bundle. Report the transmission result through the existing return value; do not
rely on send-attempt mutations appearing in the storage baseline.

The current persisted time basis is wall-clock time in DTN milliseconds. Clock
rollback is clamped for age updates, but a full monotonic-clock/restart reconciliation
policy is not implemented.

### Supported numeric ranges

Control flags and two-component ipn numbers support uint64.
Block type/number remain positive signed `Int`; age, lifetime, timestamps and
sequence values remain nonnegative signed `Long`. Out-of-range incoming values
are rejected rather than truncated. Extending these public types to all of
uint64 remains a separate API migration.

`Bpv7CborGenerator` isolates a Jackson 2.14 limitation: its ordinary
`writeNumber(BigInteger)` emits a bignum tag instead of uint64 major type 0.
The adapter uses Jackson's value-count bookkeeping and writes the uint64 bytes
only for values above `Long.MAX_VALUE`; no reflection or new dependency is used.
Recheck this adapter on Jackson upgrades.

## Evidence and regression tests

Validation used Amazon Corretto 11 on macOS. The system JDK 21 is too new for
the project's Gradle 7.4 runtime; the earlier Windows runs used JDK 17.
No toolchain version in the repository was changed.

```sh
JAVA_HOME=/path/to/jdk-11 ./gradlew \
  :bpv7:test :agent:test :aa:test :cla:test :storage-sqldb:test \
  :bputil:build :bputil:installDist \
  --rerun-tasks -Pkotlin.incremental=false --no-daemon --console=plain
```

Result: **76 tests passed**, zero failures/errors/skips:
`bpv7` 33, `agent` 26, `aa` 1, `cla` 2, SQL store 14.
`bputil` builds successfully; it has no unit tests.
Incremental compilation was disabled for this verification after a corrupted
Kotlin incremental cache during development; this is a command-line option only.

New tests cover:

- `BundleEncodeTest`: unregistered block CRC0/16/32 fixed vectors and invalid data representations.
- `WireFormatTest`: received indefinite-array CRCs, encoding preservation after/no mutation,
  concatenation/read-ahead, size policy, malformed CRC/data, and a fixed Rust-generated
  bundle with rejection at every truncation offset.
- `BundleValidationTest`: structural constraints, duplicate preservation before validation,
  unsigned headers, high flag bits, ipn uint64, percent-encoded dtn EIDs.
- `UnknownBlockRelayTest`: actual byte serialization of forwarded blocks, RFC literal masks,
  multiple discards, low unregistered type codes, delete/discard precedence, and reports on/off.
- `ForwardingRetryTest`: retry baseline, hop count, age, stored deadlines and expiration.
- `StoreCarryForwardHttpTest`: defer a real BPA's unknown-block bundle, resume through
  a loopback HTTP CLA, and deliver at a second real BPA. Check payload/opaque bytes,
  number/flags, primary bytes, hop count, and successful removal from pending storage.
- `StorageSqlTest`: insert into a file-backed SQLite DB, close/reopen the driver,
  verify encoded bundle and reception deadline, then GC.

### Rust comparison and exercised interoperability

Compared with `dtn7-rs`
[`4daf02d7`](https://github.com/dtn7/dtn7-rs/tree/4daf02d7ea927e9293753b2a5c4497457f6e5a40),
whose lockfile selects `bp7 0.10.7`.
That crate's source corresponds to
[`bp7-rs 93b0ce0f`](https://github.com/dtn7/bp7-rs/tree/93b0ce0f55829cf12cc4462b28b458ead0af1249).

Useful reference patterns were its raw `CanonicalData::Unknown` serialization,
explicit report setting, reception-order parsing, and maximum-based numbering.
The fixes do **not** copy Rust's receive-loop index removal, numeric
`block_type < 11` test, re-encoding-based CRC verification, global insertion sort,
or millisecond/microsecond age comparison.

Executed a scratch cross-language probe using published `bp7 = 0.10.7`:

1. Rust generated a bundle with creation time 0, age 100, sequence 42,
   an opaque type-200 block containing `ff0001`, payload `2a`, and CRC32C on every block.
2. Kotlin decoded it, ran `checkValid()`, and emitted **identical bundle bytes**.
3. Rust decoded Kotlin's bytes and explicitly verified all CRCs plus the opaque
   block's number/data and payload.
4. Built `bputil show --valid` accepted the Rust bundle.

The generated fixture is retained in `WireFormatTest`; Rust/Cargo are not
required to run the Kotlin suite. This is a codec interoperability check,
not a full dtn7-rs daemon or TCPCL interoperability test.

## Remaining work and boundaries

The following audit findings are **not fixed by this delivery**:

1. **Standard BPSec:** existing Ed25519 context ID 1 conflicts with standard
   BIB-HMAC-SHA2. The legacy ASB format, unknown-context relay, encrypted known
   extension handling, standard HMAC/AES-GCM services, keys and security roles/policy
   need a separate coherent change. Do not use the experimental security code as
   an RFC 9172/9173 implementation or infer authentication from opaque forwarding.
2. **Fragmentation/reassembly:** overlapping extents, fragment identifiers,
   retaining incomplete reassembly, and security interactions need further work.
   Fragment status-report lengths now use the subject payload length, but this
   does not fix the remaining fragment identity/reassembly issues.
3. **RFC 9758/9713:** three-component ipn, allocator/equivalence/null/local-node
   semantics and updated administrative-record handling are not implemented.
4. **TCPCL and deployment validation:** no new TCPCL implementation, congestion
   control, Android/Room runtime verification, or deployed multi-host test.
5. **Receive failure reporting:** malformed byte streams/CRC failures are rejected
   before delivery, and HTTP parse errors are logged, but parse-time failures do
   not yet carry recovered bundle metadata into a complete BPA deletion-report path.
6. **Full input/API conformance:** not every RFC MUST/SHOULD, numeric uint64 field,
   extension processing hook, clock policy, or immutable-primary API has been implemented.

These are follow-up milestones, not tests claimed to have passed. The immediate
release scope is ordinary opaque extension relay and the tested base-protocol fixes above.
