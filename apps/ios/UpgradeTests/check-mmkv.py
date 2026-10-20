#!/usr/bin/env python3
"""Record/compare every live RN MMKV file, without opening MMKV."""
import hashlib
import json
import pathlib
import sys

mode, directory, checkpoint = sys.argv[1:]
root = pathlib.Path(directory)
assert root.is_dir(), "Live RN MMKV directory missing"
actual = {
    str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
    for p in sorted(root.rglob("*")) if p.is_file()
}
assert "noah-wallet-storage" in actual and "noah-wallet-storage.crc" in actual
target = pathlib.Path(checkpoint)
if mode == "record":
    target.write_text(json.dumps(actual, sort_keys=True))
elif mode == "check":
    assert actual == json.loads(target.read_text()), "Native modified live RN MMKV files"
elif mode == "snapshot":
    expected = json.loads(target.read_text())
    for name in ["noah-wallet-storage", "noah-wallet-storage.crc"]:
        assert actual[name] == expected[name], "Native snapshot differs from final RN bytes"
else:
    raise ValueError("Use record, check or snapshot")
print("Live RN MMKV:", mode, len(actual), "files; manifest SHA-256",
      hashlib.sha256(json.dumps(actual, sort_keys=True).encode()).hexdigest())
