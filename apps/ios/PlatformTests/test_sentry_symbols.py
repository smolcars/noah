"""Run after xcodegen generate; no network or Sentry credentials required."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


IOS = Path(__file__).resolve().parents[1]
SCRIPT = IOS / "Scripts/upload-sentry-symbols.sh"


class SentrySymbolsTests(unittest.TestCase):
    def run_phase(self, **overrides):
        with tempfile.TemporaryDirectory(prefix="noah-symbols-") as directory:
            root = Path(directory)
            marker = root / "arguments"
            cli = root / "sentry-cli"
            cli.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$PROBE_MARKER"\n')
            cli.chmod(0o755)
            env = dict(os.environ, CONFIGURATION="Release-Mainnet", PLATFORM_NAME="iphoneos",
                       ACTION="install", SENTRY_AUTH_TOKEN="synthetic-test-token",
                       SENTRY_ORG="test-org", SENTRY_PROJECT="test-project",
                       DWARF_DSYM_FOLDER_PATH=str(root / "app symbols"),
                       SENTRY_CLI_EXECUTABLE=str(cli), PROBE_MARKER=str(marker))
            env.update(overrides)
            result = subprocess.run(["/bin/sh", str(SCRIPT)], env=env, capture_output=True)
            args = marker.read_text().splitlines() if marker.exists() else None
            return result.returncode, args

    def test_production_archives_upload_symbols(self):
        for configuration in ["Release-Mainnet", "Release-Signet"]:
            with self.subTest(configuration=configuration):
                code, args = self.run_phase(CONFIGURATION=configuration)
                self.assertEqual(code, 0)
                self.assertEqual(args[:3], ["debug-files", "upload", "--wait"])
                self.assertTrue(args[3].endswith("/app symbols"))
                self.assertEqual(len(args), 4)

    def test_debug_regtest_simulator_and_nonarchives_skip(self):
        for settings in [
            {"CONFIGURATION": "Debug-Mainnet"}, {"CONFIGURATION": "Debug-Signet"},
            {"CONFIGURATION": "Debug-Regtest"}, {"CONFIGURATION": "Release-Regtest"},
            {"PLATFORM_NAME": "iphonesimulator"}, {"ACTION": "build"},
        ]:
            with self.subTest(settings=settings):
                self.assertEqual(self.run_phase(**settings), (0, None))

    def test_missing_upload_credentials_fail_archives(self):
        for key in ["SENTRY_AUTH_TOKEN", "SENTRY_ORG", "SENTRY_PROJECT"]:
            with self.subTest(key=key):
                code, args = self.run_phase(**{key: ""})
                self.assertNotEqual(code, 0)
                self.assertIsNone(args)

    def test_missing_cli_fails_archives(self):
        code, args = self.run_phase(SENTRY_CLI_EXECUTABLE="/nonexistent/sentry-cli")
        self.assertNotEqual(code, 0)
        self.assertIsNone(args)

    def test_generated_app_target_has_exactly_one_upload_phase(self):
        project = IOS / "Noah.xcodeproj/project.pbxproj"
        result = subprocess.run(["plutil", "-convert", "json", "-o", "-", str(project)],
                                check=True, capture_output=True)
        objects = json.loads(result.stdout)["objects"]
        target = next(value for value in objects.values()
                      if value.get("isa") == "PBXNativeTarget" and value.get("name") == "Noah")
        phases = [objects[key] for key in target["buildPhases"]
                  if objects[key].get("name") == "Upload Debug Symbols to Sentry"]
        self.assertEqual(len(phases), 1)
        self.assertEqual(str(phases[0]["showEnvVarsInLog"]), "0")
        self.assertIn("$(DWARF_DSYM_FOLDER_PATH)/$(DWARF_DSYM_FILE_NAME)", phases[0]["inputPaths"])
        self.assertEqual(phases[0]["shellScript"].strip(), SCRIPT.read_text().strip())
        configs = objects[target["buildConfigurationList"]]["buildConfigurations"]
        for key in configs:
            config = objects[key]
            self.assertEqual(config["buildSettings"]["DEBUG_INFORMATION_FORMAT"], "dwarf-with-dsym")


if __name__ == "__main__":
    unittest.main()
