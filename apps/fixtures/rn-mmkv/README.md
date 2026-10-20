# RN-written MMKV corruption fixture

Captured on 2026-10-20 from a fresh, task-owned iOS 27.0 simulator running RN
0.1.14 regtest (MMKVCore 2.4.0), after the existing Maestro onboarding flows
reached Home. Email and Lightning-name setup were skipped. No funding was used.
Only the unencrypted `Documents/mmkv/noah-wallet-storage` and `.crc` were copied;
no keychain, mnemonic, database, logs, or auth-token file is included. The file
contains four RN stores written by that flow, including `wallet-storage`.
The full eight-store JSON contracts remain in `rn-store-compatibility.json`.

These are actual mmap files, including padding, rather than re-encoded native
JSON. Each is 16,384 bytes. Metadata: version 4, sequence 1, actualSize 4,157,
last-confirmed actualSize 4. The header's older size field is 0 (the earlier README said 4); tests must
use the metadata's actualSize when truncating a pending write.

SHA-256:

- data: `7b7b069453dc2201122825825cbef3ec34b419c123537a13c74d05b45f1442d5`
- crc: `37afcbcbb3864955a5effaccbb6ad20aef39770680f176b67adbdaea0219d635`

Both platforms copy these files into isolated test directories. The importer
must preserve both files byte-for-byte, including after 2,000 native writes.
Checksum/length failures import defaults, with the settings notice and no
recovery. The real RN pair has four stores and no recurring schedules; separate
MMKV-written test records exercise recurring non-import and notices. Shared
`../mmkv-launch-state.json` supplies eight admission cases; all 196 raw envelope
cases have independent import/drop expectations. Do not collect real user MMKV
files as fixtures or ask users to share their settings backup.

## Pre-open verifier pinned in review round 3

SPM pins MMKV **v2.4.3**, commit `007a393cd5e255e9aa68d6648f7179159bdf0f9d`.
`Core/MMKVMetaInfo.hpp` compiled with `sizeof`/`offsetof` confirms a **112-byte**,
little-endian metadata struct on arm64. The Android Gradle artifact is
`com.tencent:mmkv:2.4.3` (AAR SHA-256
`fbab73c29e5d4a7d157883ea1e468d7f2f84a7be89cc84bc76b320cce03400d8`);
its JNI behavior is exercised with these same RN bytes and native writes.
The corresponding Android JNI bridge and core are in that pinned source checkout.

- Offset 0: uint32 current CRC-32 (IEEE/zlib), here `0xa896e632`.
- Offset 4: uint32 version, here 4; offset 8: uint32 sequence, here 1.
- Offset 12: 16-byte encryption IV (this store is unencrypted).
- Offset 28: uint32 current payload size, here 4,157.
- Offset 32: uint32 last-confirmed size, here 4; offset 36: its uint32
  checksum, here `0x606e62b4`; offsets 40–103: sixteen reserved uint32s.
- Offset 104: uint64 flags; metadata file padding follows the 112-byte struct.

The data file starts with a four-byte legacy size header (here **0**), followed
by the payload. Versions 0–2 use the legacy header; versions 3–4 use metadata
size at 28. CRC covers exactly that many payload bytes starting at data offset 4,
excluding the header and file padding. The legacy header and last-confirmed pair
may lag a healthy current pair. Reserved versions 5/6 and all higher versions
are rejected. A missing data file is a never-created store, not corruption.

`MMKVIntegrityTests` (Swift) and `MMKVIntegrityTest` (JVM) assert these exact
fixture fields and validate the fixture without mapping it; they reject damaged
current CRC/size, truncation, short/missing metadata and unsupported versions.
The real MMKV recovery suites then install an intact older confirmed prefix,
append a newer suspension setting and damage only the current checksum or
length. This reaches `MMKV_IO.cpp::checkDataValid`'s callback-free rollback path.
Both cases must create a durable quarantine before mapping, require Continue,
and never restore the retired RN snapshot.
