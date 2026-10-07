#!/usr/bin/env python3
"""Exercise CI's simulator creation with device-specific runtime support."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
from unittest.mock import patch


workflow = Path(__file__).resolve().parents[1] / ".github/workflows/noah-maestro-test-ios.yml"
script = textwrap.dedent(workflow.read_text().split("python3 - <<'PYTHON'\n")[1].split("PYTHON")[0])
devices = [{"name": name, "identifier": name} for name in ("iPhone 17 Pro", "iPhone Duo")]
runtimes = [
    {"name": "iOS " + version, "version": version, "isAvailable": True,
     "identifier": "com.apple.CoreSimulator.SimRuntime.iOS-" + version.replace(".", "-"),
     "supportedDeviceTypes": [device]}
    for version, device in zip(("27.0", "27.1"), devices)
]

for device, runtime in zip(devices, runtimes):
    created = []

    def simctl(command, **kwargs):
        if command[2:4] == ["list", "devicetypes"]:
            return json.dumps({"devicetypes": devices})
        if command[2:4] == ["list", "runtimes"]:
            return json.dumps({"runtimes": runtimes})
        assert command[2] == "create", command
        created.append(command[-2:])
        return "ci-simulator-id"

    with tempfile.TemporaryDirectory() as directory:
        env_file = Path(directory) / "github-env"
        with patch.dict(os.environ, SIMULATOR_MODEL=device["name"], GITHUB_ENV=str(env_file)), \
             patch.object(subprocess, "check_output", side_effect=simctl), \
             patch.object(subprocess, "check_call"):
            exec(compile(script, str(workflow), "exec"), {})
        assert created == [[device["identifier"], runtime["identifier"]]], created
        assert env_file.read_text() == "SIMULATOR_ID=ci-simulator-id\n"

print("PASS: CI selects iOS 27.0 for iPhone 17 Pro and iOS 27.1 for Duo")
