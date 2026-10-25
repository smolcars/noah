#!/usr/bin/env python3
"""Real-app RN/native S3 backup acceptance helpers. See README.md.

Public BIP39 vectors are reconstructed in memory, never placed in logs/argv.
All commands capture private output; only explicitly selected evidence is saved.
"""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = "com.noahwallet.regtest"
BASE = "http://127.0.0.1:3100"
MAESTRO = Path.home() / ".maestro/bin/maestro"


def command(*args, input=None, cwd=ROOT, env=None, timeout=180):
    result = subprocess.run([str(a) for a in args], input=input, cwd=cwd, env=env,
                            capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"{Path(args[0]).name} failed (exit {result.returncode})")
    return result.stdout


def public_vector(name):
    # BIP39's 128-bit vectors; Android uses C/D to avoid the iOS wallets.
    words = (ROOT / "apps/ios/Noah/Resources/bip39-english.txt").read_text().split()
    assert len(words) == 2048
    entropy = bytes([{"A": 0x7f, "B": 0x80, "C": 0x00, "D": 0xff}[name]]) * 16
    bits = ''.join(f'{b:08b}' for b in entropy) + f'{hashlib.sha256(entropy).digest()[0]:08b}'[:4]
    return ' '.join(words[int(bits[i:i + 11], 2)] for i in range(0, 132, 11))


def request(path, body=None, token=None, raw=False):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            data = response.read()
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Server {path} HTTP {error.code}") from None
    return data if raw else json.loads(data)


def login(helper, vector):
    return command(helper, "login", input=public_vector(vector)).strip()


def register_vector(helper, vector):
    token = login(helper, vector)
    request("/v0/register", {"ln_address": None, "device_info": None, "ark_address": None}, token)
    return token


def list_backups(helper, vector):
    return request("/v0/backup/v2/list", {}, login(helper, vector))


def bootstrap(helper, vector, scratch):
    folder = scratch / ("bootstrap-" + vector)
    folder.mkdir(mode=0o700)
    command(helper, "snapshot", folder, input=public_vector(vector))
    snapshot = folder / "snapshot.sqlite"
    state = database_state(snapshot)
    assert state["spendable"] == 0 and state["movements"] == [], "Bootstrap must be empty"
    sdk = sdk_state(helper, vector, snapshot, scratch)
    assert not sdk["onchainHistory"] and all(row["sats"] == 0 for row in sdk["balance"])
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zip_file:
        zip_file.write(folder / "manifest.json", "manifest.json")
        zip_file.write(snapshot, "db.sqlite")
    seal = """const c=require('crypto'),fs=require('fs');const v=JSON.parse(fs.readFileSync(0,'utf8'));
const salt=c.randomBytes(16),iv=c.randomBytes(12),key=c.pbkdf2Sync(v.phrase,salt,600000,32,'sha256');
const enc=c.createCipheriv('aes-256-gcm',key,iv);const data=Buffer.concat([Buffer.from([2]),salt,iv,enc.update(Buffer.from(v.zip,'base64')),enc.final(),enc.getAuthTag()]);
fs.writeFileSync(v.output,data,{mode:384});"""
    encrypted = folder / "bootstrap.noahbackup"
    command("node", "-e", seal, input=json.dumps({"phrase": public_vector(vector), "zip": base64.b64encode(archive.getvalue()).decode(), "output": str(encrypted)}))
    data = encrypted.read_bytes()
    token = register_vector(helper, vector)
    upload = request("/v0/backup/v2/upload", {"format_version": 2, "encrypted_size": len(data), "encrypted_sha256": hashlib.sha256(data).hexdigest()}, token)
    req = urllib.request.Request(upload["upload_url"], data=data, method="PUT", headers={
        "Content-Type": "application/octet-stream", "x-amz-checksum-sha256": upload["checksum_sha256"]})
    with urllib.request.urlopen(req, timeout=90) as response:
        assert response.status == 200
    request("/v0/backup/v2/complete", {"backup_id": upload["backup_id"]}, token)
    info = next(row for row in list_backups(helper, vector) if row["backup_id"] == upload["backup_id"])
    shutil.rmtree(folder)
    return {"object": info, "state": state, "sdk": sdk}


