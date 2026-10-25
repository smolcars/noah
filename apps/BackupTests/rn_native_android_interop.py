#!/usr/bin/env python3
"""Android companion to rn_native_interop.py; requires prebuilt regtest APKs."""
import argparse
import base64
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time

import rn_native_interop as test


class Emulator:
    def __init__(self, scratch, port=5620):
        assert port >= 5592 and port % 2 == 0
        self.scratch, self.port = scratch, port
        self.serial = f"emulator-{port}"
        self.name = "NoahRNBackupInterop_" + os.urandom(4).hex()
        self.sdk = Path(os.environ["ANDROID_HOME"])
        self.manager = self.sdk / "cmdline-tools/latest/bin/avdmanager"
        self.process = None
        self.created = False

    def adb(self, *args):
        return test.command(self.sdk / "platform-tools/adb", "-s", self.serial, *args)

    def __enter__(self):
        assert self.serial not in test.command(self.sdk / "platform-tools/adb", "devices")
        for port in (self.port, self.port + 1, self.port + 3000):
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(("127.0.0.1", port))
        try:
            test.command(self.manager, "create", "avd", "-n", self.name, "-d", "pixel_7",
                         "-k", "system-images;android-35;google_apis;arm64-v8a", input="no\n")
            self.created = True
            with (self.scratch / (self.name + ".log")).open("w") as output:
                self.process = subprocess.Popen([str(self.sdk / "emulator/emulator"), "-avd", self.name,
                    "-port", str(self.port), "-grpc", str(self.port + 3000), "-grpc-use-token",
                    "-no-window", "-no-snapshot", "-memory", "2048", "-cores", "2"],
                    stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            self.adb("wait-for-device")
            deadline = time.monotonic() + 180
            while self.adb("shell", "getprop", "sys.boot_completed").strip() != "1":
                assert time.monotonic() < deadline, "Owned emulator boot timed out"
                time.sleep(2)
            assert self.name in self.adb("emu", "avd", "name").splitlines()
            self.adb("root")
            self.adb("wait-for-device")
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, *_):
        if self.process:
            if self.process.poll() is None:
                assert self.name in self.adb("emu", "avd", "name").splitlines()
                self.adb("emu", "kill")
            self.process.wait(timeout=60)
            deadline = time.monotonic() + 60
            while self.serial in test.command(self.sdk / "platform-tools/adb", "devices"):
                assert time.monotonic() < deadline, "Emulator must be shut down before deletion"
                time.sleep(1)
        if self.created:
            test.command(self.manager, "delete", "avd", "-n", self.name)
            assert self.name not in test.command(self.manager, "list", "avd", "-c").splitlines()

    def launch(self):
        self.adb("shell", "am", "start", "-W", "-n", test.BUNDLE + "/com.noahwallet.MainActivity")

    def install(self, apk):
        self.adb("install", "--no-incremental", str(apk))
        self.adb("shell", "pm", "grant", test.BUNDLE, "android.permission.POST_NOTIFICATIONS")
        self.launch()

    def clipboard(self, value=None):
        return test.command("python3", test.ROOT / "apps/android/UpgradeTests/clipboard.py", self.serial,
                            "get" if value is None else "set", input=value)

    def capture(self, helper, vector):
        # Freeze the producer before copying its SQLite/WAL pair. Never copy the
        # Android seed, Keystore, auth token or MMKV files into host evidence.
        self.adb("shell", "am", "force-stop", test.BUNDLE)
        with tempfile.TemporaryDirectory(prefix="android-db-", dir=self.scratch) as folder:
            for suffix in ("", "-wal", "-shm"):
                path = f"/data/user/0/{test.BUNDLE}/files/noah-data-regtest/db.sqlite{suffix}"
                try:
                    self.adb("pull", path, folder + "/db.sqlite" + suffix)
                except RuntimeError:
                    if not suffix:
                        raise
            db = Path(folder) / "db.sqlite"
            result = {"state": test.database_state(db), "sdk": test.sdk_state(helper, vector, db, self.scratch)}
        return result

    def restore(self, kind, vector):
        test.flow(self.serial, "open-restore", self.scratch)
        try:
            self.clipboard(test.public_vector(vector))
            test.flow(self.serial, "submit-" + kind + "-android-restore", self.scratch)
        finally:
            self.clipboard("")
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                self.adb("shell", "test", "-f", f"/data/user/0/{test.BUNDLE}/files/noah-data-regtest/db.sqlite")
                path = f"/data/user/0/{test.BUNDLE}/" + ("files/mmkv/noah-wallet-storage" if kind == "rn" else "no_backup/settings.native.json")
                raw = base64.b64decode(self.adb("shell", "base64", path))
                if not test.wallet_initialized(raw, kind):
                    time.sleep(2)
                    continue
                time.sleep(3)
                test.flow(self.serial, "finish-restore", self.scratch)
                return
            except (RuntimeError, ValueError, KeyError, TypeError):
                time.sleep(2)
        self.adb("shell", "am", "force-stop", test.BUNDLE)
        raise RuntimeError("Android restore did not install a wallet; stopped before UI capture")

    def fund(self, kind):
        test.flow(self.serial, "receive-address", self.scratch)
        ark = self.clipboard().strip()
        assert ark.startswith("tark1")
        test.flow(self.serial, "receive-onchain-address", self.scratch)
        chain = self.clipboard().strip()
        assert chain.startswith("bcrt1")
        test.flow(self.serial, "close-" + kind + "-receive", self.scratch)
        test.command("just", "bark", "--quiet", "--no-logfile", "send", "--wait", ark, "20000 sats")
        txid = test.bitcoin_value("sendtoaddress", chain, "0.00010000")
        test.bitcoin_value("generatetoaddress", "1", test.bitcoin_value("getnewaddress"))
        test.flow(self.serial, "refresh-funded-" + kind, self.scratch)
        return {"arkSats": 20000, "onchainSats": 10000, "onchainTxid": txid, "blocksMined": 1}


