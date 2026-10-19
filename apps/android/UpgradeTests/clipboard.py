#!/usr/bin/env python3
"""Target this emulator's authenticated gRPC clipboard, never the host clipboard."""
import json
import os
from pathlib import Path
import subprocess
import sys

serial, action = sys.argv[1:]
if not serial.startswith("emulator-") or serial == "emulator-5554":
    sys.exit("Use the dedicated upgrade emulator")
discovery = (Path.home() / "Library/Caches/TemporaryItems/avd/running"
             if sys.platform == "darwin" else
             Path(os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")) / "avd/running")
config = None
for path in discovery.glob("pid_*.ini"):
    values = dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line)
    if values.get("port.serial") == serial.removeprefix("emulator-"):
        config = values
        break
if not config or not config.get("grpc.token") or not config.get("grpc.port"):
    sys.exit("Boot the dedicated emulator with -grpc <unused-port> -grpc-use-token")
proto = Path(os.environ.get("EMULATOR_PROTO_DIR", str(Path(os.environ["ANDROID_HOME"]) / "emulator/lib")))
args = ["grpcurl", "-max-time", "30", "-plaintext", "-H",
        "authorization: Bearer " + config["grpc.token"],
        "-import-path", str(proto), "-proto", "emulator_controller.proto"]
if action == "get":
    method, payload = "getClipboard", None
elif action == "set":
    method, payload = "setClipboard", json.dumps({"text": sys.stdin.read()})
    args += ["-d", "@"]
else:
    sys.exit("Expected get or set")
result = subprocess.run(args + ["localhost:" + config["grpc.port"],
    "android.emulation.control.EmulatorController/" + method],
    input=payload, capture_output=True, text=True)
if result.returncode:
    sys.exit("Emulator clipboard RPC failed")
if action == "get":
    print(json.loads(result.stdout).get("text", ""), end="")
