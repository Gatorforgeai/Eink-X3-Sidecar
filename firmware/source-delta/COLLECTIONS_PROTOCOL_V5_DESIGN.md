# Sidecar protocol v5: independent stored collections

Status: design proposal; no production behavior changes in this document.

Implementation note (2026-09-28): the first bounded writable slice now parses durable create/full-
replace sessions and whole-page streams, enforces four completed pages per BLE connection, and
publishes verified manifests through an atomic root swap. Partial replacement, metadata, v5 display
ownership/navigation, and deferred cycle-boundary activation remain disabled pending bench proof.
New collections receive a bounded placeholder name (`Collection <id>`) until metadata opcode
`0x0A` is implemented. The working v4 catalog remains the displayed fallback in this slice.

Compression trial note (2026-09-28): capability bits 4 and 5 advertise raw-DEFLATE and the bounded
1-bit PNG profile below. Encoded bytes always land in an unpublished temporary file. Firmware
decodes into the inactive candidate page, verifies the exact raw-page CRC, and journals the page
only after closed-file verification. Format zero remains mandatory and is the compatibility
fallback. Hardware proof is deliberately bounded to one page per format before any larger
collection is attempted.

## Objective

Protocol v5 turns the current single 1–16 page catalog into a bounded set of independently
replaceable collections. Weather, markets, scores, news, astronomy, recipes, and similar modules
may each retain their own pages and refresh cadence. Updating one collection must not copy or
invalidate pages belonging to another collection.

The design preserves protocol v4 exactly. A v4 phone continues to see and update the existing
two-bank `SPG3` catalog. A v5 phone uses the collection store only after the X3 advertises v5.
Firmware must never combine v4 and v5 objects into one transaction.

## Fixed bounds

The first v5 implementation uses deliberately small fixed limits:

- 16 collection directory entries.
- 16 pages per collection.
- At most 64 published pages across all collections. The 2026-09-27 bench capacity measurement
  admits the full 64-page policy on this X3 storage profile.
- Four replacement pages per BLE transaction, matching the measured v4 connection window.
- 24 UTF-8 bytes for a display name, validated and stored by the phone; collection identity is a
  separate unsigned 32-bit ID.
- One full-screen 1-bpp framebuffer per page. On the X3 that is 52,272 bytes
  (`792 * 528 / 8`). Sixty-four pages require about 3.2 MiB, before atomic-update overhead.

These are firmware policy limits rather than an e-reader or filesystem limit. The INFO
characteristic advertises them so a phone does not infer them. Before staging, firmware checks
free space for the complete candidate collection plus manifests and a safety reserve. Storage
exhaustion rejects BEGIN and leaves all published content intact.

## Identity and ordering

`collectionId` is a stable, nonzero `u32` assigned by Android and retained across updates.
Names are labels, never keys. Renaming a collection does not change its ID.

The Android registry is resolved and canonical in
`android/app/src/main/java/com/gatorforge/sidecar/trackb/SidecarCollections.kt`.
It assigns persisted IDs `0x0001` through `0x0010` to all 16 current modules and records their
names, display order, and refresh classes. Its tests enforce uniqueness, stable planning, and the
four-page transaction bound. Existing IDs must never be renumbered; new modules append new IDs.

The root index defines display order and per-collection policy. Each entry contains:

- `collectionId: u32`
- `flags: u8` (`enabled`, `pinned`, remaining bits zero)
- `order: u8`
- `nameLength: u8` and `name[24]`
- `expectedGeneration: u32`

IDs must be unique, names must be valid UTF-8 without `/`, `\\`, control characters, or NUL, and
enabled entries must have unique order values. Unknown flag bits make an index invalid.

`expectedGeneration` binds the root entry to one exact collection publication. This prevents a
newer orphan manifest, left by a failed root update, from silently entering the rotation.

## On-disk layout

All v5 objects live under `/.crosspoint/sidecar_v5/`. V4 paths remain untouched.

```text
root_0.index
root_1.index
selection.bin
c_<8-hex-ID>_0.manifest
c_<8-hex-ID>_1.manifest
c_<8-hex-ID>_0_<page>.bin
c_<8-hex-ID>_1_<page>.bin
stage_0.session
stage_1.session
```

The collection ID is rendered as exactly eight uppercase hexadecimal digits. Paths are generated
by firmware into fixed buffers; names never appear in paths.