def download_inspect(helper, vector, info, scratch):
    metadata = request("/v0/backup/v2/download", {"backup_id": info["backup_id"]}, login(helper, vector))
    assert metadata["backup"] == info
    assert (urlsplit(metadata["download_url"]).hostname or "").endswith(".amazonaws.com"), "Expected the real S3 download origin"
    with urllib.request.urlopen(metadata["download_url"], timeout=90) as response:
        data = response.read()
    assert len(data) == info["encrypted_size"]
    assert hashlib.sha256(data).hexdigest() == info["encrypted_sha256"]
    encrypted, decrypted = scratch / "download.noahbackup", scratch / "download.zip"
    encrypted.write_bytes(data)
    # Invoke the actual decryption script without putting the phrase in argv.
    script = """const fs=require('fs'),M=require('module'),p=require('path');const v=JSON.parse(fs.readFileSync(0,'utf8'));
process.argv=['node','decrypt_backup.js',v.input,v.output,v.phrase];const f=p.resolve('scripts/decrypt_backup.js');
const m=new M(f);m.filename=f;m.paths=M._nodeModulePaths(p.dirname(f));require.main=m;process.mainModule=m;m._compile(fs.readFileSync(f,'utf8'),f);"""
    command("node", "-e", script, input=json.dumps({"input": str(encrypted), "output": str(decrypted), "phrase": public_vector(vector)}))
    with zipfile.ZipFile(decrypted) as archive:
        entries = [{"name": item.filename, "size": item.file_size, "compression": item.compress_type,
                    "flags": item.flag_bits, "extraBytes": len(item.extra)} for item in archive.infolist()]
        assert sorted(row["name"] for row in entries) == ["db.sqlite", "manifest.json"]
        manifest = json.loads(archive.read("manifest.json"))
        snapshot = archive.read("db.sqlite")
    assert manifest["formatVersion"] == data[0] == info["format_version"] == 2
    assert hashlib.sha256(snapshot).hexdigest() == manifest["snapshotSha256"]
    db = scratch / "download.sqlite"
    db.write_bytes(snapshot)
    state = database_state(db)
    assert state["fingerprint"] == manifest["walletFingerprint"]
    evidence = {"object": info, "downloadedFromS3": True, "envelopeVersion": data[0], "entries": entries, "manifest": manifest, "snapshot": state,
                "sdk": sdk_state(helper, vector, db, scratch)}
    for file in (encrypted, decrypted, db):
        file.unlink()
    return evidence


def database_state(path):
    with sqlite3.connect(f"file:{path}?mode=ro", uri=True) as db:
        db.row_factory = sqlite3.Row
        identity = dict(db.execute("select network, fingerprint from bark_properties").fetchone())
        identity["spendable"] = db.execute("select coalesce(sum(amount_sat),0) from vtxo_view where state_kind='Spendable'").fetchone()[0]
        identity["movements"] = [dict(row) for row in db.execute("select id,status,subsystem_name,movement_kind,intended_balance,effective_balance,offchain_fee from bark_movements order by id")]
        return identity


def sdk_state(helper, vector, path, scratch):
    # SQLite backup includes a live app's WAL without copying any secret stores.
    with tempfile.TemporaryDirectory(prefix="inspect-", dir=scratch) as folder:
        with sqlite3.connect(f"file:{path}?mode=ro", uri=True) as source, sqlite3.connect(folder + "/db.sqlite") as destination:
            source.backup(destination)
        return json.loads(command(helper, "inspect", folder, input=public_vector(vector)))


def app_state(sim):
    container = Path(command("xcrun", "simctl", "get_app_container", sim, BUNDLE, "data").strip())
    return database_state(container / "Documents/noah-data-regtest/db.sqlite")


def app_sdk_state(sim, helper, vector, scratch):
    container = Path(command("xcrun", "simctl", "get_app_container", sim, BUNDLE, "data").strip())
    return sdk_state(helper, vector, container / "Documents/noah-data-regtest/db.sqlite", scratch)


def wallet_initialized(raw, kind):
    if kind == "rn":
        # Devices are fresh: no earlier successful wallet can leave a true record.
        return re.search(rb'"isInitialized"\s*:\s*true', raw) is not None
    envelope = json.loads(raw)
    assert hashlib.sha256(envelope["payload"].encode()).hexdigest() == envelope["sha256"]
    values = json.loads(envelope["payload"])["values"]
    return json.loads(values["wallet-storage"])["state"].get("isInitialized") is True


def bitcoin_value(*args):
    output = command("just", "bcli", *args)
    return next(line.strip().strip('"') for line in output.splitlines()
                if re.fullmatch(r'"?(?:bcrt1[a-z0-9]+|[a-f0-9]{64})"?', line.strip()))


