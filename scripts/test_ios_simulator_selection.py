#!/usr/bin/env python3
"""Exercise CI's simulator creation using the selected Xcode SDK."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
from unittest.mock import patch


workflow = Path(__file__).resolve().parents[1] / ".github/workflows/noah-maestro-test-ios.yml"
script = textwrap.dedent(workflow.read_text().split("python3 - <<'PYTHON'\n")[1].split("PYTHON")[0])
device = {"name": "iPhone 17 Pro", "identifier": "iPhone 17 Pro"}
devices = [device]
runtimes = [
    {"name": "iOS " + version, "version": version, "isAvailable": True,
     "identifier": "com.apple.CoreSimulator.SimRuntime.iOS-" + version.replace(".", "-"),
     "supportedDeviceTypes": [device]}
    for version in ("26.5", "27.0", "27.1")
]

for sdk_version, runtime in (("26.5.0", runtimes[0]), ("27.0", runtimes[1])):
    created = []

    def simctl(command, **kwargs):
        if command[1:] == ["--sdk", "iphonesimulator", "--show-sdk-version"]:
            return sdk_version + "\n"
        if command[2:4] == ["list", "devicetypes"]:
            return json.dumps({"devicetypes": devices})
        if command[2:4] == ["list", "runtimes"]:
            return json.dumps({"runtimes": runtimes})
        assert command[2] == "create", command
        created.append(command[-2:])
        return "ci-simulator-id"

    with tempfile.TemporaryDirectory() as directory:
        env_file = Path(directory) / "github-env"
        with patch.dict(os.environ, GITHUB_ENV=str(env_file)), \
             patch.object(subprocess, "check_output", side_effect=simctl), \
             patch.object(subprocess, "check_call"):
            exec(compile(script, str(workflow), "exec"), {})
        assert created == [[device["identifier"], runtime["identifier"]]], created
        assert env_file.read_text() == "SIMULATOR_ID=ci-simulator-id\n"

print("PASS: CI uses iPhone 17 Pro with the selected Xcode SDK instead of a newer installed runtime")