### Root index (`SCI5`, version 1)

The dual root files contain a header, 0–16 fixed-size entries, and CRC32 over every preceding
byte. Header fields are magic, version, root bank, entry count, selected collection fallback,
root generation, total published page count, and reserved zero bytes. Readers require the exact
file size implied by entry count, valid CRC, unique IDs/order, valid flags/names, and a total page
count no greater than the advertised limit.

The newest valid root is chosen using the existing wrap-safe signed generation comparison. Every
entry is then resolved only to the collection manifest whose generation equals
`expectedGeneration`. A root with any unresolved or invalid enabled entry is invalid as a whole.
This gives cold boot one all-or-nothing published view.

The root header is exactly 24 bytes: magic `[0..3]`, version `[4]`, bank `[5]`, entry count `[6]`,
reserved zero `[7]`, root generation `[8..11]`, selected collection ID `[12..15]`, total published
pages `[16..17]`, and six reserved zero bytes `[18..23]`. Each entry is exactly 35 bytes:
collection ID (4), flags (1), order (1), name length (1), zero-padded 24-byte name, and expected
generation (4). CRC32 (4) follows the final entry, so exact file size is `28 + 35 * entryCount`.

### Collection manifest (`SCM5`, version 1)

Each collection has two banks. A manifest contains magic, version, bank, page count, fallback
selected page, width, height, collection ID, collection generation, and 16 page CRC32 values,
followed by a manifest CRC32. Exact size, reserved-zero checks, geometry checks, page bounds, and
every referenced page file are verified before the manifest can be published or loaded.

The generation belongs to that collection, not the root. Therefore weather can advance many
times without changing sports or astronomy generations.

The manifest is exactly 92 bytes: magic (4), version (1), bank (1), page count (1), active page
(1), width (2), height (2), collection ID (4), generation (4), four reserved zero bytes, sixteen
page CRC32 values (unused slots must be zero), and record CRC32 (4).

### Selection record (`SCS5`, version 1)

Selection is advisory and contains root generation, collection ID, collection generation, page
index, rotation mode, and CRC32. It applies only when every identity/generation field matches the
published root and collection manifest. Otherwise the root and collection fallback selections
win. A corrupt or stale selection record can never make content publishable.

The selection is exactly 24 bytes: magic (4), version (1), rotation mode (1), page index (1),
reserved zero (1), root generation (4), collection ID (4), collection generation (4), and CRC32
(4).

### Staging session (`SCT5`, version 1)

V5 supports collections larger than four pages through one durable, resumable staging session.
There is exactly one device-wide session at a time. `stage_0.session` and `stage_1.session` are an
alternating journal; the valid record with the newer journal sequence wins. A record contains:

- random nonzero `sessionId: u64`
- `collectionId: u32`
- base root and base collection generations
- candidate collection generation
- resulting page count and candidate bank
- update mode (`create`, `full replace`, or `partial replace`)
- required-page bitmap and received-page bitmap (`u16` each)
- CRC32 for every resulting page
- session state (`receiving` or `ready`)
- journal sequence and record CRC32

The session record is written and read-verified after inactive-bank preparation and after every
verified page. Power loss may lose only the most recent journal update; on resume, firmware
reverifies candidate page files and reconstructs the received bitmap conservatively. A page is
never considered received from the bitmap alone.

The session has no time-based expiry because the X3 has no trustworthy always-on wall clock.
Only a matching explicit abort, successful commit, a physical recovery action, or a new-session
request that includes the existing session ID may reclaim it. An unrelated client cannot evict a
partially received transaction.

Each session journal is exactly 112 bytes: magic (4), version (1), journal bank (1), state (1),
mode (1), journal sequence (4), session ID (8), collection ID (4), base root generation (4), base
collection generation (4), candidate collection generation (4), resulting page count (1),
candidate bank (1), two reserved zero bytes, required bitmap (2), received bitmap (2), sixteen
page CRC32 values (unused slots zero), and record CRC32 (4).

## Atomicity and recovery

### Updating pages in an existing collection

1. Resolve the collection through the active root and exact expected generation.
2. Select the collection's inactive bank and remove its manifest first.
3. Reclaim only that inactive collection bank.
4. Copy unchanged pages from the active collection bank when the transaction replaces a subset.
5. Stream replacement pages, closing and CRC-verifying each persisted file.
6. Write and read-verify the candidate collection manifest last.
7. Build the candidate root in bounded RAM; its one changed entry points to the new collection
   generation. Preserve every other entry byte-for-byte except root generation and total pages.