def check_restored(expected, actual):
    assert actual == expected, "Fingerprint, all balances, movement rows or onchain history differ"


def check_spent(before, after):
    assert after["state"]["fingerprint"] == before["state"]["fingerprint"]
    assert after["state"]["spendable"] == before["state"]["spendable"] - 5000
    movements = after["state"]["movements"]
    assert movements[:-1] == before["state"]["movements"]
    assert movements[-1]["status"] == "successful" and movements[-1]["intended_balance"] == -5000
    assert movements[-1]["offchain_fee"] == 0
    assert after["sdk"]["onchainHistory"] == before["sdk"]["onchainHistory"]
    previous = {row["label"]: row["sats"] for row in before["sdk"]["balance"]}
    current = {row["label"]: row["sats"] for row in after["sdk"]["balance"]}
    for label in previous:
        assert current[label] == previous[label] - (5000 if label in ("Spendable", "Offchain") else 0)


def restore_ios(sim, kind, vector, scratch, fingerprint):
    flow(sim, "open-restore", scratch)
    command("xcrun", "simctl", "privacy", sim, "grant", "pasteboard", BUNDLE)
    try:
        command("pbcopy", input=public_vector(vector))
        command("xcrun", "simctl", "pbcopy", sim, input=public_vector(vector))
        flow(sim, "submit-" + kind + "-restore", scratch)
    finally:
        command("pbcopy", input="")
        command("xcrun", "simctl", "pbcopy", sim, input="")
    # Never let a failed UI assertion screenshot an RN phrase field. A failed
    # restore rolls back the installed DB. Observe stable installation first.
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            if app_state(sim)["fingerprint"] == fingerprint:
                container = Path(command("xcrun", "simctl", "get_app_container", sim, BUNDLE, "data").strip())
                settings = container / ("Documents/mmkv/noah-wallet-storage" if kind == "rn" else "Documents/mmkv.native-settings.json")
                if not wallet_initialized(settings.read_bytes(), kind):
                    time.sleep(2)
                    continue
                time.sleep(3)
                if app_state(sim)["fingerprint"] == fingerprint:
                    flow(sim, "finish-restore", scratch)
                    return
        except (RuntimeError, sqlite3.Error, TypeError, OSError, ValueError, KeyError):
            pass
        time.sleep(2)
    command("xcrun", "simctl", "terminate", sim, BUNDLE)
    raise RuntimeError("Restore did not install a stable wallet; stopped before UI capture")


def fund_ios(sim, kind, scratch):
    flow(sim, "receive-address", scratch)
    address = command("xcrun", "simctl", "pbpaste", sim).strip()
    assert address.startswith("tark1")
    flow(sim, "receive-onchain-address", scratch)
    chain = command("xcrun", "simctl", "pbpaste", sim).strip()
    assert chain.startswith("bcrt1")
    flow(sim, "close-" + kind + "-receive", scratch)
    command("just", "bark", "--quiet", "--no-logfile", "send", "--wait", address, "20000 sats")
    txid = bitcoin_value("sendtoaddress", chain, "0.00010000")
    bitcoin_value("generatetoaddress", "1", bitcoin_value("getnewaddress"))
    flow(sim, "refresh-funded-" + kind, scratch)
    return {"arkSats": 20000, "onchainSats": 10000, "onchainTxid": txid, "blocksMined": 1}


def spend_ios(sim, kind, helper, vector, scratch):
    destination = next(line for line in command("just", "bark", "--quiet", "--no-logfile", "address").splitlines() if line.startswith("tark1"))
    command("pbcopy", input=destination)
    command("xcrun", "simctl", "pbcopy", sim, input=destination)
    path = ROOT / ("client/.maestro/subflows" if kind == "rn" else "apps/maestro/subflows") / ("send-funded-ark.yml" if kind == "rn" else "send-ark-from-clipboard.yml")
    flow(sim, str(path), scratch, {"DESTINATION": destination})
    state = {"state": app_state(sim), "sdk": app_sdk_state(sim, helper, vector, scratch)}
    output = command("just", "bark", "--quiet", "--no-logfile", "movements")
    rows = json.JSONDecoder().raw_decode(output[output.index("[\n"):])[0]
    matches = [row for row in rows if row.get("intended_balance_sat") == 5000 and any(
        value.get("destination", {}).get("value") == destination for value in row.get("received_on", []))]
    assert len(matches) == 1, "Bark did not receive exactly one spend at the fresh address"
    return dict(state, amount=5000, destination=destination, receiver=matches[0]["id"])


