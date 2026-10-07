#!/usr/bin/env python3
"""Check that creating a CI Bark wallet leaves the local wallet untouched."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile


script = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).with_name("ark-dev.sh")
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    for project in ("scripts", "noah-ci-test"):
        wallet = root / f"{project}_bark" / ".bark"
        wallet.mkdir(parents=True)
        (wallet / "existing-wallet").write_text(project)

    # Model the Docker volume selection at the CLI boundary, without real wallets.
    cli = root / "docker-compose"
    cli.write_text(
        f"#!{sys.executable}\n"
        "import os, pathlib, shutil, sys\n"
        "root = pathlib.Path(os.environ['TEST_VOLUMES'])\n"
        "args = sys.argv[1:]\n"
        "if pathlib.Path(sys.argv[0]).name == 'docker':\n"
        "    volume = args[args.index('-v') + 1].split(':')[0]\n"
        "else:\n"
        "    volume = os.environ.get('COMPOSE_PROJECT_NAME', 'scripts') + '_bark'\n"
        "wallet = root / volume / '.bark'\n"
        "if 'create' in args:\n"
        "    wallet.mkdir(parents=True, exist_ok=True)\n"
        "    (wallet / 'new-wallet').touch()\n"
        "else:\n"
        "    shutil.rmtree(wallet, ignore_errors=True)\n"
    )
    cli.chmod(0o755)
    (root / "docker").symlink_to(cli)
    result = subprocess.run(
        ["bash", str(script), "create-bark-wallet"],
        capture_output=True,
        text=True,
        env={
            **os.environ,
            "PATH": str(root) + os.pathsep + os.environ["PATH"],
            "TEST_VOLUMES": str(root),
            "COMPOSE_PROJECT_NAME": "noah-ci-test",
        },
    )
    assert result.returncode == 0, result.stderr
    local_wallet = root / "scripts_bark" / ".bark" / "existing-wallet"
    assert local_wallet.exists(), "CI bootstrap deleted the local Bark wallet"
    assert local_wallet.read_text() == "scripts"
    ci_wallet = root / "noah-ci-test_bark" / ".bark"
    assert not (ci_wallet / "existing-wallet").exists(), "CI wallet was not reset"
    assert (ci_wallet / "new-wallet").exists(), "CI wallet was not created"

print("PASS: CI wallet recreation preserves the local Bark wallet")