8. Validate the serialized candidate root in RAM and reverify every referenced collection
   manifest/page. Do not write the root yet when activation is deferred.
9. At the configured cycle boundary, write and read-verify the candidate root in the inactive root
   bank. That durable root write is the publication point. Refresh the selection record, load its
   selected page, and acknowledge completion.

Until step 9, the old root refers only to the old collection generation. Power loss at any earlier
step restores the complete old view. Orphan candidate files are unpublished scratch and may be
reclaimed later. If the root write or readback fails, firmware removes or quarantines the candidate
root before rollback, using the recovery behavior already proven in v4. If power fails after the
complete root reached media but before presentation, cold boot loads the complete new view; the
display may switch later than planned, but never to a mixed catalog.

This design copies unchanged pages only inside the collection being updated. A two-page weather
update never copies markets, news, sports, or recipes.

### Creating, deleting, renaming, enabling, or reordering collections

These are root-index transactions. New collection content is fully committed first, then a new
root references it. Deletion removes only the root entry; garbage collection reclaims the now
unreferenced files after publication. Rename, enable, and reorder change only the candidate root.
No currently referenced file is deleted during a transaction.

### Interrupted BLE traffic

Disconnect, malformed packets, sequence gaps, CRC failure, timeout, or event-ring overflow abandon
the active staging transaction. The currently published root continues to rotate. A completely
verified deferred candidate may remain pending until the boundary, as v4 does today. Only one
foreground staging transaction or deferred candidate may exist at a time.

### Garbage collection

Boot and preflight garbage collection derive a live set from the selected valid root plus the
previous valid root needed for rollback. It may remove only unreferenced v5 manifests/page files.
It is bounded, resumable, and never treats a filename alone as proof that an object is live.

## Wire protocol

All integers are little-endian. Existing opcodes `0x01`–`0x08`, DATA (`0x02`), END (`0x03`),
sequencing, payload CRC behavior, and v4 status values remain unchanged.

### Capability advertisement

For protocol v5, INFO retains bytes 0–12 exactly as v4 and appends:

| Byte | Meaning |
|---:|---|
| 8–9 | protocol version = 5 |
| 10 | capability bits: bit 3 = collections, bit 4 = raw DEFLATE pages, bit 5 = bounded 1-bit PNG pages |
| 11 | legacy/v4 maximum catalog pages = 16 |
| 12 | maximum pages per BLE replacement transaction = 4 |
| 13 | maximum collections = 16 |
| 14 | maximum pages per collection = 16 |
| 15–16 | measured-safe maximum total published pages, capped at 64 |
| 17 | maximum collection name bytes = 24 |

Android must accept the existing 13-byte v4 INFO value and use v4. It may send v5 opcodes only
after reading version 5 and the collections capability.

### `BEGIN_COLLECTION_PAGE` (`0x09`)

One ATT write, exact length 32 bytes including opcode:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 1 | opcode `0x09` |
| 1 | 2 | x |
| 3 | 2 | y |
| 5 | 2 | width |
| 7 | 2 | height |
| 9 | 1 | format (`0` = raw 1-bpp, `1` = bounded 1-bit PNG, `2` = raw DEFLATE of packed 1-bpp) |
| 10 | 4 | data length |
| 14 | 8 | session ID |
| 22 | 1 | collection page index |
| 23 | 4 | page CRC32 |
| 27 | 4 | candidate collection generation |
| 31 | 1 | flags; version 1 requires zero |

Every page is a whole-panel frame and must belong to the current durable session. The page bit must
be set in the required bitmap, its CRC must match the session record, and the candidate generation
must match. Pages may arrive in any order and may be retransmitted; a retransmission is accepted
only when its CRC matches the session's immutable expected CRC. Firmware accepts at most four new
pages per BLE connection, then returns a reconnect-required result. That is a connection-lifetime
limit, not a collection-size limit.

The existing DATA and END packets carry the pixels. END closes and verifies one page, journals the
received bit, and returns `PAGE_STORED`; it never publishes a collection. Publication requires the
explicit commit below. This removes any dependence on BLE connection boundaries or page order.

### `BEGIN_COLLECTION_SESSION` (`0x0B`)

