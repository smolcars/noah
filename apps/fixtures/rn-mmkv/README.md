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
last-confirmed actualSize 4. The header's older size field is also 4; tests must
use the metadata's actualSize when truncating a pending write.

SHA-256:

- data: `7b7b069453dc2201122825825cbef3ec34b419c123537a13c74d05b45f1442d5`
- crc: `37afcbcbb3864955a5effaccbb6ad20aef39770680f176b67adbdaea0219d635`

Both platforms copy these files into disposable directories. Tests damage the
current checksum and last-confirmed metadata, set impossible lengths, truncate
the last record, or erase payload bytes. They verify recovery, exact pre-open
backup bytes, a single closed-file restore, and blocking when recovery cannot
prove RN data remains. Baseline tests explicitly select the former discard
strategy and demonstrate loss on the same CRC-damaged bytes. Android runs this
through real JNI instrumentation; JVM tests separately exercise durable backup
publication, directory-sync failures, restart retries and deletion markers.

The iOS discard demonstration intentionally replaces the global handler and
must run alone in a separate test process:

```sh
TEST_RUNNER_NOAH_MMKV_DISCARD_DEMO=1 xcodebuild ... \
  '-only-testing:NoahTests/MMKVDiscardDemonstrationTests/defaultStrategyDiscardsTheSameCorruptRNBytes()' \
  test
```

It is skipped in the ordinary full suite. Do not collect real user MMKV files
as fixtures or ask users to share their settings backup.

The recovery review also damages UTF-8 in the final real `wallet-storage` value
while retaining its MMKV key. Present-but-undecodable values must block. After a
successful native validation, suspension writes followed by empty/CRC recovery
and admitted-key removal must block without restoring the initial snapshot.
A separate fsynced retirement record survives snapshot deletion and re-onboarding.
The nine shared launch decisions are in `../mmkv-launch-state.json`.
