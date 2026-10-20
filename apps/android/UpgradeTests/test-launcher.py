#!/usr/bin/env python3
"""Exercise the upgrade gate with Android 15's real dumpsys record layout."""
import pathlib
import subprocess
import tempfile
import unittest

BUNDLE = "com.noahwallet.regtest"
DUMP = """Providers:
Widgets:
  [0] id=2
    host=HostId{user:0, app:10177, hostId:1024, pkg:com.google.android.apps.nexuslauncher}
    provider=ProviderId{user:0, app:10208, cmp:ComponentInfo{com.noahwallet.regtest/com.noahwallet.widgets.NoahWidgetRegtestProvider}}
    host.callbacks=ignored
Hosts:
"""

class LauncherGateTest(unittest.TestCase):
    def check(self, after=DUMP, shortcut=True, before=DUMP):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for stage, dump in [("before", before), ("after", after)]:
                (root / f"widgets-{stage}.txt").write_text(dump)
                (root / f"shortcuts-{stage}.txt").write_text("noah-upgrade-check" if shortcut else "")
            return subprocess.run(["python3", str(pathlib.Path(__file__).with_name("check-launcher.py")), BUNDLE, directory], capture_output=True).returncode

    def test_same_identity_ignores_callback_address(self):
        self.assertEqual(0, self.check(DUMP.replace("callbacks=ignored", "callbacks=new")))

    def test_changed_id_component_or_host_blocks_upgrade(self):
        for before, after in [("id=2", "id=3"), ("NoahWidgetRegtestProvider", "OtherProvider"), ("hostId:1024", "hostId:1025")]:
            self.assertNotEqual(0, self.check(DUMP.replace(before, after)))

    def test_missing_placement_or_shortcut_cannot_pass(self):
        self.assertNotEqual(0, self.check(shortcut=False))
        self.assertNotEqual(0, self.check(before="Widgets:\nHosts:\n", after="Widgets:\nHosts:\n"))

if __name__ == "__main__":
    unittest.main()