This control packet creates the durable session before any `0x09` page. Its exact length is
`30 + 4 * resultingPageCount` bytes:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 1 | opcode `0x0B` |
| 1 | 8 | session ID |
| 9 | 4 | collection ID |
| 13 | 4 | base root generation |
| 17 | 4 | base collection generation (`0` for create) |
| 21 | 1 | resulting page count |
| 22 | 1 | mode (`0` create, `1` full replace, `2` partial replace) |
| 23 | 2 | required-page bitmap |
| 25 | 1 | CRC-list byte length, exactly `4 * resultingPageCount` |
| 26 | variable | one little-endian CRC32 per resulting page |
| `26 + 4N` | 4 | packet CRC32 over every preceding byte |

The candidate collection generation is `1` for create and `baseCollectionGeneration + 1`
otherwise. Generation zero is reserved and wrap follows the same signed-comparison rule as the
existing manifest format.

For create/full replacement, every resulting page bit is required. For partial replacement, the
required bitmap identifies replacement pages and firmware clones only the other pages from the
published collection into the inactive collection bank. A shrink is a full replacement. Firmware
rejects a session when base generations are stale, IDs/counts/bitmaps disagree, another session
exists, capacity is insufficient, or the packet is not exact.

Android generates a cryptographically random nonzero 64-bit session ID. Retrying the identical
BEGIN is idempotent and returns the current received bitmap. Reusing that ID with different bytes
is rejected.

### `COMMIT_COLLECTION_SESSION` (`0x0C`) and `ABORT_COLLECTION_SESSION` (`0x0D`)

COMMIT is exactly 18 bytes: opcode `0x0C`, session ID (`u64`), candidate generation (`u32`),
desired collection fallback page (`u8`), and packet CRC32. It succeeds only after all required
bits are durably received and every resulting
page and the candidate manifest reverify. Firmware journals `ready`, builds and validates the new
root in RAM, and publishes it at the configured cycle boundary using the atomic root procedure.
Only durable root publication and presentation produce the collection-complete acknowledgement.

ABORT is exactly 13 bytes: opcode `0x0D`, session ID (`u64`), and packet CRC32. It removes the staging manifests/pages and
both session journal records only after confirming the active and previous roots do not reference
them. Disconnect does not abort. A new phone can read the pending-session inventory and explicitly
resume or abort it.

### `PUT_COLLECTION_META` (`0x0A`)

An exact, bounded single-write control packet changes collection name, enabled/pinned flags, and
order, or creates/deletes an empty root entry. It includes operation, collection ID, base root
generation, flags, order, name length/name, and packet CRC32. Delete requires name length zero.
The parser rejects it during any frame transaction or while a deferred candidate exists.

The implementation should add this opcode only after page updating is proven. In the first v5
slice, Android may use a fixed bootstrap root assembled from full collection creation operations.

### Status and conflict handling

V5 adds explicit terminal results: unsupported opcode/version, stale root generation, stale
collection generation, collection limit, total-page limit, insufficient storage, invalid metadata,
and durable publication failure. A stale-generation response tells Android to reread inventory and
rebase; firmware never guesses which side is newer.

### Inventory characteristic (`SIV5`, inventory version 1)

The characteristic UUID is `6e6b1a04-9f1d-4b7a-9c2e-1f0a2d3c4b5a`, the next free slot in the
existing Sidecar service family after RX (`...01`), INFO (`...02`), and STATUS (`...03`). It is
read-only.

The read-only inventory characteristic is one CRC-protected value with exact length
`56 + 12 * entryCount` bytes (56–248 bytes). Firmware constructs the whole value from one resolved
root/session snapshot and installs it as one characteristic value; normal ATT offset reads may then
retrieve it. Android concatenates offset-read fragments, checks the exact implied length and CRC,
and parses only after the complete value is present. Notification ordering is never used to assemble
inventory state.

