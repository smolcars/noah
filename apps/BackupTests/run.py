#!/usr/bin/env python3
"""Funded Swift → Kotlin → Swift service acceptance. Run inside nix develop.

Creates/deletes its own devices; never operates Docker lifecycle. Secrets and
encrypted exports remain in memory. The optional evidence file is public only.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import shutil
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]
SERIAL = "emulator-5618"


def command(*args, cwd=ROOT, timeout=180, env=None):
    result = subprocess.run(args, cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        # No command arguments/response bodies: a future helper may carry secrets.
        raise RuntimeError(f"{Path(args[0]).name} failed (exit {result.returncode})")
    return result.stdout


def wrapper(*args):
    return command("just", *args)


def bitcoin(*args):
    text = wrapper("bcli", *args)
    return next(line.strip().strip('"') for line in text.splitlines()
                if re.fullmatch(r'"?(?:bcrt1[a-z0-9]+|[a-f0-9]{64})"?', line.strip()))


def balances(state):
    return {row["label"]: row["sats"] for row in state["balance"]}


def check_same(expected, actual):
    assert actual == expected, "Fingerprint, balance rows or movement/history records differ"


def check_spend(before, after, fee):
    assert after["fingerprint"] == before["fingerprint"]
    assert after["movements"] == before["movements"] + 1
    assert balances(after)["Spendable"] == balances(before)["Spendable"] - 1000 - fee
    assert balances(after)["Onchain"] == balances(before)["Onchain"]
    old = {row["id"]: row for row in before["history"]}
    new = {row["id"]: row for row in after["history"]}
    assert all(new.get(key) == row for key, row in old.items())
    sent = [row for key, row in new.items() if key not in old]
    assert len(sent) == 1 and sent[0]["direction"] == "outgoing" and sent[0]["amount"] == 1000 + fee
    assert sent[0]["status"] == "successful"


class Bridge(ThreadingHTTPServer):
    def __init__(self):
        super().__init__(("127.0.0.1", 0), Handler)
        self.exports = {}
        self.evidence = {}
        self.payment = None
        self.payment_error = None
        self.failures = []
        self.recipient = next(line for line in wrapper("bark", "address").splitlines() if line.startswith("tark1"))


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def handle_request(self):
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length)) if length else {}
            parts = self.path.strip("/").split("/")
            server = self.server
            response = {}
            raw = False
            if parts[0] == "recipient":
                response = server.recipient.encode(); raw = True
            elif parts[0] == "onchain":
                address = body["address"]
                assert re.fullmatch(r"bcrt1[a-z0-9]+", address)
                txid = bitcoin("sendtoaddress", address, "0.00010000")
                bitcoin("generatetoaddress", "1", bitcoin("getnewaddress"))
                print(f"Funded onchain 10000 sats: {txid}", flush=True)
            elif parts[0] == "pay":
                assert server.payment is None or not server.payment.is_alive()
                invoice = body["invoice"]
                assert invoice.startswith("lnbcrt")
                def pay():
                    try:
                        result = wrapper("lncli", "payinvoice", "--force", "--json", invoice)
                        assert re.search(r'"status"\s*:\s*"SUCCEEDED"', result)
                    except Exception:
                        server.payment_error = "LND payment failed; inspect LND before any retry"
                server.payment = threading.Thread(target=pay)
                server.payment.start()
            elif parts[0] == "paid":
                server.payment.join(30)
                assert not server.payment.is_alive(), "LND payment did not finish"
                assert server.payment_error is None, server.payment_error
            elif parts[0] == "export":
                platform = parts[1]
                assert platform not in server.exports, "Never overwrite or replay a funded source"
                state = body["state"]
                assert state["movements"] >= 2 and balances(state)["Spendable"] > 0
                assert balances(state)["Confirmed"] == 10000
                server.exports[platform] = body
                server.evidence[platform] = {"source": state}
                print(f"{platform} export: {state['fingerprint']}, Ark {balances(state)['Spendable']}, onchain 10000, {state['movements']} movements", flush=True)
            elif parts[0] == "input":
                response = {"phrase": server.exports[parts[1]]["phrase"]}
            elif parts[0] == "check":
                platform, stage = parts[1:]
                server.evidence[platform][stage] = body["state"]
                if stage == "after-sync":
                    check_same(server.exports[platform]["state"], body["state"])
                print(f"{platform} restore {stage} recorded", flush=True)
            elif parts[0] == "spent":
                platform = parts[1]
                check_spend(server.exports[platform]["state"], body["state"], body["fee"])
                # Receiver-side sync, in addition to the restored sender's successful movement.
                received = wrapper("bark", "movements")
                rows = json.JSONDecoder().raw_decode(received[received.index("[\n"):])[0]
                received_spends = [row for row in rows if row.get("intended_balance_sat") == 1000 and any(
                    destination.get("destination", {}).get("value") == server.recipient
                    for destination in row.get("received_on", []))]
                assert len(received_spends) == 1 + sum("spent" in value for value in server.evidence.values()), "Bark did not receive a new spend"
                server.evidence[platform]["receiverMovement"] = received_spends[-1]["id"]
                server.evidence[platform]["spent"] = body["state"]
                server.evidence[platform]["spendFee"] = body["fee"]
                print(f"{platform} restored spend: 1000 sats, fee {body['fee']}", flush=True)
            elif parts[0] in ("ios", "android"):
                platform = parts[0]
                data = base64.b64decode(server.exports[platform]["fileBase64"])
                info = {"backup_id": platform, "created_at": "2026-10-20T00:00:00Z", "format_version": 2,
                        "encrypted_sha256": hashlib.sha256(data).hexdigest(), "encrypted_size": len(data)}
                url = f"http://{self.headers['Host']}/{platform}/file"
                if parts[1:] == ["v0", "backup", "v2", "list"]:
                    response = [info]
                elif parts[1:] == ["v0", "backup", "v2", "download"]:
                    response = {"backup": info, "download_url": url}
                elif parts[1:] == ["file"]:
                    response = data; raw = True
                else:
                    raise ValueError("Unknown backup fixture route")
            else:
                raise ValueError("Unknown test bridge route")
            payload = response if raw else json.dumps(response).encode()
            self.send_response(200)
        except Exception as error:
            # Assertion messages are fixed/public; never echo request bodies or exceptions with secrets.
            self.server.failures.append(type(error).__name__ + ": " + (str(error) if isinstance(error, AssertionError) else "host operation failed"))
            print(self.server.failures[-1], flush=True)
            payload = b'{"error":"host assertion failed"}'
            self.send_response(500)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def self_test():
    global wrapper
    original = wrapper
    try:
        for output in ('wrapper output\n[\n  "' + 'a' * 64 + '"\n]\n', 'wrapper output\n' + 'a' * 64 + '\n'):
            wrapper = lambda *_: output
            assert bitcoin("generatetoaddress") == 'a' * 64
    finally:
        wrapper = original
    sample = {"fingerprint": "test", "movements": 2, "balance": [
        {"label": "Spendable", "sats": 38000}, {"label": "Onchain", "sats": 10000}], "history": []}
    check_same(sample, sample)
    for field in ("fingerprint", "movements", "balance", "history"):
        changed = dict(sample, **{field: None})
        try:
            check_same(sample, changed)
        except AssertionError:
            continue
        raise AssertionError(f"Comparison missed {field}")
    spent = json.loads(json.dumps(sample))
    spent["movements"] += 1; spent["balance"][0]["sats"] -= 1000
    spent["history"] = [{"id": "3", "direction": "outgoing", "amount": 1000, "status": "successful"}]
    check_spend(sample, spent, 0)
    spent["balance"][0]["sats"] += 1
    try:
        check_spend(sample, spent, 0)
    except AssertionError:
        print("Comparison self-check passed")
        return
    raise AssertionError("Spend comparison missed incorrect amount")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, default=Path("/private/tmp/noah-funded-backup-evidence.json"))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test(); return
    os.umask(0o077)
    services = command("docker", "compose", "-f", "scripts/docker-compose.yml", "ps", "--format", "{{.Service}} {{.State}}")
    for required in ("bitcoind", "captaind", "cln", "lnd"):
        assert f"{required} running" in services.splitlines(), f"Required container {required} is not running; stop"
    channel = wrapper("lncli", "channelbalance")
    channel = json.loads(channel[channel.index("{"):channel.rindex("}") + 1])
    if int(channel["local_balance"]["sat"]) < 80_000:
        # Return outbound liquidity for repeat runs, using the existing CLN↔LND channel.
        invoice = wrapper("lncli", "addinvoice", "--amt", "80000")
        invoice = json.loads(invoice[invoice.index("{"):invoice.rindex("}") + 1])["payment_request"]
        returned = wrapper("cln", "pay", invoice)
        assert re.search(r'"status"\s*:\s*"complete"', returned), "Could not return LND liquidity"
        print("Returned 80000 sats of LND channel liquidity", flush=True)
    assert SERIAL not in command("adb", "devices"), "Console port 5618 is occupied"
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", 5618))
    sdk = Path.home() / "Library/Android/sdk"
    avdmanager = sdk / "cmdline-tools/latest/bin/avdmanager"
    name = "NoahFundedBackup_" + os.urandom(4).hex()
    sim = None; avd = False; emulator = None; bridge = None
    scratch = Path(tempfile.mkdtemp(prefix="noah-funded-backup-", dir="/private/tmp"))

    def build(argv, cwd, label, env=None):
        print(label, flush=True)
        log = scratch / f"{label}.log"
        with log.open("w") as output:
            process = subprocess.Popen(argv, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                returncode = process.wait(timeout=1200)
            except BaseException:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=30)
                raise
        if returncode:
            # Compiler diagnostics only; never print runtime values or XCTest request bodies.
            for line in log.read_text(errors="replace").splitlines():
                if "error:" in line or line.startswith("e: ") or "TEST FAILED" in line:
                    print(line, flush=True)
            raise RuntimeError(f"{label} failed; see public bridge evidence above")
        return log.read_text(errors="replace")

    try:
        sim = command("xcrun", "simctl", "create", name, "com.apple.CoreSimulator.SimDeviceType.iPhone-17", "com.apple.CoreSimulator.SimRuntime.iOS-27-0").strip()
        command("xcrun", "simctl", "boot", sim)
        command("xcrun", "simctl", "bootstatus", sim, "-b")
        # Host SDK includes emulator/system images; Nix supplies build dependencies.
        result = subprocess.run([str(avdmanager), "create", "avd", "-n", name, "-k", "system-images;android-35;google_apis;arm64-v8a"], input="no\n", capture_output=True, text=True)
        assert result.returncode == 0, "AVD creation failed"
        avd = True
        emulator = subprocess.Popen([str(sdk / "emulator/emulator"), "-avd", name, "-port", "5618", "-no-window", "-no-snapshot"],
            env=dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk)), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        command("adb", "-s", SERIAL, "wait-for-device")
        deadline = time.monotonic() + 180
        while command("adb", "-s", SERIAL, "shell", "getprop", "sys.boot_completed").strip() != "1":
            assert time.monotonic() < deadline, "Emulator boot timed out"
            time.sleep(2)
        assert name in command("adb", "-s", SERIAL, "emu", "avd", "name").splitlines()
        print(f"Owned devices: {name}, iOS {sim}, Android {SERIAL}", flush=True)
        bridge = Bridge()
        threading.Thread(target=bridge.serve_forever, daemon=True).start()
        port = bridge.server_port
        command("adb", "-s", SERIAL, "reverse", f"tcp:{port}", f"tcp:{port}")
        command("/opt/homebrew/bin/xcodegen", "generate", cwd=ROOT / "apps/ios")
        xcode = ["xcodebuild", "-project", "Noah.xcodeproj", "-scheme", "Noah-Regtest", "-configuration", "Debug-Regtest",
                 "-destination", f"platform=iOS Simulator,id={sim}", "-derivedDataPath", str(scratch / "DerivedData"),
                 "-jobs", "2", "-parallel-testing-enabled", "NO", "ARCHS=arm64", "ONLY_ACTIVE_ARCH=YES",
                 "-only-testing:NoahTests/FundedCrossPlatformBackupTests"]
        build(xcode + ["build-for-testing"], ROOT / "apps/ios", "ios-build")
        android = ROOT / "apps/android"
        build(["./gradlew", "--max-workers=2", "--no-daemon", ":app:assembleRegtestDebug", ":app:assembleRegtestDebugAndroidTest"], android, "android-build")
        for apk in ("app/build/outputs/apk/regtest/debug/app-regtest-debug.apk", "app/build/outputs/apk/androidTest/regtest/debug/app-regtest-debug-androidTest.apk"):
            command("adb", "-s", SERIAL, "install", "-r", str(android / apk))
        for mode in ("produce", "consume"):
            env = dict(os.environ, TEST_RUNNER_NOAH_FUNDED_BACKUP_URL=f"http://127.0.0.1:{port}", TEST_RUNNER_NOAH_FUNDED_BACKUP_MODE=mode)
            build(xcode + ["-resultBundlePath", str(scratch / f"{mode}.xcresult"), "test-without-building"], ROOT / "apps/ios", f"ios-{mode}", env)
            if mode == "produce":
                result = command("adb", "-s", SERIAL, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                    "com.noahwallet.services.FundedCrossPlatformBackupTest", "-e", "noahFundedBackupURL", f"http://127.0.0.1:{port}",
                    "com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner", timeout=600)
                if "OK (1 test)" not in result:
                    for line in result.splitlines():
                        if "AssertionError" in line or "at com.noahwallet." in line or "Process crashed" in line:
                            print(line, flush=True)
                assert "OK (1 test)" in result, "Android acceptance failed"
        assert not bridge.failures and all("spent" in bridge.evidence[p] for p in ("ios", "android"))
        args.evidence.write_text(json.dumps(bridge.evidence, indent=2) + "\n")
        print(f"Both directions passed. Public evidence: {args.evidence}", flush=True)
    finally:
        if bridge:
            bridge.shutdown(); bridge.server_close()
            if bridge.payment: bridge.payment.join(180)
            if bridge.evidence:
                args.evidence.write_text(json.dumps(bridge.evidence, indent=2) + "\n")
            bridge.exports.clear()
        if emulator:
            if emulator.poll() is None:
                assert name in command("adb", "-s", SERIAL, "emu", "avd", "name").splitlines()
                command("adb", "-s", SERIAL, "emu", "kill")
            emulator.wait(timeout=60)
            deadline = time.monotonic() + 60
            while SERIAL in command("adb", "devices"):
                assert time.monotonic() < deadline, "Emulator must be stopped before deletion"
                time.sleep(1)
        if avd:
            command(str(avdmanager), "delete", "avd", "-n", name)
            assert name not in command(str(avdmanager), "list", "avd", "-c").splitlines()
        if sim:
            def identity():
                devices = json.loads(command("xcrun", "simctl", "list", "devices", "-j"))["devices"]
                return next(d for group in devices.values() for d in group if d["udid"] == sim)
            assert identity()["name"] == name
            command("xcrun", "simctl", "shutdown", sim)
            assert identity()["state"] == "Shutdown"
            command("xcrun", "simctl", "delete", sim)
            assert sim not in command("xcrun", "simctl", "list", "devices", "-j")
        shutil.rmtree(scratch)
        print("Owned devices, DerivedData, result bundles and logs removed", flush=True)


if __name__ == "__main__":
    main()
