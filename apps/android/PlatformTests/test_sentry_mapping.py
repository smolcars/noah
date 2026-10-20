"""Credential-free checks for the CI-only mapping upload boundary."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/upload-sentry-mapping.sh"
UUID = "53e98d84-ef56-4eab-89d1-c508652f39dc"


class SentryMappingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="noah-sentry-mapping-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.mapping = self.root / "mapping.txt"
        self.mapping.write_text("com.noahwallet.Example -> a:\n")
        self.metadata = self.root / "sentry-debug-meta.properties"
        self.metadata.write_text(f"io.sentry.ProguardUuids={UUID}\n")
        self.record = self.root / "arguments"
        self.cli = self.root / "fake-sentry-cli"
        self.cli.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$TEST_RECORD"\n'
                            'echo "synthetic provider details"\nexit "${TEST_EXIT:-0}"\n')
        self.cli.chmod(0o700)
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith("SENTRY_") and key != "CI"}
        self.env.update(CI="true", SENTRY_AUTH_TOKEN="synthetic-credential",
                        SENTRY_ORG="synthetic-org", SENTRY_PROJECT="synthetic-project",
                        SENTRY_CLI_EXECUTABLE=str(self.cli), TEST_RECORD=str(self.record))

    def run_upload(self):
        return subprocess.run(["sh", str(SCRIPT), str(self.mapping), str(self.metadata)],
                              env=self.env, text=True, capture_output=True, timeout=10)

    def test_local_build_never_invokes_cli_even_with_credentials(self):
        for ci in ("", "false"):
            self.env["CI"] = ci
            self.assertEqual(0, self.run_upload().returncode)
            self.assertFalse(self.record.exists())

    def test_ci_missing_each_credential_fails_before_invoking_cli(self):
        for key in ("SENTRY_AUTH_TOKEN", "SENTRY_ORG", "SENTRY_PROJECT"):
            original = self.env.pop(key)
            result = self.run_upload()
            self.assertNotEqual(0, result.returncode)
            self.assertIn("requires", result.stderr)
            self.assertNotIn("synthetic-credential", result.stderr)
            self.assertFalse(self.record.exists())
            self.env[key] = original

    def test_ci_missing_cli_fails_closed(self):
        self.env["SENTRY_CLI_EXECUTABLE"] = str(self.root / "missing")
        self.assertNotEqual(0, self.run_upload().returncode)
        self.assertFalse(self.record.exists())

    def test_missing_mapping_or_metadata_fails_closed(self):
        for path in (self.mapping, self.metadata):
            original = path.read_text()
            path.unlink()
            self.assertNotEqual(0, self.run_upload().returncode)
            self.assertFalse(self.record.exists())
            path.write_text(original)

    def test_invalid_mapping_uuid_never_invokes_cli(self):
        self.metadata.write_text("io.sentry.ProguardUuids=invalid\n")
        self.assertNotEqual(0, self.run_upload().returncode)
        self.assertFalse(self.record.exists())

    def test_ci_uploads_only_mapping_with_the_embedded_uuid(self):
        for ci in ("true", "1"):
            self.env["CI"] = ci
            result = self.run_upload()
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(["upload-proguard", "--uuid", UUID, "--require-one", str(self.mapping)],
                             self.record.read_text().splitlines())
            self.assertNotIn("synthetic", result.stdout + result.stderr)

    def test_upload_error_fails_ci_without_printing_provider_details(self):
        self.env["TEST_EXIT"] = "1"
        result = self.run_upload()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("CI release is blocked", result.stderr)
        self.assertNotIn("synthetic", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