The 52-byte header preceding entries is:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `SIV5` |
| 4 | 1 | inventory format version = 1 |
| 5 | 1 | entry count, 0–16 |
| 6 | 1 | inventory flags; bit 0 = durable session present, all other bits zero |
| 7 | 1 | reserved zero |
| 8 | 4 | active root generation |
| 12 | 4 | selected collection ID, zero only when the root is empty |
| 16 | 4 | selected collection generation, zero only when the root is empty |
| 20 | 1 | selected page index, zero when the root is empty |
| 21 | 2 | total published page count, 0–64 |
| 23 | 1 | reserved zero |
| 24 | 8 | durable session ID, or zero when absent |
| 32 | 4 | session collection ID, or zero when absent |
| 36 | 4 | session candidate collection generation, or zero when absent |
| 40 | 2 | session required-page bitmap, or zero when absent |
| 42 | 2 | session received-page bitmap, or zero when absent |
| 44 | 1 | session state (`0` receiving, `1` ready), or `0xFF` when absent |
| 45 | 1 | session mode (`0` create, `1` full replace, `2` partial replace), or `0xFF` when absent |
| 46 | 2 | reserved zero |
| 48 | 4 | session journal sequence, or zero when absent |

Each root-order entry is exactly 12 bytes: collection ID (`u32`), expected collection generation
(`u32`), page count (`u8`), root flags (`u8`), order (`u8`), and one reserved-zero byte. The entry
array starts at offset 52. CRC32 occupies the final four bytes and covers every preceding byte.
All integers are little-endian and all reserved bytes/bits must be zero.

An empty root uses generation zero, zero selection fields, zero pages, and zero entries. With no
durable session, bit 0 is clear, all numeric session fields are zero, and state/mode are `0xFF`.
When bit 0 is set, all session identity fields are nonzero where required by `SCT5`, state/mode are
in range, the received bitmap is a subset of the required bitmap, and the journal sequence is
nonzero. Entries must have unique nonzero IDs and unique order values; each page count is 1–16;
their page-count sum must equal the header total. The selected identity/generation/page must resolve
to an enabled entry and its manifest. Android treats any violation as an invalid snapshot and
rereads rather than attempting an update from partial information.

## Capacity measurement and admission control

The FreeInk storage layer already provides the correct read-only primitives:

- `Storage.sdTotalBytes()` returns `clusterCount * bytesPerCluster`, cached when the volume mounts.
- `Storage.sdUsedBytes()` calls `freeClusterCount()`, derives allocated bytes, and caches the scan
  for 20 seconds because walking the FAT is slow.
- Safe free bytes are `total - used` only when storage is ready, total is nonzero, used is no
  greater than total, and the free-cluster scan succeeded. The present API reports scan failure as
  zero used, which is ambiguous; v5 admission code must add a success-bearing query rather than
  treating zero as an empty card.

The read-only `CMD:STORAGE` diagnostic was installed and measured on the bench X3 on 2026-09-27.
Authoritative output from firmware SHA-256 beginning `0A930E` was:

```text
STORAGE_STATUS:ready=1,measured=1,total=31247564800,used=2293760,free=31245271040
```

That is 29.1016 GiB total and 29.0994 GiB free at measurement time. The policy reserve is 5% of
total because it exceeds 8 MiB: 1,562,378,240 bytes (1.4551 GiB). After subtracting the reserve,
29,682,892,800 bytes remain available for admission decisions. Sixty-four X3 frame files require
3,345,408 bytes; one complete 16-page inactive collection requires 836,352 bytes, before the small
root/session/manifest overhead. The measured device therefore admits the 64-page policy with more
nearly four orders of magnitude of storage margin. Capacity is no longer a v5 blocker.

Production v5 INFO may advertise 64 total published pages on this storage profile. Admission still
uses a fresh read-only used-space measurement and the reserve calculation below for every new
session, because books, caches, and other files can consume space after this benchmark.

Admission requires:

```text
freeBytes >= bytesNeededForCandidateCollection
             + root/session/manifest overhead
             + filesystem safety reserve
```

`bytesNeededForCandidateCollection` includes every file that will exist concurrently, including
cloned unchanged pages. The safety reserve is the greater of 8 MiB and 5% of measured total
capacity. This is intentionally much larger than metadata needs and protects the reader's normal
book/cache use.

## Display rotation and buttons

The visible sequence is the enabled collections in root order, with pages in ascending order.
Automatic rotation advances page, then collection, and wraps after the last enabled collection.
A one-page fixed collection remains visible for its normal dwell but does not trap rotation.

The actual X3 input layer supports `wasPressed`, `wasReleased`, `isPressed`, and one global
`getHeldTime()` for the most recent physical button. It has no per-button long-press event for the
Up/Down side buttons. Current v4 Sidecar navigation acts on Up/Down `wasPressed` immediately, so a
long-Up/Down collection gesture would require delaying every existing page turn and risks firing a
short and long action from one hold. The four front buttons already expose separately mapped
logical Left/Right events, and normal reader activities do not run while Sidecar owns the screen.