def compile_helper(derived_data, scratch):
    package = derived_data / "SourcePackages/checkouts/bark-bolt-ffi"
    binary = derived_data / "SourcePackages/artifacts/bark-bolt-ffi/BarkBoltFFIFFIBinary/BarkBoltFFI.xcframework/macos-arm64_x86_64"
    helper = scratch / "interop-setup"
    command("xcrun", "swiftc", "-parse-as-library", "-I", package / "sdk/swift/FFI/include", "-I", binary / "Headers",
            binary / "libbark_bolt_ffi.a", "-framework", "Security", "-framework", "SystemConfiguration", "-o", helper,
            *sorted((package / "sdk/swift/Sources").rglob("*.swift")), ROOT / "apps/BackupTests/InteropSetup.swift", timeout=300)
    return helper


def run_ios(rn_app, native_app, derived_data, evidence_path):
    import plistlib
    os.umask(0o077)
    assert "http://localhost:3100" in plistlib.loads((native_app / "Info.plist").read_bytes())["NoahRegtestServerURL"]
    assert b"http://localhost:3100" in (rn_app / "main.jsbundle").read_bytes(), "RN build must target :3100"
    command("curl", "--fail", "--silent", BASE + "/health")
    command("just", "bark", "--quiet", "--no-logfile", "balance")
    scratch = Path(tempfile.mkdtemp(prefix="noah-rn-native-interop-", dir="/private/tmp"))
    owned = []
    evidence = {"base": command("git", "rev-parse", "HEAD").strip(), "ios": {}}

    def identity(sim):
        groups = json.loads(command("xcrun", "simctl", "list", "devices", "-j"))["devices"]
        return next(device for group in groups.values() for device in group if device["udid"] == sim)

    def stop(sim, name):
        assert identity(sim)["name"] == name
        if identity(sim)["state"] == "Booted":
            command("xcrun", "simctl", "shutdown", sim)
        assert identity(sim)["state"] == "Shutdown"

    def create(kind):
        name = "NoahRNBackupInterop_" + kind + "_" + os.urandom(4).hex()
        sim = command("xcrun", "simctl", "create", name, "com.apple.CoreSimulator.SimDeviceType.iPhone-17",
                      "com.apple.CoreSimulator.SimRuntime.iOS-27-0").strip()
        owned.append((sim, name))
        command("xcrun", "simctl", "boot", sim)
        command("xcrun", "simctl", "bootstatus", sim, "-b")
        command("xcrun", "simctl", "install", sim, rn_app if kind == "rn" else native_app)
        return sim, name

    try:
        helper = compile_helper(derived_data, scratch)
        # Fixed public vectors may hold funds from an earlier run. Never overwrite
        # their newest server backup or blindly repeat an uncertain payment.
        for vector in ("A", "B"):
            register_vector(helper, vector)
            assert not list_backups(helper, vector), "Public vectors already have backups; use an independently reset test environment"
        for source_kind, target_kind, vector in (("rn", "native", "A"), ("native", "rn", "B")):
            key = source_kind + "-to-" + target_kind
            result = evidence["ios"][key] = {}
            result["bootstrap"] = bootstrap(helper, vector, scratch)
            source, source_name = create(source_kind)
            restore_ios(source, source_kind, vector, scratch, result["bootstrap"]["state"]["fingerprint"])
            result["funding"] = fund_ios(source, source_kind, scratch)
            flow(source, "backup-" + source_kind, scratch)
            result["source"] = {"state": app_state(source), "sdk": app_sdk_state(source, helper, vector, scratch)}
            command("xcrun", "simctl", "terminate", source, BUNDLE)
            info = list_backups(helper, vector)[0]
            initial = result["bootstrap"]["object"]
            assert info["created_at"] > initial["created_at"] and info["backup_id"] != initial["backup_id"]
            assert info["encrypted_sha256"] != initial["encrypted_sha256"]
            result["backup"] = download_inspect(helper, vector, info, scratch)
            assert result["backup"]["snapshot"] == result["source"]["state"]
            assert result["source"]["state"]["spendable"] == 20000
            stop(source, source_name)
            target, _ = create(target_kind)
            assert list_backups(helper, vector)[0] == info, "Newest server object changed before restore"
            restore_ios(target, target_kind, vector, scratch, result["source"]["state"]["fingerprint"])
            result["restored"] = {"state": app_state(target), "sdk": app_sdk_state(target, helper, vector, scratch)}
            check_restored(result["source"], result["restored"])
            result["spent"] = spend_ios(target, target_kind, helper, vector, scratch)
            check_spent(result["restored"], result["spent"])
            result["status"] = "pass"
            evidence_path.write_text(json.dumps(evidence, indent=2) + "\n")
            print(key + " iOS passed", flush=True)
    finally:
        evidence_path.write_text(json.dumps(evidence, indent=2) + "\n")
        try:
            for sim, name in reversed(owned):
                stop(sim, name)
                command("xcrun", "simctl", "delete", sim)
                assert sim not in command("xcrun", "simctl", "list", "devices", "-j")
        finally:
            shutil.rmtree(scratch)
        print("Owned simulators and private runner artifacts removed", flush=True)