def produce(kind, vector, bootstrap, apk, helper, scratch, port):
    result = {"bootstrap": bootstrap, "publicVector": vector, "producer": kind}
    with Emulator(scratch, port) as device:
        result["sourceDevice"] = {"name": device.name, "serial": device.serial}
        device.install(apk)
        device.restore(kind, vector)
        result["funding"] = device.fund(kind)
        test.flow(device.serial, "backup-" + kind, scratch)
        result["source"] = device.capture(helper, vector)
        info = test.list_backups(helper, vector)[0]
        initial = bootstrap["object"]
        assert info["backup_id"] != initial["backup_id"] and info["created_at"] > initial["created_at"]
        assert info["encrypted_sha256"] != initial["encrypted_sha256"]
        result["backup"] = test.download_inspect(helper, vector, info, scratch)
        assert result["backup"]["snapshot"] == result["source"]["state"]
        assert result["source"]["state"]["spendable"] == 20000
    return result


def consume(result, kind, apk, helper, scratch, port):
    vector = result["publicVector"]
    with Emulator(scratch, port) as device:
        result["targetDevice"] = {"name": device.name, "serial": device.serial}
        assert test.list_backups(helper, vector)[0] == result["backup"]["object"]
        device.install(apk)
        device.restore(kind, vector)
        result["restored"] = device.capture(helper, vector)
        test.check_restored(result["source"], result["restored"])
        device.launch()
        destination = next(line for line in test.command("just", "bark", "--quiet", "--no-logfile", "address").splitlines() if line.startswith("tark1"))
        device.clipboard(destination)
        path = test.ROOT / ("client/.maestro/subflows" if kind == "rn" else "apps/maestro/subflows") / ("send-funded-ark.yml" if kind == "rn" else "send-ark-from-clipboard.yml")
        test.flow(device.serial, str(path), scratch, {"DESTINATION": destination})
        result["spent"] = device.capture(helper, vector)
        test.check_spent(result["restored"], result["spent"])
        output = test.command("just", "bark", "--quiet", "--no-logfile", "movements")
        rows = json.JSONDecoder().raw_decode(output[output.index("[\n"):])[0]
        received = [row for row in rows if row.get("intended_balance_sat") == 5000 and any(
            value.get("destination", {}).get("value") == destination for value in row.get("received_on", []))]
        assert len(received) == 1
        result["spent"].update(amount=5000, destination=destination, receiver=received[0]["id"])
        result["newestObjectMatchedBeforeRestore"] = True
        result["status"] = "pass"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rn-apk", type=Path, required=True)
    parser.add_argument("--native-apk", type=Path, required=True)
    parser.add_argument("--native-derived-data", type=Path, required=True)
    parser.add_argument("--port", type=int, default=5620)
    parser.add_argument("--evidence", type=Path, default=Path("/private/tmp/noah-rn-native-android-evidence.json"))
    args = parser.parse_args()
    os.umask(0o077)
    scratch = Path(tempfile.mkdtemp(prefix="noah-rn-native-android-", dir="/private/tmp"))
    evidence = {}
    try:
        helper = test.compile_helper(args.native_derived_data.resolve(), scratch)
        for vector in ("C", "D"):
            test.register_vector(helper, vector)
            assert not test.list_backups(helper, vector), "Public vectors already have backups; use an independently reset environment"
        for source, target, vector, source_apk, target_apk in (("rn", "native", "C", args.rn_apk, args.native_apk),
                                                             ("native", "rn", "D", args.native_apk, args.rn_apk)):
            key = source + "-to-" + target
            evidence[key] = produce(source, vector, test.bootstrap(helper, vector, scratch), source_apk.resolve(), helper, scratch, args.port)
            args.evidence.write_text(json.dumps(evidence, indent=2) + "\n")
            consume(evidence[key], target, target_apk.resolve(), helper, scratch, args.port)
            print(key + " Android passed", flush=True)
    finally:
        args.evidence.write_text(json.dumps(evidence, indent=2) + "\n")
        shutil.rmtree(scratch)


if __name__ == "__main__":
    main()