Resolved button behavior in v5 Sidecar ownership mode:

- Short Up/Down: previous/next page in the flattened visible sequence, preserving v4 behavior.
- Short logical Left/Right: previous/next enabled collection, landing on that collection's last durable page
  (or page zero if no matching selection exists).
- Back exits Sidecar ownership when that behavior is implemented; Confirm remains reserved.
- Power retains its existing sleep/refresh behavior.

Every successful manual move updates `SCS5`. Button input during staging navigates the published
root. At activation, selection is carried forward by collection ID and clamped to the new page
count; if that collection was removed/disabled, choose the next enabled collection by new order,
then the first enabled collection as fallback.

This mapping is evidence-based but remains unwired: current v4 Up/Down behavior is unchanged.
Before v5 ships, bench-test the configured front-button remaps in portrait Sidecar orientation and
verify Left/Right do not collide with the Power+Down screenshot chord.

## Compatibility and migration

1. Firmware boots by validating v5 roots. If no valid v5 root exists, it loads the existing v4
   `SPG3` catalog exactly as today.
2. A v4 phone always updates only the v4 catalog, even on v5 firmware.
3. A v5 phone performs an explicit one-time import: it creates collection ID `1`, name `Legacy`,
   from the active v4 pages, verifies it, and publishes the first v5 root. V4 files remain intact.
4. Once a valid v5 root exists, v5 owns Sidecar cold restore and rotation. A later v4 update is
   accepted for compatibility but does not silently replace v5 content; INFO/inventory exposes
   that v5 remains active.
5. Android offers an explicit “replace collections with legacy catalog” recovery action if needed.
   Firmware never auto-deletes either format.

This keeps downgrade safe: older firmware ignores `sidecar_v5` and can still load the retained
v4 catalog.

## Implementation stages and proof gates

### Stage 0 — host model and format tests

Implement platform-neutral encoders/decoders for `SCI5`, `SCM5`, `SCS5`, generation comparison,
path generation, and live-set calculation. Golden-vector tests cover exact byte layout, CRC,
truncation, duplicate IDs/order, unknown flags, invalid UTF-8, generation wrap, stale selection,
and page-count overflow. No X3 behavior changes.

### Stage 1 — read-only collection store

Add v5 manifest loading and cold-boot restore behind a compile flag. Seed fixtures through a host
tool, not BLE. Prove selection restore, flattened rotation, missing/corrupt page rejection, and v4
fallback. Build and checker review are required; no automatic migration yet.

### Stage 2 — full collection creation

Advertise protocol v5 and implement `0x09` only for creation/full replacement of one collection.
Implement `0x0B`–`0x0D` and the alternating `SCT5` journal at the same time. A connection carries
at most four new pages, while the durable session may collect all 16 pages across connections and
device reboots. Only explicit COMMIT may publish the collection.

Bench proof: interrupted transfer at each page, bad CRC, disconnect, power loss before/after
manifest/root writes, storage exhaustion, and cold restore. Existing pages must rotate throughout.

### Stage 3 — partial collection replacement

Enable subset updates with collection-local copying and base-generation conflict checks. Measure
time and writes for updating two weather pages while at least four unrelated collections remain
stored. Prove unrelated page files are neither copied nor modified.

### Stage 4 — metadata and navigation

Add `0x0A`, inventory reads, enable/reorder/rename/delete, logical Left/Right collection navigation, and
selection carry-forward. Prove root-only operations are atomic and button behavior remains usable
during download and deferred activation.

### Stage 5 — migration and capacity enforcement

Add explicit Android-driven v4 import and garbage collection. Storage capacity now admits the
64-page policy; boot validation time, rotation latency, and recovery behavior still require bench
proof before v5 is enabled.

## Remaining decisions before production code

No architecture decision remains. The resolved Left/Right collection mapping still requires the
normal implementation review and bench validation before runtime v5 wiring is enabled.

## Acceptance criteria

V5 is complete when an X3 with at least five collections can receive a weather-only update while
continuing to display its published sequence; only weather's inactive bank and a candidate root
are written; injected interruption leaves the prior weather pages and all other collections
valid; successful activation survives cold boot; v4 Android still performs a complete legacy
update; and button navigation selects pages and collections deterministically.