def flow(sim, name, scratch, variables=None):
    path = Path(name) if "/" in str(name) else ROOT / "apps/BackupTests/flows" / (name + ".yml")
    output = scratch / ("maestro-" + path.stem)
    args = [MAESTRO, "--device", sim, "test", "--debug-output", output, "--test-output-dir", output]
    for key, value in (variables or {}).items():
        args += ["-e", f"{key}={value}"]
    command(*args, path, timeout=240)


def self_test():
    assert public_vector("A") != public_vector("B")
    assert all(len(public_vector(v).split()) == 12 for v in ("A", "B"))
    assert not wallet_initialized(b'{"isInitialized":false}', "rn")
    assert wallet_initialized(b'\x00{"isInitialized":true}', "rn")
    for initialized in (False, True):
        payload = json.dumps({"values": {"wallet-storage": json.dumps({"state": {"isInitialized": initialized}})}})
        envelope = json.dumps({"payload": payload, "sha256": hashlib.sha256(payload.encode()).hexdigest()}).encode()
        assert wallet_initialized(envelope, "native") == initialized
    with tempfile.TemporaryDirectory() as directory:
        db = Path(directory) / "db.sqlite"
        with sqlite3.connect(db) as connection:
            connection.executescript("create table bark_properties(network, fingerprint); insert into bark_properties values('regtest','test');"
                "create table vtxo_view(amount_sat,state_kind); insert into vtxo_view values(12000,'Spendable'),(1000,'Spent');"
                "create table bark_movements(id,status,subsystem_name,movement_kind,intended_balance,effective_balance,offchain_fee);")
        assert database_state(db) == {"network": "regtest", "fingerprint": "test", "spendable": 12000, "movements": []}
    sample = {"state": {"fingerprint": "test", "spendable": 20000, "movements": []}, "sdk": {
        "balance": [{"label": "Spendable", "sats": 20000}, {"label": "Offchain", "sats": 20000},
                    {"label": "Onchain", "sats": 10000}], "onchainHistory": [{"id": "chain", "amount": 10000}]}}
    check_restored(sample, sample)
    for field in ("state", "sdk"):
        changed = dict(sample, **{field: None})
        try:
            check_restored(sample, changed)
        except AssertionError:
            continue
        raise AssertionError("Restore comparison accepted missing state")
    spent = json.loads(json.dumps(sample))
    spent["state"].update(spendable=15000, movements=[{"status": "successful", "intended_balance": -5000, "offchain_fee": 0}])
    for row in spent["sdk"]["balance"][:2]:
        row["sats"] -= 5000
    check_spent(sample, spent)
    for label in ("Spendable", "Offchain", "Onchain"):
        changed = json.loads(json.dumps(spent))
        next(row for row in changed["sdk"]["balance"] if row["label"] == label)["sats"] += 1
        try:
            check_spent(sample, changed)
        except AssertionError:
            continue
        raise AssertionError("Spend comparison accepted changed balance")
    print("Interop public-vector/database/restore/spend self-check passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--rn-app", type=Path)
    parser.add_argument("--native-app", type=Path)
    parser.add_argument("--native-derived-data", type=Path)
    parser.add_argument("--evidence", type=Path, default=Path("/private/tmp/noah-rn-native-interop-evidence.json"))
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if not all((args.rn_app, args.native_app, args.native_derived_data)):
            parser.error("Supply --rn-app, --native-app and --native-derived-data; see README.md")
        run_ios(args.rn_app.resolve(), args.native_app.resolve(), args.native_derived_data.resolve(), args.evidence)
